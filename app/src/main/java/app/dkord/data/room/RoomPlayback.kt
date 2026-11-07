package app.dkord.data.room

import app.dkord.core.Track
import kotlinx.coroutines.flow.Flow

/** Only the new room feature uses this adapter; normal player commands/autoplay are unchanged. */
data class RoomPlaybackState(
    val current: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
)

interface RoomPlayback {
    val states: Flow<RoomPlaybackState>
    fun current(): RoomPlaybackState
    suspend fun sync(message: RoomMessage, positionMs: Long)
}

fun roomMessage(state: RoomPlaybackState, timestamp: Long): RoomMessage? = state.current?.let { track ->
    RoomMessage(track.id, track.title, track.artist, track.thumbnail, state.positionMs.coerceIn(0, RoomCodec.MAX_POSITION_MS),
        state.playing, timestamp, state.queue.drop((state.queueIndex + 1).coerceAtLeast(0))
            .filter { it.id != track.id }.take(RoomCodec.MAX_QUEUE).map(RoomTrack::fromTrack))
}
