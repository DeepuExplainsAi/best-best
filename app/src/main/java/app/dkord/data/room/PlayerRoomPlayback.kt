package app.dkord.data.room

import app.dkord.player.PlayerController
import app.dkord.player.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Uses existing public commands only; a guest never sends control commands to the host. */
class PlayerRoomPlayback(private val player: PlayerController) : RoomPlayback {
    override val states = player.state.map(::snapshot)
    override fun current() = snapshot(player.state.value)

    override suspend fun sync(message: RoomMessage, positionMs: Long) = withContext(Dispatchers.Main.immediate) {
        val tracks = listOf(RoomTrack(message.videoId, message.title, message.artist, message.thumbnail).toTrack()) +
            message.queue.map { it.toTrack() }
        val state = player.state.value
        val replace = !roomQueueMatches(snapshot(state), tracks)
        val position = if (!replace && state.durationMs > 0) positionMs.coerceAtMost((state.durationMs - 1).coerceAtLeast(0)) else positionMs
        if (replace) player.play(tracks, 0)
        if (replace || abs(state.positionMs - position) > 1_500) player.seekTo(position)
        if (message.playing) {
            if (!replace && !state.isPlaying) player.togglePlay()
        } else player.pause()
    }

    private fun snapshot(state: PlayerUiState) = RoomPlaybackState(
        state.current, state.queue, state.queueIndex, state.positionMs, state.isPlaying,
    )
}
