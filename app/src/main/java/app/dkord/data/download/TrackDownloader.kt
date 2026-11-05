package app.dkord.data.download

import app.dkord.core.DebugLog
import app.dkord.data.stream.AudioQuality
import app.dkord.data.stream.StreamError
import app.dkord.data.stream.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Downloads one audio stream into [part], resuming from what is already there.
 * A resume only happens if the freshly resolved stream has the same mime type as the partial file
 * (opus bytes must never be appended to m4a bytes).
 */
class TrackDownloader(
    private val streams: StreamResolver,
    private val http: OkHttpClient,
    private val invalidate: (String) -> Unit,
    private val retryDelayMs: Long = 2_000,
) {
    class Result(val mime: String, val size: Long)

    /** [onProgress] is called for every chunk; the caller throttles. */
    suspend fun fetch(
        videoId: String,
        quality: AudioQuality,
        part: File,
        savedMime: String?,
        onProgress: (bytes: Long, total: Long, mime: String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return@withContext attemptOnce(videoId, quality, part, savedMime, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamError) {
                throw e
            } catch (e: IOException) {
                lastError = e
                DebugLog.w("download", "$videoId attempt ${attempt + 1} failed: ${e.message}")
                delay(retryDelayMs * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Download failed")
    }

    private suspend fun attemptOnce(
        id: String, quality: AudioQuality, part: File, savedMime: String?,
        onProgress: (Long, Long, String) -> Unit,
    ): Result {
        val stream = streams.resolve(id, quality, preferM4a = true)
        var offset = if (part.exists() && savedMime == stream.mimeType) part.length() else 0L
        if (offset == 0L) part.delete()

        val req = Request.Builder().url(stream.url)
            .header("Range", "bytes=$offset-")
            .apply { stream.userAgent?.let { header("User-Agent", it) } }
            .build()

        http.newCall(req).execute().use { res ->
            when (res.code) {
                403, 410 -> { invalidate(id); throw IOException("HTTP ${res.code}: link expired") }
                416 -> { part.delete(); throw IOException("HTTP 416: restarting") }
                !in 200..299 -> throw IOException("HTTP ${res.code}")
            }
            val resumed = res.code == 206 && offset > 0
            if (!resumed) offset = 0
            val total = if (resumed) contentRangeTotal(res.header("Content-Range")) ?: (offset + res.body.contentLength())
            else res.body.contentLength()

            RandomAccessFile(part, "rw").use { out ->
                out.setLength(offset)
                out.seek(offset)
                val buf = ByteArray(64 * 1024)
                var done = offset
                onProgress(done, total, stream.mimeType)
                res.body.source().let { src ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total, stream.mimeType)
                    }
                }
                if (total > 0 && done != total) throw IOException("Incomplete download ($done of $total bytes)")
                return Result(stream.mimeType, done)
            }
        }
    }

    private fun contentRangeTotal(h: String?): Long? = h?.substringAfterLast('/')?.toLongOrNull()

    private companion object { const val MAX_ATTEMPTS = 4 }
}
