package app.dkord

import app.dkord.data.update.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {
    private class Platform : UpdatePlatform {
        var info: UpdateInfo? = UpdateInfo("0.6.0", "https://example.test/update.apk", 30_000_000)
        var checks = 0
        var downloads = 0
        var installerOpens = 0
        var allowed = true
        var checkFailure = false
        var installFailure = false
        var transfer: suspend ((UpdateProgress) -> Unit) -> Unit = { it(UpdateProgress(30_000_000, 30_000_000)) }
        override suspend fun findUpdate(): UpdateInfo? { checks++; delay(1); if (checkFailure) error("offline"); return info }
        override suspend fun download(info: UpdateInfo, onProgress: (UpdateProgress) -> Unit) { downloads++; transfer(onProgress) }
        override fun canInstall() = allowed
        override fun openInstaller(info: UpdateInfo) { installerOpens++; if (installFailure) error("no installer") }
    }

    @Test fun automatic_check_is_throttled_and_does_not_download_without_consent() = runTest {
        val platform = Platform()
        val controller = UpdateCoordinator(platform, this, elapsed = { testScheduler.currentTime })
        controller.onForeground(); controller.onForeground(); advanceUntilIdle()
        assertEquals(1, platform.checks)
        assertEquals(0, platform.downloads)
        assertEquals(UpdatePhase.AVAILABLE, controller.state.value.phase)
        assertTrue(controller.state.value.promptAllowed)
        controller.onForeground(); advanceUntilIdle()
        assertEquals(1, platform.checks)
        controller.check(force = true); advanceUntilIdle()
        assertEquals(2, platform.checks)
    }

    @Test fun later_is_persisted_and_reappears_after_24_hours_or_for_a_new_version() = runTest {
        val platform = Platform()
        var deferred: DeferredUpdate? = null
        var now = 1000L
        val controller = UpdateCoordinator(platform, this, { deferred }, { deferred = it }, { now })
        controller.check(); advanceUntilIdle(); controller.later()
        assertFalse(controller.state.value.promptAllowed)
        assertEquals(now + UpdateCoordinator.LATER_MS, deferred?.untilMs)
        controller.check(force = true); advanceUntilIdle()
        assertFalse(controller.state.value.promptAllowed)
        now += UpdateCoordinator.LATER_MS
        controller.check(force = true); advanceUntilIdle()
        assertTrue(controller.state.value.promptAllowed)
        controller.later()
        platform.info = platform.info?.copy(version = "0.6.1")
        controller.check(force = true); advanceUntilIdle()
        assertTrue(controller.state.value.promptAllowed)
    }

    @Test fun shared_progress_and_installation_continue_without_a_screen_scope() = runTest {
        val platform = Platform()
        platform.transfer = { progress -> progress(UpdateProgress(10_000_000, 30_000_000)); delay(100); progress(UpdateProgress(30_000_000, 30_000_000)) }
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle(); controller.download(); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, controller.state.value.phase)
        assertEquals(20_000_000L, controller.state.value.progress.remainingBytes)
        controller.download(); controller.check(force = true)
        advanceUntilIdle()
        assertEquals(1, platform.downloads)
        assertEquals(1, platform.checks)
        assertEquals(1, platform.installerOpens)
        assertEquals(UpdatePhase.READY, controller.state.value.phase)
    }

    @Test fun cancellation_returns_to_available_without_installing_or_prompting_again() = runTest {
        val platform = Platform().apply { transfer = { awaitCancellation() } }
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle(); controller.download(); runCurrent(); controller.cancel(); advanceUntilIdle()
        assertEquals(UpdatePhase.AVAILABLE, controller.state.value.phase)
        assertFalse(controller.state.value.promptAllowed)
        assertEquals(0, platform.installerOpens)
    }

    @Test fun permission_return_opens_the_installer_once_without_redownloading() = runTest {
        val platform = Platform().apply { allowed = false }
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle(); controller.download(); advanceUntilIdle()
        assertTrue(controller.state.value.needsInstallPermission)
        assertEquals(1, platform.installerOpens)
        controller.onForeground(); advanceUntilIdle()
        assertEquals(1, platform.installerOpens)
        platform.allowed = true
        controller.onForeground(); controller.onForeground(); advanceUntilIdle()
        assertFalse(controller.state.value.needsInstallPermission)
        assertEquals(2, platform.installerOpens)
        assertEquals(1, platform.downloads)
    }

    @Test fun verification_failure_never_opens_installer() = runTest {
        val platform = Platform().apply { transfer = { throw UpdateVerificationException() } }
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle(); controller.download(); advanceUntilIdle()
        assertEquals(UpdatePhase.FAILED, controller.state.value.phase)
        assertEquals(UpdateFailure.VERIFICATION, controller.state.value.failure)
        assertEquals(0, platform.installerOpens)
    }

    @Test fun installer_retry_uses_the_verified_file_without_another_download() = runTest {
        val platform = Platform().apply { installFailure = true }
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle(); controller.download(); advanceUntilIdle()
        assertEquals(UpdatePhase.READY, controller.state.value.phase)
        assertEquals(UpdateFailure.INSTALLER, controller.state.value.failure)
        platform.installFailure = false
        controller.install()
        assertNull(controller.state.value.failure)
        assertEquals(1, platform.downloads)
        assertEquals(2, platform.installerOpens)
    }

    @Test fun offline_checks_are_quiet_and_manual_retry_can_recover() = runTest {
        val platform = Platform().apply { checkFailure = true }
        val controller = UpdateCoordinator(platform, this)
        controller.onForeground(); advanceUntilIdle()
        assertEquals(UpdatePhase.FAILED, controller.state.value.phase)
        assertFalse(controller.state.value.promptAllowed)
        platform.checkFailure = false
        controller.check(force = true); advanceUntilIdle()
        assertEquals(UpdatePhase.AVAILABLE, controller.state.value.phase)
    }

    @Test fun no_update_is_explicitly_reported() = runTest {
        val platform = Platform().apply { info = null }
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle()
        assertEquals(UpdatePhase.UP_TO_DATE, controller.state.value.phase)
        assertNull(controller.state.value.info)
    }
}
