package app.dkord

import app.dkord.data.update.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateMetadataTest {
    private val apk = "https://github.com/DeepuExplainsAi/dkord/releases/download/v0.6.0/Dkord-v0.6.0.apk"
    private fun release(url: String = apk, extra: String = "", digest: String = "null") = """
        {"tag_name":"v0.6.0","draft":false,"prerelease":false,$extra
         "assets":[{"name":"Dkord-v0.6.0.apk","size":30483450,"digest":$digest,"browser_download_url":"$url"}]}
    """.trimIndent()

    @Test fun release_size_and_sha256_are_preserved() {
        val digest = "ab".repeat(32)
        val info = checkNotNull(UpdateReleases.parse(release(digest = "\"sha256:$digest\""), "0.5.0"))
        assertEquals("0.6.0", info.version)
        assertEquals(30_483_450L, info.sizeBytes)
        assertEquals(digest, info.sha256)
        assertEquals(apk, info.apkUrl)
    }

    @Test fun current_older_draft_and_prerelease_do_not_prompt() {
        assertNull(UpdateReleases.parse(release(), "0.6.0"))
        assertNull(UpdateReleases.parse(release(), "0.7.0"))
        assertNull(UpdateReleases.parse(release().replace("\"draft\":false", "\"draft\":true"), "0.5.0"))
        assertNull(UpdateReleases.parse(release().replace("\"prerelease\":false", "\"prerelease\":true"), "0.5.0"))
    }

    @Test fun only_this_repositorys_https_release_apk_is_accepted() {
        for (url in listOf(apk.replace("https:", "http:"), apk.replace("github.com", "example.org"),
            apk.replace("DeepuExplainsAi", "someone"), apk.replace("download/v0.6.0", "download/v0.5.0"), "$apk?token=value")) {
            try { UpdateReleases.parse(release(url), "0.5.0"); fail("Unexpected URL accepted") }
            catch (_: IOException) { }
        }
    }

    @Test fun missing_or_ambiguous_assets_and_malformed_digest_fail_closed() {
        for (json in listOf(release().replace(".apk\"", ".zip\""),
            release().replace("}]}", "},{\"name\":\"other.apk\"}]}"), release(digest = "\"sha256:bad\""))) {
            try { UpdateReleases.parse(json, "0.5.0"); fail("Invalid release accepted") }
            catch (_: IOException) { }
        }
    }

    @Test fun progress_is_exact_and_unknown_size_is_not_invented() {
        val known = UpdateProgress(10_000_000, 30_000_000)
        assertEquals(20_000_000L, known.remainingBytes)
        assertEquals(1f / 3f, checkNotNull(known.fraction), 0.0001f)
        assertNull(UpdateProgress(20).remainingBytes)
        assertNull(UpdateProgress(20).fraction)
        assertEquals(0L, UpdateProgress(31, 30).remainingBytes)
        assertEquals(1f, checkNotNull(UpdateProgress(31, 30).fraction), 0f)
    }

    @Test fun later_expires_and_a_different_version_can_prompt_immediately() {
        val info = UpdateInfo("0.6.0", apk)
        val deferred = DeferredUpdate("0.6.0", 1000)
        assertFalse(shouldPromptUpdate(info, deferred, 999))
        assertTrue(shouldPromptUpdate(info, deferred, 1000))
        assertTrue(shouldPromptUpdate(info.copy(version = "0.6.1"), deferred, 999))
    }
}
