package app.dkord.data.lyrics

import app.dkord.core.DebugLog
import app.dkord.core.Track
import app.dkord.data.net.Http
import app.dkord.data.yt.at
import app.dkord.data.yt.lenientJson
import app.dkord.data.yt.list
import app.dkord.data.yt.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

data class LyricLine(val timeMs: Long, val text: String)

/** [synced] is empty when only plain text exists. */
data class Lyrics(val synced: List<LyricLine>, val plain: String?)

object LrcParser {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun parse(lrc: String): List<LyricLine> = lrc.lineSequence().flatMap { line ->
        val stamps = stamp.findAll(line).toList()
        val text = stamp.replace(line, "").trim()
        stamps.asSequence().map { m ->
            val frac = m.groupValues[3]
            val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
            LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms, text)
        }
    }.sortedBy { it.timeMs }.toList()

    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/** Free synced lyrics from LRCLIB (https://lrclib.net, open source, no key). */
class LyricsRepository {
    private val cache = ConcurrentHashMap<String, Lyrics>()

    /** Null when nothing was found. Network errors propagate so the UI can offer a retry. */
    suspend fun load(track: Track): Lyrics? {
        cache[track.id]?.let { return it }
        return withContext(Dispatchers.IO) {
            val title = clean(track.title); val artist = track.artist.removeSuffix(" - Topic")
            val exact = get("get", "track_name" to title, "artist_name" to artist, "album_name" to track.album, "duration" to track.durationSec.takeIf { it > 0 }?.toString())
            val hit = exact?.let(::parse)
                ?: get("search", "track_name" to title, "artist_name" to artist)?.list()?.firstNotNullOfOrNull { parse(it)?.takeIf { l -> l.synced.isNotEmpty() } }
            hit?.also { cache[track.id] = it }
        }
    }

    private fun parse(e: JsonElement): Lyrics? {
        val synced = e.at("syncedLyrics").str()?.let(LrcParser::parse).orEmpty()
        val plain = e.at("plainLyrics").str()
        return if (synced.isEmpty() && plain.isNullOrBlank()) null else Lyrics(synced, plain)
    }

    private fun get(path: String, vararg params: Pair<String, String?>): JsonElement? {
        val url = "https://lrclib.net/api/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).header("User-Agent", "Dkord (https://github.com/DeepuExplainsAi/best-best)").build()
        Http.client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) { DebugLog.w("lyrics", "HTTP ${r.code}"); return null }
            return lenientJson.parseToJsonElement(r.body.string())
        }
    }

    companion object {
        private val noise = Regex("\\s*[(\\[][^)\\]]*(official|video|audio|lyric|lyrics|visualizer|hd|4k|remaster)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
        fun clean(title: String) = noise.replace(title, "").trim()
    }
}
