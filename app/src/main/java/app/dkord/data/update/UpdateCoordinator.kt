package app.dkord.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Android/filesystem operations are replaceable so the update lifecycle is unit-testable. */
interface UpdatePlatform {
    suspend fun findUpdate(): UpdateInfo?
    suspend fun download(info: UpdateInfo, onProgress: (UpdateProgress) -> Unit)
    fun canInstall(): Boolean
    /** Opens either the system installer or its required unknown-app-sources permission screen. */
    fun openInstaller(info: UpdateInfo)
}

/** One app-lifetime job/state shared by Home and Settings, not owned by either screen. */
class UpdateCoordinator(
    private val platform: UpdatePlatform,
    private val scope: CoroutineScope,
    private val readDeferred: () -> DeferredUpdate? = { null },
    private val saveDeferred: (DeferredUpdate) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableState = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var nextCheck = Long.MIN_VALUE
    private var handledVersion: String? = null
    private var installerOpen = false
    private fun busy() = checkJob?.isCompleted == false || downloadJob?.isCompleted == false

    fun onForeground() {
        installerOpen = false // Returning from the external permission/installer Activity permits a retry.
        if (state.value.phase == UpdatePhase.READY && state.value.needsInstallPermission && platform.canInstall()) install()
        else check()
    }

    fun check(force: Boolean = false) {
        if (busy() || (!force && elapsed() < nextCheck)) return
        val previous = state.value
        mutableState.update { it.copy(phase = UpdatePhase.CHECKING, failure = null, promptAllowed = false) }
        checkJob = scope.launch {
            try {
                val info = findLatest()
                mutableState.value = when {
                    info == null -> UpdateState(phase = UpdatePhase.UP_TO_DATE)
                    previous.phase == UpdatePhase.READY && previous.info == info -> previous.copy(failure = null)
                    else -> UpdateState(phase = UpdatePhase.AVAILABLE, info = info,
                        promptAllowed = handledVersion != info.version && shouldPromptUpdate(info, readDeferred(), now()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                nextCheck = elapsed() + RETRY_INTERVAL_MS
                mutableState.update { it.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.NETWORK, promptAllowed = false) }
            }
        }
    }

    fun later() {
        if (state.value.phase != UpdatePhase.AVAILABLE) return
        val info = state.value.info ?: return
        saveDeferred(DeferredUpdate(info.version, now() + LATER_MS))
        mutableState.update { it.copy(promptAllowed = false) }
    }

    /** Resolve /latest again after consent: never walk the user through intermediate releases. */
    fun download() {
        if (busy()) return
        val previous = state.value
        val advertised = previous.info ?: return
        handledVersion = advertised.version
        installerOpen = false
        mutableState.value = previous.copy(phase = UpdatePhase.PREPARING, failure = null, promptAllowed = false)
        downloadJob = scope.launch {
            try {
                val latest = findLatest()
                if (latest == null) {
                    mutableState.value = UpdateState(phase = UpdatePhase.UP_TO_DATE)
                    return@launch
                }
                handledVersion = latest.version
                val reuse = previous.phase == UpdatePhase.READY && previous.info == latest
                mutableState.value = if (reuse) previous.copy(failure = null, promptAllowed = false)
                else UpdateState(UpdatePhase.DOWNLOADING, latest, UpdateProgress(totalBytes = latest.sizeBytes))
                if (!reuse) platform.download(latest) { progress ->
                    mutableState.update { if (it.phase == UpdatePhase.DOWNLOADING) it.copy(progress = progress) else it }
                }
                mutableState.update { it.copy(phase = UpdatePhase.READY) }
                openReadyInstaller()
            } catch (e: CancellationException) {
                mutableState.update { UpdateState(UpdatePhase.AVAILABLE, it.info ?: advertised) }
                throw e
            } catch (_: UpdateVerificationException) {
                mutableState.update { it.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.VERIFICATION, promptAllowed = false) }
            } catch (_: Exception) {
                nextCheck = elapsed() + RETRY_INTERVAL_MS
                mutableState.update { it.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.NETWORK, promptAllowed = false) }
            }
        }
    }

    fun cancel() { downloadJob?.cancel() }

    fun install() {
        if (busy() || state.value.phase != UpdatePhase.READY || installerOpen) return
        // A week-old ready APK must not hide newer releases. Fresh permission returns work offline.
        if (elapsed() >= nextCheck) download() else openReadyInstaller()
    }

    private fun openReadyInstaller() {
        val info = state.value.info ?: return
        if (state.value.phase != UpdatePhase.READY || installerOpen) return
        try {
            installerOpen = true
            mutableState.update { it.copy(needsInstallPermission = !platform.canInstall(), failure = null) }
            platform.openInstaller(info)
        } catch (_: UpdateVerificationException) {
            installerOpen = false
            mutableState.update { it.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.VERIFICATION) }
        } catch (_: Exception) {
            installerOpen = false
            // Keep the verified APK for an installer retry, not a redundant download.
            mutableState.update { it.copy(failure = UpdateFailure.INSTALLER) }
        }
    }

    private suspend fun findLatest(): UpdateInfo? = platform.findUpdate().also { nextCheck = elapsed() + CHECK_INTERVAL_MS }

    companion object {
        const val CHECK_INTERVAL_MS = 30L * 60 * 1000
        const val RETRY_INTERVAL_MS = 60_000L
        const val LATER_MS = 24L * 60 * 60 * 1000
    }
}
