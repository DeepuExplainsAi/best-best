@file:OptIn(UnstableApi::class)

package app.dkord.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.dkord.R
import app.dkord.data.net.Http
import app.dkord.data.stream.NoVideoException
import app.dkord.data.stream.ResolvedVideo
import app.dkord.data.stream.StreamError
import app.dkord.data.stream.VideoResolver
import app.dkord.player.OpenRangeResolver
import app.dkord.ui.common.Actions
import org.koin.compose.koinInject

/** What the screen shows. Pure data so the mapping from errors is unit-testable. */
sealed interface VideoUi {
    data object Loading : VideoUi
    data class Ready(val video: ResolvedVideo) : VideoUi
    data class Failed(val reason: VideoFailure, val detail: String?) : VideoUi
}

enum class VideoFailure { NO_VIDEO, RESTRICTED, NETWORK, PLAYBACK }

object VideoErrors {
    fun of(t: Throwable): VideoUi.Failed = when (t) {
        is NoVideoException -> VideoUi.Failed(VideoFailure.NO_VIDEO, null)
        is StreamError.Network -> VideoUi.Failed(VideoFailure.NETWORK, null)
        is StreamError -> VideoUi.Failed(VideoFailure.RESTRICTED, t.message)
        else -> VideoUi.Failed(VideoFailure.NETWORK, t.message)
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Explicit, separate video playback. Own ExoPlayer; the background audio service and downloads are untouched. */
@Composable
fun VideoScreen(videoId: String, title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val actions: Actions = koinInject()
    var ui by remember { mutableStateOf<VideoUi>(VideoUi.Loading) }
    var attempt by rememberSaveable { mutableStateOf(0) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    // Video and background audio must not play together.
    LaunchedEffect(Unit) { actions.player.pause() }
    LaunchedEffect(videoId, attempt) {
        ui = VideoUi.Loading
        ui = runCatching { VideoResolver().resolve(videoId) }.fold({ VideoUi.Ready(it) }, { VideoErrors.of(it) })
    }

    val activity = ctx.activity()
    DisposableEffect(fullscreen) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!fullscreen) Box(Modifier.fillMaxWidth().statusBarsPadding()) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
            Text(title, Modifier.align(Alignment.Center).padding(horizontal = 56.dp), color = Color.White, maxLines = 1)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val s = ui) {
                VideoUi.Loading -> CircularProgressIndicator()
                is VideoUi.Failed -> VideoError(s) { attempt++ }
                is VideoUi.Ready -> {
                    VideoPlayer(s.video, onError = { ui = VideoUi.Failed(VideoFailure.PLAYBACK, it) })
                    IconButton({ fullscreen = !fullscreen }, Modifier.align(Alignment.TopEnd)) {
                        Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, stringResource(R.string.fullscreen), tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoError(f: VideoUi.Failed, onRetry: () -> Unit) {
    val msg = when (f.reason) {
        VideoFailure.NO_VIDEO -> stringResource(R.string.video_none)
        VideoFailure.RESTRICTED -> stringResource(R.string.video_restricted)
        VideoFailure.NETWORK -> stringResource(R.string.err_network)
        VideoFailure.PLAYBACK -> stringResource(R.string.video_failed)
    }
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(msg, color = Color.White, textAlign = TextAlign.Center)
        f.detail?.let { Text(it, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
        if (f.reason != VideoFailure.NO_VIDEO) TextButton(onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun VideoPlayer(video: ResolvedVideo, onError: (String?) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(video.videoId) {
        val http = ResolvingDataSource.Factory(OkHttpDataSource.Factory(Http.client), OpenRangeResolver())
        val subs = video.subtitles.map {
            MediaItem.SubtitleConfiguration.Builder(it.url.toUri())
                .setMimeType(it.mimeType).setLanguage(it.language).setLabel(it.label).build()
        }
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(DefaultLoadControl())
            .build().apply {
                setMediaItem(MediaItem.Builder().setUri(video.video.url).setSubtitleConfigurations(subs).build())
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error.errorCodeName)
        }
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(l); lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); player.removeListener(l); player.release() }
    }
    AndroidView(
        factory = { c -> PlayerView(c).apply { this.player = player; setShowSubtitleButton(video.subtitles.isNotEmpty()); setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}
