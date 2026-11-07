package app.dkord.data.update

import app.dkord.data.net.Http
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Bounded progress events and atomic completion; partial/unverified APKs never reach the installer. */
class UpdateDownloader(client: OkHttpClient = Http.client) {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
        .followSslRedirects(false).build()
    suspend fun download(info: UpdateInfo, destination: File, onProgress: (UpdateProgress) -> Unit): Unit = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        var complete = false
        val call = http.newCall(Request.Builder().url(info.apkUrl).build())
        try {
            coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Update download failed")
                        val reported = response.body.contentLength().takeIf { it > 0 }
                        if (reported != null && info.sizeBytes != null && reported != info.sizeBytes) throw UpdateVerificationException()
                        val total = info.sizeBytes ?: reported
                        if (total != null && total > MAX_BYTES) throw UpdateVerificationException()
                        var downloaded = 0L
                        var lastEvent = 0L
                        val sha = MessageDigest.getInstance("SHA-256")
                        onProgress(UpdateProgress(0, total))
                        response.body.byteStream().use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                downloaded += count
                                if (downloaded > MAX_BYTES || total != null && downloaded > total) throw UpdateVerificationException()
                                output.write(buffer, 0, count)
                                sha.update(buffer, 0, count)
                                val time = System.nanoTime() / 1_000_000
                                if (time - lastEvent >= 150) { onProgress(UpdateProgress(downloaded, total)); lastEvent = time }
                            }
                        } }
                        currentCoroutineContext().ensureActive()
                        if (downloaded == 0L || total != null && downloaded != total) throw UpdateVerificationException()
                        val actual = sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        if (info.sha256 != null && actual != info.sha256) throw UpdateVerificationException()
                        if (destination.exists() && !destination.delete()) throw IOException("Cannot replace update file")
                        if (!partial.renameTo(destination)) throw IOException("Cannot finish update file")
                        complete = true
                        onProgress(UpdateProgress(downloaded, total ?: downloaded))
                    }
                } finally { cancellation.cancel() }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            if (!complete) partial.delete()
        }
    }

    companion object {
        const val MAX_BYTES = 250L * 1024 * 1024
    }
}
