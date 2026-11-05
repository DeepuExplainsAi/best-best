package app.dkord.data.stream

import app.dkord.data.yt.at
import app.dkord.data.yt.list
import app.dkord.data.yt.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive

/** Maps an InnerTube /player response to a stream or a user-facing [StreamError]. */
object PlayerResponseParser {

    fun parse(videoId: String, root: JsonElement, quality: AudioQuality, preferM4a: Boolean, source: String, userAgent: String?): ResolvedStream {
        checkPlayability(root)
        val formats = root.at("streamingData", "adaptiveFormats").list()
        val candidates = formats.mapNotNull { f ->
            val mime = f.at("mimeType").str() ?: return@mapNotNull null
            val url = f.at("url").str() ?: return@mapNotNull null // signatureCipher-only formats can't be used here
            AudioCandidate(
                url = url,
                mimeType = mime,
                bitrate = (f.at("bitrate") as? JsonPrimitive)?.intOrNull ?: (f.at("averageBitrate") as? JsonPrimitive)?.intOrNull ?: 0, // nominal first: same field NewPipe reports, so every resolver picks the same itag
                contentLength = (f.at("contentLength") as? JsonPrimitive)?.content?.toLongOrNull(),
                isOriginalTrack = f.at("audioTrack", "audioIsDefault").let { it == null || (it as? JsonPrimitive)?.content == "true" },
            )
        }
        val pick = AudioPicker.pick(candidates, quality, preferM4a) ?: throw StreamError.NoAudio()
        val ttl = (root.at("streamingData", "expiresInSeconds").let { it as? JsonPrimitive })?.longOrNull
        val expiry = ttl?.let { System.currentTimeMillis() + it * 1000 } ?: expiryFromUrl(pick.url)
        return ResolvedStream(videoId, pick.url, pick.mimeType, pick.bitrate, pick.contentLength, minOf(expiry, expiryFromUrl(pick.url)), userAgent, source)
    }

    fun checkPlayability(root: JsonElement) {
        val status = root.at("playabilityStatus", "status").str() ?: throw StreamError.Unavailable("Malformed player response")
        if (status == "OK") return
        val reason = (root.at("playabilityStatus", "reason").str() ?: root.at("playabilityStatus", "errorScreen", "playerErrorMessageRenderer", "reason", "simpleText").str()).orEmpty()
        val low = reason.lowercase()
        throw when {
            "not a bot" in low || "confirm you" in low && "bot" in low -> StreamError.BotCheck()
            "age" in low && ("confirm" in low || "restrict" in low || "inappropriate" in low) || status == "AGE_VERIFICATION_REQUIRED" -> StreamError.AgeRestricted()
            "made for kids" in low || "kids" in low -> StreamError.MadeForKids()
            "private" in low -> StreamError.Private()
            "country" in low || "region" in low || "location" in low -> StreamError.Geo()
            status == "LOGIN_REQUIRED" -> StreamError.BotCheck(reason)
            else -> StreamError.Unavailable(reason.ifBlank { status })
        }
    }
}
