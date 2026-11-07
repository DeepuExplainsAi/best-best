package app.dkord

import app.dkord.data.update.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
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
        val downloadedVersions = mutableListOf<String>()
        val installerVersions = mutableListOf<String>()
        var missingFile = false
        var installerOpens = 0
        var allowed = true
        var checkFailure = false
        var installFailure = false
        var transfer: suspend ((UpdateProgress) -> Unit) -> Unit = { it(UpdateProgress(30_000_000, 30_000_000)) }
        override suspend fun findUpdate(): UpdateInfo? { checks++; delay(1); if (checkFailure) error("offline"); return info }
        override suspend fun download(info: UpdateInfo, onProgress: (UpdateProgress) -> Unit) {
            downloads++; downloadedVersions += info.version; transfer(onProgress)
        }
        override fun canInstall() = allowed
        override fun openInstaller(info: UpdateInfo) {
            installerOpens++; installerVersions += info.version
            if (missingFile) throw UpdateVerificationException()
            if (installFailure) error("no installer")
        }
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
        controller.check(); advanceUntilIdle(); controller.download(); advanceTimeBy(1); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, controller.state.value.phase)
        assertEquals(20_000_000L, controller.state.value.progress.remainingBytes)
        controller.download(); controller.check(force = true)
        advanceUntilIdle()
        assertEquals(1, platform.downloads)
        assertEquals(2, platform.checks) // The second check resolves the latest APK after consent.
        assertEquals(1, platform.installerOpens)
        assertEquals(UpdatePhase.READY, controller.state.value.phase)
    }

    @Test fun cancellation_returns_to_available_without_installing_or_prompting_again() = runTest {
        val platform = Platform().apply { transfer = { awaitCancellation() } }
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle(); controller.download(); advanceTimeBy(1); runCurrent(); controller.cancel(); advanceUntilIdle()
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

    @Test fun consent_resolves_the_latest_release_and_skips_all_intermediate_apks() = runTest {
        val platform = Platform()
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle()
        assertEquals("0.6.0", controller.state.value.info?.version)
        platform.info = UpdateInfo("0.9.0", "https://example.test/latest-0.9.0.apk", 31_000_000)
        controller.download(); advanceUntilIdle()
        assertEquals(listOf("0.9.0"), platform.downloadedVersions)
        assertEquals(listOf("0.9.0"), platform.installerVersions)
        assertEquals("0.9.0", controller.state.value.info?.version)
    }

    @Test fun cancelling_latest_resolution_never_downloads_the_advertised_old_version() = runTest {
        val platform = Platform()
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle()
        controller.download(); runCurrent()
        assertEquals(UpdatePhase.PREPARING, controller.state.value.phase)
        controller.cancel(); advanceUntilIdle()
        assertEquals(UpdatePhase.AVAILABLE, controller.state.value.phase)
        assertFalse(controller.state.value.promptAllowed)
        assertEquals(0, platform.downloads)
        assertEquals(0, platform.installerOpens)
    }

    @Test fun a_ready_old_apk_cannot_block_detection_of_a_newer_release() = runTest {
        var elapsed = 0L
        val platform = Platform()
        val controller = UpdateCoordinator(platform, this, elapsed = { elapsed })
        controller.check(); advanceUntilIdle(); controller.download(); advanceUntilIdle()
        assertEquals(UpdatePhase.READY, controller.state.value.phase)
        platform.info = UpdateInfo("0.9.0", "https://example.test/latest-0.9.0.apk")
        elapsed += UpdateCoordinator.CHECK_INTERVAL_MS
        controller.onForeground(); advanceUntilIdle()
        assertEquals(UpdatePhase.AVAILABLE, controller.state.value.phase)
        assertEquals("0.9.0", controller.state.value.info?.version)
        assertTrue(controller.state.value.promptAllowed)
        controller.install()
        assertEquals(1, platform.installerOpens) // The superseded ready file cannot be opened again.
    }

    @Test fun checking_the_same_ready_release_preserves_permission_and_progress_without_downloading() = runTest {
        val platform = Platform().apply { allowed = false }
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle(); controller.download(); advanceUntilIdle()
        val previous = controller.state.value
        controller.check(force = true); advanceUntilIdle()
        assertEquals(UpdatePhase.READY, controller.state.value.phase)
        assertEquals(previous.progress, controller.state.value.progress)
        assertTrue(controller.state.value.needsInstallPermission)
        assertEquals(1, platform.downloads)
        assertEquals(1, platform.installerOpens)
    }

    @Test fun installing_an_aged_ready_apk_rechecks_latest_but_reuses_an_unchanged_verified_file() = runTest {
        var elapsed = 0L
        val platform = Platform()
        val controller = UpdateCoordinator(platform, this, elapsed = { elapsed })
        controller.check(); advanceUntilIdle(); controller.download(); advanceUntilIdle()
        controller.onForeground(); advanceUntilIdle() // The user cancelled the external installer.
        elapsed += UpdateCoordinator.CHECK_INTERVAL_MS
        controller.install(); advanceUntilIdle()
        assertEquals(3, platform.checks)
        assertEquals(1, platform.downloads)
        assertEquals(2, platform.installerOpens)
    }

    @Test fun a_missing_cached_file_can_be_downloaded_again_instead_of_looping_the_installer() = runTest {
        val platform = Platform().apply { missingFile = true }
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle(); controller.download(); advanceUntilIdle()
        assertEquals(UpdatePhase.FAILED, controller.state.value.phase)
        assertEquals(UpdateFailure.VERIFICATION, controller.state.value.failure)
        platform.missingFile = false
        controller.download(); advanceUntilIdle()
        assertEquals(2, platform.downloads)
        assertEquals(UpdatePhase.READY, controller.state.value.phase)
    }

    @Test fun a_failed_latest_resolution_does_not_silently_download_a_stale_release() = runTest {
        val platform = Platform()
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle()
        platform.checkFailure = true
        controller.download(); advanceUntilIdle()
        assertEquals(UpdatePhase.FAILED, controller.state.value.phase)
        assertEquals(UpdateFailure.NETWORK, controller.state.value.failure)
        assertEquals(0, platform.downloads)
        platform.checkFailure = false
        platform.info = UpdateInfo("0.9.0", "https://example.test/latest-0.9.0.apk")
        controller.download(); advanceUntilIdle()
        assertEquals(listOf("0.9.0"), platform.downloadedVersions)
    }

    @Test fun repeated_install_taps_do_not_stack_duplicate_system_installers() = runTest {
        val platform = Platform()
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle(); controller.download(); advanceUntilIdle()
        controller.install(); controller.install()
        assertEquals(1, platform.installerOpens)
        controller.onForeground(); advanceUntilIdle()
        controller.install(); controller.install()
        assertEquals(2, platform.installerOpens)
    }

    @Test fun a_withdrawn_or_no_longer_new_release_does_not_download_a_stale_advertisement() = runTest {
        val platform = Platform()
        val controller = UpdateCoordinator(platform, this)
        controller.check(); advanceUntilIdle()
        platform.info = null
        controller.download(); advanceUntilIdle()
        assertEquals(UpdatePhase.UP_TO_DATE, controller.state.value.phase)
        assertNull(controller.state.value.info)
        assertEquals(0, platform.downloads)
    }
}
