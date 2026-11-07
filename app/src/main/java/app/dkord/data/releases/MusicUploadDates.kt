package app.dkord.data.releases

import app.dkord.core.Track
import app.dkord.data.net.Http
import app.dkord.data.yt.at
import app.dkord.data.yt.InnerTubeClient
import app.dkord.data.yt.lenientJson
import app.dkord.data.yt.str
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Read publication metadata only; an unavailable audio/player extraction cannot erase all dates. */
internal class MusicUploadDates(client: OkHttpClient = Http.client) {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
        .callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun fetch(track: Track): Long? = withContext(Dispatchers.IO) {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(track.id)) return@withContext null
        val request = Request.Builder().url("https://www.youtube.com/watch?v=${track.id}")
            .header("User-Agent", InnerTubeClient.WEB_UA).header("Accept-Language", "en-US,en;q=0.9").build()
        coroutineScope {
            val call = http.newCall(request)
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Release metadata unavailable")
                    val html = response.body.charStream().use { reader ->
                        val text = StringBuilder()
                        val buffer = CharArray(8192)
                        while (text.length < MAX_PAGE_CHARS) {
                            currentCoroutineContext().ensureActive()
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_PAGE_CHARS - text.length))
                            if (count < 0) break
                            text.append(buffer, 0, count)
                        }
                        text.toString()
                    }
                    publishedAt(html)
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally { cancellation.cancel() }
        }
    }

    companion object {
        private const val MAX_PAGE_CHARS = 3 * 1024 * 1024
    }
}

private val META = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE)
private val ATTRIBUTE = Regex("""([\w:-]+)\s*=\s*(["'])(.*?)\2""", RegexOption.DOT_MATCHES_ALL)
private val MICROFORMAT = Regex("\"playerMicroformatRenderer\"\\s*:\\s*\\{")

/** Only main-video metadata, never an arbitrary date from a recommendation or description. */
internal fun publishedAt(html: String): Long? {
    val marker = MICROFORMAT.find(html)
    val objectValue = marker?.let { jsonObjectAt(html, it.range.last) }
        ?.let { runCatching { lenientJson.parseToJsonElement(it) }.getOrNull() }
    objectValue.at("publishDate").str()?.let(::isoDate)?.let { return it }
    val metaDates = mutableMapOf<String, Long>()
    for (meta in META.findAll(html)) {
        val attributes = ATTRIBUTE.findAll(meta.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
        val name = (attributes["itemprop"] ?: attributes["property"] ?: attributes["name"]).orEmpty().lowercase()
        if (name in setOf("datepublished", "uploaddate", "video:release_date")) {
            attributes["content"]?.let(::isoDate)?.let { metaDates.putIfAbsent(name, it) }
        }
    }
    return metaDates["datepublished"] ?: metaDates["video:release_date"]
        ?: objectValue.at("uploadDate").str()?.let(::isoDate) ?: metaDates["uploaddate"]
}

private fun isoDate(value: String): Long? =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

/** Tiny bounded brace scanner; braces inside JSON strings do not terminate the object. */
private fun jsonObjectAt(text: String, start: Int): String? {
    var depth = 0
    var quoted = false
    var escaped = false
    for (index in start until minOf(text.length, start + 256 * 1024)) {
        val char = text[index]
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return text.substring(start, index + 1) }
        }
    }
    return null
}
