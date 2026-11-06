@file:OptIn(UnstableApi::class)

package app.dkord.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.dkord.core.DebugLog
import app.dkord.core.Track
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val connected: Boolean = false,
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/**
 * UI-side handle to the playback service. Safe to create anywhere: it connects lazily, reconnects
 * if the service dies, and survives Activity recreation because it is an app-level singleton.
 */
class PlayerController(
    private val context: Context,
    private val sleepTimer: SleepTimer,
    private val music: app.dkord.data.yt.MusicRepository,
    private val settings: app.dkord.data.settings.SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
    val sleep: StateFlow<SleepState> get() = sleepTimer.state

    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    DebugLog.w("controller", "service disconnected")
                    this@PlayerController.controller = null
                    _state.value = PlayerUiState()
                }
            })
            .buildAsync()
        future.addListener({
            connecting = false
            runCatching { future.get() }
                .onSuccess { c ->
                    controller = c
                    c.addListener(listener)
                    refresh(c)
                    while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
                }
                .onFailure { DebugLog.e("controller", "connect failed", it) }
        }, MoreExecutors.directExecutor())
    }

    private inline fun withController(crossinline block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else { pending.addLast { block(it) }; connect() }
    }

    // ---- commands ----

    /** Replaces the queue and starts playing [tracks][startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int = 0) = withController { c ->
        if (tracks.isEmpty()) return@withController
        c.setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(0, tracks.lastIndex), 0)
        c.prepare(); c.play()
    }

    fun playTrack(track: Track) = play(listOf(track))

    fun playNext(track: Track) = withController { c ->
        val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
        c.addMediaItem(at, track.toMediaItem())
        if (c.mediaItemCount == 1) { c.prepare(); c.play() }
    }

    fun addToQueue(track: Track) = withController { c ->
        c.addMediaItem(track.toMediaItem())
        if (c.mediaItemCount == 1) { c.prepare(); c.play() }
    }

    fun removeFromQueue(index: Int) = withController { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }
    fun moveInQueue(from: Int, to: Int) = withController { if (from != to) it.moveMediaItem(from, to) }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); it.play() }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun pause() = withController { it.pause() }
    fun next() = withController { it.seekToNextMediaItem() }
    /** Like every music app: restart the track if it is past 3s, otherwise go to the previous one. */
    fun previous() = withController { if (it.currentPosition > 3_000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed.coerceIn(0.25f, 3f)) }
    fun stop() = withController { it.stop(); it.clearMediaItems() }

    // Sleep timer runs in the app process on the main thread, next to the service's player.
    fun sleepIn(minutes: Int) = sleepTimer.start(minutes)
    fun sleepAtEndOfTrack() = sleepTimer.startEndOfTrack()
    fun cancelSleep() = sleepTimer.cancel()

    // ---- state ----

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh(player); maybeAutoplay(player) }
    }

    private var fetchingFor: String? = null

    /** Autoplay: when the last (or second to last) track is playing or the queue ended, append related songs. */
    private fun maybeAutoplay(p: Player) {
        if (!settings.settings.value.autoplay || p.mediaItemCount == 0) return
        val ended = p.playbackState == Player.STATE_ENDED
        val nearEnd = p.currentMediaItemIndex >= p.mediaItemCount - 2
        if (!ended && !nearEnd) return
        if (p.repeatMode != Player.REPEAT_MODE_OFF) return
        val last = p.getMediaItemAt(p.mediaItemCount - 1).toTrack()
        if (fetchingFor == last.id) return
        fetchingFor = last.id
        scope.launch {
            val more = runCatching { music.related(last) }.getOrDefault(emptyList())
            val c = controller ?: return@launch
            val have = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
            val fresh = more.filter { it.id !in have }.take(15)
            if (fresh.isEmpty()) { fetchingFor = null; return@launch }
            val firstNew = c.mediaItemCount
            c.addMediaItems(fresh.map { it.toMediaItem() })
            if (c.playbackState == Player.STATE_ENDED) { c.seekToDefaultPosition(firstNew); c.prepare(); c.play() }
        }
    }

    private fun refresh(p: Player) {
        val queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).toTrack() }
        _state.value = PlayerUiState(
            connected = true,
            current = p.currentMediaItem?.toTrack(),
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it > 0 } ?: (p.currentMediaItem?.mediaMetadata?.durationMs ?: 0),
            bufferedMs = p.bufferedPosition,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            speed = p.playbackParameters.speed,
            queue = queue,
            queueIndex = p.currentMediaItemIndex,
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem(),
        )
    }

    init {
        // The seek bar needs a steady position; events alone only fire on state changes.
        scope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                if (c.isPlaying) _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0), bufferedMs = c.bufferedPosition) }
            }
        }
    }

}
