package app.dkord.data.stream

import java.io.IOException

enum class AudioQuality { LOW, MEDIUM, HIGH }

/** A direct, audio-only, progressive stream. [expiresAtMs] is when the URL stops working. */
data class ResolvedStream(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val contentLength: Long?,
    val expiresAtMs: Long,
    val userAgent: String?,
    val source: String,
) {
    val isM4a get() = mimeType.contains("mp4")
    fun isFresh(now: Long = System.currentTimeMillis(), marginMs: Long = 60_000) = expiresAtMs - marginMs > now
}

/** User-facing failure reasons. [fallbackAdvice] tells the player what to do next. */
sealed class StreamError(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class BotCheck(detail: String = "") : StreamError("YouTube asks to confirm you're not a bot. $detail".trim())
    class AgeRestricted : StreamError("This track is age-restricted and can't be played without a signed-in adult account.")
    class MadeForKids : StreamError("This track is restricted (made for kids).")
    class Geo : StreamError("This track isn't available in your country.")
    class Private : StreamError("This track is private.")
    class Unavailable(reason: String) : StreamError(reason.ifBlank { "This track is unavailable." })
    class Network(cause: Throwable) : StreamError("Network error: ${cause.message}", cause)
    class NoAudio : StreamError("No playable audio stream was found.")

    /** Higher = more useful to show when every resolver failed. */
    val priority: Int get() = when (this) {
        is AgeRestricted -> 7; is Private -> 6; is Geo -> 5; is MadeForKids -> 4
        is BotCheck -> 3; is Unavailable -> 2; is NoAudio -> 1; is Network -> 0
    }
}

interface StreamResolver {
    val name: String
    suspend fun resolve(videoId: String, quality: AudioQuality = AudioQuality.HIGH, preferM4a: Boolean = false): ResolvedStream
}

/** Raw candidate before choosing, kept free of any library type so the policy is unit-testable. */
data class AudioCandidate(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val contentLength: Long? = null,
    val isOriginalTrack: Boolean = true,
)

object AudioPicker {
    private val target = mapOf(AudioQuality.LOW to 64_000, AudioQuality.MEDIUM to 128_000, AudioQuality.HIGH to Int.MAX_VALUE)

    /** Audio-only m4a/opus. [preferM4a] is used for downloads (M4A tagging). Never returns video. */
    fun pick(all: List<AudioCandidate>, quality: AudioQuality, preferM4a: Boolean): AudioCandidate? {
        val audio = all.filter { it.mimeType.startsWith("audio/") && it.url.startsWith("http") }
        val originals = audio.filter { it.isOriginalTrack }.ifEmpty { audio }
        val pool = if (preferM4a) originals.filter { it.mimeType.contains("mp4") }.ifEmpty { originals } else originals
        val t = target.getValue(quality)
        val atOrBelow = pool.filter { it.bitrate <= t }
        // Best bitrate that does not exceed the target; if none, the lowest above it.
        return atOrBelow.maxByOrNull { it.bitrate } ?: pool.minByOrNull { it.bitrate }
    }
}

fun expiryFromUrl(url: String, fallbackSec: Long = 5 * 3600): Long =
    Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
        ?: (System.currentTimeMillis() + fallbackSec * 1000)
