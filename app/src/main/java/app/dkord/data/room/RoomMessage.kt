package app.dkord.data.room

import app.dkord.core.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

@Serializable
data class RoomTrack(val videoId: String, val title: String, val artist: String, val thumbnail: String? = null) {
    fun toTrack() = Track(videoId, title, artist, thumbnail = thumbnail)
    companion object {
        fun fromTrack(track: Track) = RoomTrack(track.id, track.title, track.artist, track.thumbnail)
    }
}

@Serializable
data class RoomMessage(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String? = null,
    val positionMs: Long,
    val playing: Boolean,
    val timestamp: Long,
    val queue: List<RoomTrack> = emptyList(),
    val version: Int = 1,
)

/** Relay cursor is opaque; transports may use a message id or another resume token. */
data class RoomEvent(val message: RoomMessage, val cursor: String? = null)

object RoomCodec {
    const val MAX_BYTES = 4000 // Strictly less, including all JSON/UTF-8 overhead.
    const val MAX_QUEUE = 10
    const val MAX_POSITION_MS = 7L * 24 * 60 * 60 * 1000
    private val id = Regex("[A-Za-z0-9_-]{11}")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(message: RoomMessage): String {
        require(valid(message)) { "Invalid room message" }
        var clean = clean(message).copy(queue = message.queue.asSequence()
            .filter { id.matches(it.videoId) && it.videoId != message.videoId && short(it.title).isNotBlank() }
            .distinctBy { it.videoId }.take(MAX_QUEUE).map(::cleanTrack).toList())
        var text = json.encodeToString(clean)
        if (text.toByteArray(Charsets.UTF_8).size >= MAX_BYTES) {
            clean = clean.copy(thumbnail = null, queue = clean.queue.map { it.copy(thumbnail = null) })
            text = json.encodeToString(clean)
        }
        while (text.toByteArray(Charsets.UTF_8).size >= MAX_BYTES && clean.queue.isNotEmpty()) {
            clean = clean.copy(queue = clean.queue.dropLast(1))
            text = json.encodeToString(clean)
        }
        require(text.toByteArray(Charsets.UTF_8).size < MAX_BYTES) { "Room message too large" }
        return text
    }

    fun decode(text: String): RoomMessage? = try {
        if (text.toByteArray(Charsets.UTF_8).size >= MAX_BYTES) null
        else json.decodeFromString<RoomMessage>(text).takeIf {
            valid(it) && it.queue.size <= MAX_QUEUE && it.queue.all { track ->
                id.matches(track.videoId) && short(track.title).isNotBlank()
            }
        }?.let(::clean)
    } catch (_: Exception) { null }

    /** ntfy JSON-lines contain the room JSON as a string in `message`; ignore open/keepalive events. */
    fun decodeRelayLine(line: String): RoomEvent? = try {
        if (line.toByteArray(Charsets.UTF_8).size > 16_384) null
        else {
            val value = json.parseToJsonElement(line).jsonObject
            if (value["event"]?.jsonPrimitive?.content != "message") null
            else value["message"]?.jsonPrimitive?.content?.let(::decode)?.let { message ->
                val cursor = value["id"]?.jsonPrimitive?.content?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
                RoomEvent(message, cursor)
            }
        }
    } catch (_: Exception) { null }

    private fun valid(message: RoomMessage) = message.version == 1 && id.matches(message.videoId) &&
        short(message.title).isNotBlank() && message.timestamp > 0 && message.positionMs in 0..MAX_POSITION_MS

    private fun clean(message: RoomMessage) = message.copy(
        title = short(message.title), artist = short(message.artist), thumbnail = thumbnail(message.thumbnail),
        queue = message.queue.filter { it.videoId != message.videoId }.distinctBy { it.videoId }.take(MAX_QUEUE).map(::cleanTrack),
    )

    private fun cleanTrack(track: RoomTrack) = track.copy(
        title = short(track.title), artist = short(track.artist), thumbnail = thumbnail(track.thumbnail),
    )

    private fun short(text: String): String = text.filter { !it.isISOControl() }.take(96)
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }

    private fun thumbnail(url: String?): String? = url?.takeIf { it.length <= 512 }?.let {
        runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        }?.toString()
    }
}

/** Sender and receiver clocks are epoch milliseconds. Future clocks never cause negative seeks. */
fun synchronizedPosition(message: RoomMessage, nowMs: Long): Long {
    val elapsed = if (message.playing && nowMs > message.timestamp) (nowMs - message.timestamp).coerceAtMost(RoomCodec.MAX_POSITION_MS) else 0
    return (message.positionMs.coerceIn(0, RoomCodec.MAX_POSITION_MS) + elapsed).coerceAtMost(RoomCodec.MAX_POSITION_MS)
}
