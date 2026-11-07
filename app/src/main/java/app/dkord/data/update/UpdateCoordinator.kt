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

    fun onForeground() {
        if (state.value.phase == UpdatePhase.READY && state.value.needsInstallPermission && platform.canInstall()) install()
        else check()
    }

    fun check(force: Boolean = false) {
        if (checkJob?.isCompleted == false || downloadJob?.isCompleted == false || state.value.phase == UpdatePhase.READY) return
        if (!force && elapsed() < nextCheck) return
        nextCheck = elapsed() + CHECK_INTERVAL_MS
        mutableState.update { it.copy(phase = UpdatePhase.CHECKING, failure = null, promptAllowed = false) }
        checkJob = scope.launch {
            try {
                val info = platform.findUpdate()
                mutableState.value = if (info == null) UpdateState(phase = UpdatePhase.UP_TO_DATE)
                else UpdateState(phase = UpdatePhase.AVAILABLE, info = info,
                    promptAllowed = handledVersion != info.version && shouldPromptUpdate(info, readDeferred(), now()))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                nextCheck = elapsed() + RETRY_INTERVAL_MS
                mutableState.update { it.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.NETWORK) }
            }
        }
    }

    fun later() {
        val info = state.value.info ?: return
        saveDeferred(DeferredUpdate(info.version, now() + LATER_MS))
        mutableState.update { it.copy(promptAllowed = false) }
    }

    fun download() {
        if (downloadJob?.isCompleted == false || state.value.phase == UpdatePhase.CHECKING) return
        val info = state.value.info ?: return
        if (state.value.phase == UpdatePhase.READY) { install(); return }
        handledVersion = info.version
        mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, info, UpdateProgress(totalBytes = info.sizeBytes))
        downloadJob = scope.launch {
            try {
                platform.download(info) { progress ->
                    mutableState.update { if (it.phase == UpdatePhase.DOWNLOADING) it.copy(progress = progress) else it }
                }
                mutableState.update { it.copy(phase = UpdatePhase.READY) }
                install()
            } catch (e: CancellationException) {
                mutableState.value = UpdateState(UpdatePhase.AVAILABLE, info)
                throw e
            } catch (_: UpdateVerificationException) {
                mutableState.update { it.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.VERIFICATION) }
            } catch (_: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.NETWORK) }
            }
        }
    }

    fun cancel() { downloadJob?.cancel() }

    fun install() {
        val value = state.value
        val info = value.info ?: return
        if (value.phase != UpdatePhase.READY) return
        try {
            val needsPermission = !platform.canInstall()
            mutableState.update { it.copy(needsInstallPermission = needsPermission, failure = null) }
            platform.openInstaller(info)
        } catch (_: Exception) {
            // Keep the verified APK so the user can retry the installer without downloading again.
            mutableState.update { it.copy(failure = UpdateFailure.INSTALLER) }
        }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 30L * 60 * 1000
        const val RETRY_INTERVAL_MS = 60_000L
        const val LATER_MS = 24L * 60 * 60 * 1000
    }
}
