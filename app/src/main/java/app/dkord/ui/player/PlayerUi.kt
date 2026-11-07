package app.dkord.ui.player

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Share
import app.dkord.ui.common.shareTrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.dkord.R
import app.dkord.core.Track
import app.dkord.data.lyrics.LrcParser
import app.dkord.data.lyrics.Lyrics
import app.dkord.data.lyrics.LyricsRepository
import app.dkord.player.PlayerController
import app.dkord.player.PlayerUiState
import app.dkord.player.SleepState
import app.dkord.ui.common.Actions
import app.dkord.ui.common.Cover
import app.dkord.ui.common.formatTime
import app.dkord.ui.common.hiRes
import coil3.compose.AsyncImage
import org.koin.compose.koinInject

/** Collapsed player bar above the bottom navigation. Tap or swipe up opens the full player; swipe sideways skips. */
@Composable
fun MiniPlayer(state: PlayerUiState, controller: PlayerController, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val track = state.current ?: return
    var drag by remember { mutableFloatStateOf(0f) }
    Surface(
        modifier.fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (drag < -120) controller.next() else if (drag > 120) controller.previous(); drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, d -> drag += d }
            }
            .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -20) onOpen() } }
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(track.thumbnail, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(controller::previous, enabled = state.hasPrevious) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous)) }
                IconButton(controller::togglePlay) {
                    Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (state.isPlaying) R.string.pause else R.string.play))
                }
                IconButton(controller::next, enabled = state.hasNext) { Icon(Icons.Default.SkipNext, stringResource(R.string.next)) }
            }
            if (state.isBuffering) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            else LinearProgressIndicator({ if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f }, Modifier.fillMaxWidth().height(2.dp), trackColor = Color.Transparent)
        }
    }
}

private enum class Panel { ART, LYRICS, QUEUE }

