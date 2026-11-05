package app.dkord.player

import app.dkord.core.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Snapshot used to restore the queue after process death or a Bluetooth "play" press. */
@Serializable
data class SavedQueue(val tracks: List<Track>, val index: Int, val positionMs: Long) {
    fun encode(): String = json.encodeToString(this)

    companion object {
        const val MAX_TRACKS = 500
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(s: String?): SavedQueue? = s?.let {
            runCatching { json.decodeFromString<SavedQueue>(it) }.getOrNull()
                ?.takeIf { q -> q.tracks.isNotEmpty() }
                ?.let { q -> q.copy(index = q.index.coerceIn(0, q.tracks.lastIndex), positionMs = q.positionMs.coerceAtLeast(0)) }
        }

        /** Keeps a window around the current track when the queue is huge. */
        fun of(tracks: List<Track>, index: Int, positionMs: Long): SavedQueue {
            if (tracks.size <= MAX_TRACKS) return SavedQueue(tracks, index, positionMs)
            val from = (index - MAX_TRACKS / 4).coerceAtLeast(0).coerceAtMost(tracks.size - MAX_TRACKS)
            return SavedQueue(tracks.subList(from, from + MAX_TRACKS), index - from, positionMs)
        }
    }
}
