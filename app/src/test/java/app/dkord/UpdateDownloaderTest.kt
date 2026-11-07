package app.dkord

import app.dkord.data.update.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class UpdateDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val info = UpdateInfo("0.6.0", "https://example.test/update.apk", 3)
    private fun client(body: String = "apk", code: Int = 200) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(body.toResponseBody()).build()
    }.build()

    @Test fun verified_download_is_atomic_and_reports_exact_final_bytes() = runTest {
        val file = File(temporary.root, "update.apk")
        val events = mutableListOf<UpdateProgress>()
        val digest = MessageDigest.getInstance("SHA-256").digest("apk".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        UpdateDownloader(client()).download(info.copy(sha256 = digest), file) { events += it }
        assertEquals("apk", file.readText())
        assertFalse(File(temporary.root, "update.apk.part").exists())
        assertEquals(3L, events.last().downloadedBytes)
        assertEquals(0L, events.last().remainingBytes)
    }

    @Test fun unknown_asset_size_uses_response_size() = runTest {
        val events = mutableListOf<UpdateProgress>()
        UpdateDownloader(client()).download(info.copy(sizeBytes = null), File(temporary.root, "update.apk")) { events += it }
        assertEquals(3L, events.last().totalBytes)
    }

    @Test fun mismatched_size_empty_body_and_bad_hash_leave_no_installable_or_partial_file() = runTest {
        val cases = listOf(info.copy(sizeBytes = 4) to "apk", info.copy(sizeBytes = null) to "", info.copy(sha256 = "00".repeat(32)) to "apk")
        for ((metadata, body) in cases) {
            val file = File(temporary.root, "update.apk")
            try { UpdateDownloader(client(body)).download(metadata, file) {}; fail("Unverified download accepted") }
            catch (_: UpdateVerificationException) { }
            assertFalse(file.exists())
            assertFalse(File(temporary.root, "update.apk.part").exists())
        }
    }

    @Test fun http_error_is_retryable_and_never_creates_an_apk() = runTest {
        val file = File(temporary.root, "update.apk")
        try { UpdateDownloader(client(code = 503)).download(info, file) {}; fail("HTTP error accepted") }
        catch (_: IOException) { }
        assertFalse(file.exists())
    }
}