/** Full-screen player. Swipe down closes; swipe sideways on the art changes track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(state: PlayerUiState, onClose: () -> Unit, onAddToPlaylist: (Track) -> Unit, onPlayVideo: (Track) -> Unit) {
    val actions: Actions = koinInject()
    val settingsRepo: app.dkord.data.settings.SettingsRepository = koinInject()
    val controller = actions.player
    val shareContext = LocalContext.current
    val sleep by controller.sleep.collectAsState()
    val likedIds by remember { actions.library.likedIds() }.collectAsState(emptySet())
    val track = state.current
    var panel by rememberSaveable { mutableStateOf(Panel.ART) }
    var sheet by rememberSaveable { mutableStateOf("") } // "", "speed", "sleep"
    var dragY by remember { mutableFloatStateOf(0f) }

    if (track == null) { LaunchedEffect(Unit) { onClose() }; return }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { if (dragY > 200) onClose(); dragY = 0f }, onDragCancel = { dragY = 0f }) { _, d -> dragY += d }
            },
    ) {
        // Blurred artwork background (blur is a no-op below Android 12, the dark scrim keeps it readable).
        AsyncImage(hiRes(track.thumbnail, 200), null, Modifier.fillMaxSize().blur(60.dp).alpha(0.55f), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), MaterialTheme.colorScheme.background.copy(alpha = 0.92f)))))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.close)) }
                Spacer(Modifier.weight(1f))
                IconButton({ shareTrack(shareContext, track) }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                IconButton({ panel = if (panel == Panel.LYRICS) Panel.ART else Panel.LYRICS }) {
                    Icon(Icons.Default.Lyrics, stringResource(R.string.lyrics), tint = if (panel == Panel.LYRICS) MaterialTheme.colorScheme.primary else LocalContentColorOrDefault())
                }
                IconButton({ panel = if (panel == Panel.QUEUE) Panel.ART else Panel.QUEUE }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue), tint = if (panel == Panel.QUEUE) MaterialTheme.colorScheme.primary else LocalContentColorOrDefault())
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(panel, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "panel") { p ->
                    when (p) {
                        Panel.ART -> ArtPanel(track, controller)
                        Panel.LYRICS -> LyricsPanel(track, state.positionMs, controller)
                        Panel.QUEUE -> QueuePanel(state, controller)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val ctx = LocalContext.current
                val savedMsg = stringResource(R.string.download_started)
                IconButton({ actions.download(listOf(track)); Toast.makeText(ctx, savedMsg, Toast.LENGTH_SHORT).show() }) {
                    Icon(Icons.Default.Download, stringResource(R.string.download))
                }
                val liked = track.id in likedIds
                IconButton({ actions.like(track, !liked) }) {
                    Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.like), tint = if (liked) MaterialTheme.colorScheme.primary else LocalContentColorOrDefault())
                }
            }

            SeekBar(state, controller)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(controller::toggleShuffle) { Icon(Icons.Default.Shuffle, stringResource(R.string.shuffle), tint = if (state.shuffle) MaterialTheme.colorScheme.primary else LocalContentColorOrDefault()) }
                IconButton(controller::previous, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp)) }
                FilledIconButton(controller::togglePlay, Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (state.isPlaying) R.string.pause else R.string.play), Modifier.size(40.dp))
                }
                IconButton(controller::next, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, stringResource(R.string.next), Modifier.size(36.dp)) }
                val ctxT = LocalContext.current
                val repeatNames = listOf(stringResource(R.string.repeat_off), stringResource(R.string.repeat_all), stringResource(R.string.repeat_one))
                IconButton({
                    controller.cycleRepeat()
                    val next = when (state.repeatMode) { Player.REPEAT_MODE_OFF -> 1; Player.REPEAT_MODE_ALL -> 2; else -> 0 }
                    Toast.makeText(ctxT, repeatNames[next], Toast.LENGTH_SHORT).show()
                }) {
                    Icon(if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, stringResource(R.string.repeat),
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColorOrDefault())
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton({ sheet = "speed" }) { Icon(Icons.Default.Speed, null); Text("  ${state.speed}x") }
                TextButton({ sheet = "sleep" }) {
                    Icon(Icons.Default.Bedtime, null, tint = if (sleep != SleepState.Off) MaterialTheme.colorScheme.primary else LocalContentColorOrDefault())
                    Text("  " + when (val s = sleep) {
                        SleepState.Off -> stringResource(R.string.sleep_timer)
                        SleepState.EndOfTrack -> stringResource(R.string.end_of_track)
                        is SleepState.Countdown -> formatTime(s.remainingMs)
                    })
                }
                val st by settingsRepo.settings.collectAsState()
                TextButton({ settingsRepo.setAutoplay(!st.autoplay) }) {
                    Icon(Icons.Default.AutoAwesome, null, tint = if (st.autoplay) MaterialTheme.colorScheme.primary else LocalContentColorOrDefault())
                    Text("  " + stringResource(R.string.autoplay), color = if (st.autoplay) MaterialTheme.colorScheme.primary else LocalContentColorOrDefault())
                }
                TextButton({ onAddToPlaylist(track) }) { Text(stringResource(R.string.add_to_playlist)) }
                TextButton({ onPlayVideo(track) }) { Text(stringResource(R.string.play_video)) }
            }
        }
    }

    if (sheet == "speed") OptionSheet(stringResource(R.string.speed), listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { "${it}x" to { controller.setSpeed(it) } }) { sheet = "" }
    if (sheet == "sleep") OptionSheet(
        stringResource(R.string.sleep_timer),
        listOf(5, 15, 30, 45, 60).map { m -> stringResource(R.string.minutes_n, m) to { controller.sleepIn(m) } } +
            listOf(stringResource(R.string.end_of_track) to { controller.sleepAtEndOfTrack() }) +
            (if (sleep != SleepState.Off) listOf(stringResource(R.string.turn_off) to { controller.cancelSleep() }) else emptyList()),
    ) { sheet = "" }
}

@Composable private fun LocalContentColorOrDefault() = androidx.compose.material3.LocalContentColor.current

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionSheet(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        options.forEach { (label, act) ->
            Text(label, Modifier.fillMaxWidth().clickable { act(); onDismiss() }.padding(horizontal = 24.dp, vertical = 14.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ArtPanel(track: Track, controller: PlayerController) {
    var drag by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (drag < -120) controller.next() else if (drag > 120) controller.previous(); drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, d -> drag += d }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(track.id, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "art") { _ ->
            Cover(track.thumbnail, 320.dp, radius = 12.dp)
        }
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, controller: PlayerController) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    val dur = state.durationMs.coerceAtLeast(1)
    val shown = if (scrubbing) scrub else state.positionMs.toFloat().coerceIn(0f, dur.toFloat())
    Column {
        Slider(
            value = shown, valueRange = 0f..dur.toFloat(),
            onValueChange = { scrubbing = true; scrub = it },
            onValueChangeFinished = { controller.seekTo(scrub.toLong()); scrubbing = false },
            enabled = state.durationMs > 0,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shown.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LyricsPanel(track: Track, positionMs: Long, controller: PlayerController) {
    val repo: LyricsRepository = koinInject()
    val result by produceState<Result<Lyrics?>?>(null, track.id) {
        value = null
        value = runCatching { repo.load(track) }
    }
    val r = result
    when {
        r == null -> androidx.compose.material3.CircularProgressIndicator()
        r.isFailure -> Text(stringResource(R.string.err_network), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.getOrNull() == null -> Text(stringResource(R.string.no_lyrics), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            val lyrics = r.getOrNull()!!
            if (lyrics.synced.isEmpty()) {
                LazyColumn(Modifier.fillMaxSize()) { item { Text(lyrics.plain.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp)) } }
            } else {
                val idx = LrcParser.indexAt(lyrics.synced, positionMs)
                val list = rememberLazyListState()
                LaunchedEffect(idx) { if (idx >= 0) list.animateScrollToItem((idx - 2).coerceAtLeast(0)) }
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    itemsIndexed(lyrics.synced) { i, line ->
                        Text(
                            line.text.ifBlank { "♪" },
                            style = MaterialTheme.typography.titleLarge,
                            color = if (i == idx) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth().clickable { controller.seekTo(line.timeMs) }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePanel(state: PlayerUiState, controller: PlayerController) {
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (state.queueIndex > 0) list.scrollToItem(state.queueIndex) }
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        itemsIndexed(state.queue, key = { i, t -> "$i${t.id}" }) { i, t ->
            Row(Modifier.fillMaxWidth().clickable { controller.skipTo(i) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(t.thumbnail, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (i == state.queueIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ controller.removeFromQueue(i) }, enabled = i != state.queueIndex) { Icon(Icons.Default.Delete, stringResource(R.string.remove)) }
            }
        }
    }
}
