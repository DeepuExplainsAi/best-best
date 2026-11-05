@file:OptIn(UnstableApi::class)

package app.dkord.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.dkord.MainActivity
import app.dkord.R
import app.dkord.core.DebugLog
import app.dkord.data.download.DownloadManager
import app.dkord.data.library.LibraryRepository
import app.dkord.data.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.media3.datasource.DefaultDataSource
import app.dkord.data.stream.StreamModule
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.koin.android.ext.android.inject

/**
 * Owns the ExoPlayer and the MediaSession. Media3 turns the session into the foreground
 * notification (mediaPlayback type), lock-screen controls and Bluetooth/headset button handling.
 */
class PlaybackService : MediaSessionService() {

    private val streams: StreamModule by inject()
    private val audioCache: AudioCache by inject()
    private val config: StreamConfig by inject()
    private val queueStore: QueueStore by inject()
    private val sleepTimer: SleepTimer by inject()
    private val downloads: DownloadManager by inject()
    private val library: LibraryRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())

    private var urlRetryFor: String? = null
    private var consecutiveSkips = 0
    private var recorded: String? = null

    override fun onCreate() {
        super.onCreate()
        player = buildPlayer()
        player.addListener(PlayerListener())
        restoreQueue()

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.channel_playback)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        sleepTimer.attach(object : SleepTarget {
            override fun setVolume(volume: Float) { player.volume = volume }
            override fun pause() = player.pause()
            override fun setPauseAtEndOfTrack(enabled: Boolean) = player.setPauseAtEndOfMediaItems(enabled)
        })
        DebugLog.d("player", "service created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops an idle player; a playing one keeps going in the background. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueueNow()
        if (!player.playWhenReady || player.mediaItemCount == 0) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        saveQueueNow()
        sleepTimer.attach(null)
        scope.cancel()
        session?.release(); session = null
        player.release()
        DebugLog.d("player", "service destroyed")
        super.onDestroy()
    }

    private fun buildPlayer(): ExoPlayer {
        // Chain (outermost first): resolve dkord:// (downloaded file wins) -> local file | cache -> add open Range -> OkHttp.
        val http = OkHttpDataSource.Factory(Http.client)
        val ranged = ResolvingDataSource.Factory(http, OpenRangeResolver())
        val cached = CacheDataSource.Factory()
            .setCache(audioCache.cache)
            .setUpstreamDataSourceFactory(ranged)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val routed = SchemeRoutingDataSource.Factory(DefaultDataSource.Factory(this), cached)
        val resolving = ResolvingDataSource.Factory(
            routed,
            StreamUriResolver(streams, { config.quality }, { id -> runBlocking { downloads.localUri(id) } }),
        )

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving).setLoadErrorHandlingPolicy(StreamErrorPolicy()))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true, // pauses on focus loss / calls and resumes after transient loss
            )
            .setHandleAudioBecomingNoisy(true) // headphones unplugged / Bluetooth lost
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    // ---- queue persistence (process death, "play" from a Bluetooth button after the app was killed) ----

    private fun restoreQueue() {
        val q = queueStore.load() ?: return
        // Not prepared: no network until the user presses play.
        player.setMediaItems(q.tracks.map { it.toMediaItem() }, q.index, q.positionMs)
        DebugLog.d("player", "restored queue of ${q.tracks.size}")
    }

    private val saveRunnable = Runnable { saveQueueNow() }
    private fun saveQueueSoon() { handler.removeCallbacks(saveRunnable); handler.postDelayed(saveRunnable, 1_000) }

    private fun saveQueueNow() {
        handler.removeCallbacks(saveRunnable)
        val n = player.mediaItemCount
        if (n == 0) { queueStore.clear(); return }
        val tracks = (0 until n).map { player.getMediaItemAt(it).toTrack() }
        queueStore.save(SavedQueue.of(tracks, player.currentMediaItemIndex, player.currentPosition))
    }

    // ---- session callbacks ----

    private inner class SessionCallback : MediaSession.Callback {
        /** Controllers such as Assistant or Android Auto send items with only a mediaId. */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { if (it.localConfiguration == null) it.buildUpon().setUri(trackUri(it.mediaId)).build() else it },
        )

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val q = queueStore.load() ?: return Futures.immediateFailedFuture(UnsupportedOperationException("no saved queue"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(q.tracks.map { it.toMediaItem() }, q.index, q.positionMs))
        }
    }

    // ---- player events ----

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            urlRetryFor = null
            saveQueueSoon()
            recorded = null
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = saveQueueSoon()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                consecutiveSkips = 0
                // Only a track that really started counts as played (not a restored or failing queue entry).
                player.currentMediaItem?.takeIf { it.mediaId != recorded }?.let { item ->
                    recorded = item.mediaId
                    scope.launch { runCatching { library.recordPlayed(item.toTrack()) } }
                }
            } else saveQueueSoon()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) sleepTimer.onTrackEnded()
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = player.currentMediaItem ?: return
            val id = item.mediaId
            DebugLog.e("player", "error ${error.errorCodeName} on $id", error)

            // Expired/invalid URL: drop the cached one, re-resolve and continue from the same position.
            if (PlaybackErrors.isUrlExpired(error) && urlRetryFor != id) {
                urlRetryFor = id
                streams.invalidate(id)
                DebugLog.w("player", "HTTP ${PlaybackErrors.httpStatus(error)} for $id: re-resolving")
                player.prepare()
                return
            }

            val streamError = PlaybackErrors.streamError(error)
            val problem = when {
                streamError != null -> PlaybackErrors.problemOf(streamError)
                PlaybackErrors.isNetwork(error) -> PlaybackProblem.NETWORK
                else -> PlaybackProblem.UNAVAILABLE
            }
            PlaybackEvents.emit(PlaybackFailure(id, item.mediaMetadata.title?.toString(), problem, streamError?.message ?: error.errorCodeName))

            // A dead track should not stall the queue, but a dead network will fail every next track too.
            if (problem != PlaybackProblem.NETWORK && consecutiveSkips < MAX_AUTO_SKIPS && player.hasNextMediaItem()) {
                consecutiveSkips++
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "dkord_playback"
        const val MAX_AUTO_SKIPS = 3
    }
}
