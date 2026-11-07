package app.dkord.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.dkord.R
import app.dkord.core.ItemKind
import app.dkord.core.Track
import app.dkord.player.PlaybackEvents
import app.dkord.player.PlaybackProblem
import app.dkord.ui.common.Actions
import app.dkord.ui.common.HomeUpdateNotice
import app.dkord.ui.common.ObserveAppUpdates
import app.dkord.ui.common.DkordMotion
import app.dkord.ui.common.PlaylistPicker
import app.dkord.ui.common.TrackMenu
import app.dkord.ui.player.FullPlayer
import app.dkord.ui.player.MiniPlayer
import app.dkord.ui.screens.DebugLogScreen
import app.dkord.ui.screens.DetailScreen
import app.dkord.ui.screens.DownloadsScreen
import app.dkord.ui.player.VideoScreen
import app.dkord.ui.screens.HomeScreen
import app.dkord.ui.screens.LibraryScreen
import app.dkord.ui.screens.LikedScreen
import app.dkord.ui.screens.LocalPlaylistScreen
import app.dkord.ui.screens.RecentScreen
import app.dkord.ui.screens.SearchScreen
import app.dkord.ui.screens.ListenTogetherScreen
import app.dkord.ui.screens.SettingsScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val actions: Actions = koinInject()
    val player = actions.player
    val state by player.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val savedTabs = rememberSaveableStateHolder()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var stack by rememberSaveable(stateSaver = BackStackSaver) { mutableStateOf(emptyList<Dest>()) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var pickTracks by remember { mutableStateOf<List<Track>?>(null) }

    fun say(text: String) { scope.launch { snack.showSnackbar(text) } }
    fun push(d: Dest) { if (stack.lastOrNull() != d) stack = stack + d }
    fun pop() { stack = stack.dropLast(1) }
    fun play(list: List<Track>, i: Int) = player.play(list, i)


    // Playback failures -> localized message (the service cannot reach the UI directly).
    val msgs = mapOf(
        PlaybackProblem.BOT_CHECK to stringResource(R.string.err_bot_check), PlaybackProblem.AGE_RESTRICTED to stringResource(R.string.err_age_restricted),
        PlaybackProblem.KIDS to stringResource(R.string.err_kids), PlaybackProblem.GEO to stringResource(R.string.err_geo),
        PlaybackProblem.PRIVATE to stringResource(R.string.err_private), PlaybackProblem.UNAVAILABLE to stringResource(R.string.err_unavailable),
        PlaybackProblem.NETWORK to stringResource(R.string.err_network), PlaybackProblem.NO_AUDIO to stringResource(R.string.err_no_audio),
    )
    LaunchedEffect(Unit) {
        PlaybackEvents.failures.collect { f -> snack.showSnackbar(listOfNotNull(f.title, msgs[f.problem]).joinToString(": ")) }
    }

    // Back order: full player -> pushed screen -> non-home tab -> system. Predictive back animates via the system callback.
    BackHandler(enabled = playerOpen) { playerOpen = false }
    BackHandler(enabled = !playerOpen && stack.isNotEmpty()) { pop() }
    BackHandler(enabled = !playerOpen && stack.isEmpty() && tab != Tab.HOME) { tab = Tab.HOME }

    val top = stack.lastOrNull()
    fun onItem(i: app.dkord.core.BrowseItem) {
        if (i.kind == ItemKind.SONG) i.track?.let { play(listOf(it), 0) } else push(i.toDest())
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                if (top == null || top is Dest.Detail || top is Dest.Playlist || top is Dest.Liked || top is Dest.Recent) Column {
                    MiniPlayer(state, player, onOpen = { playerOpen = true })
                    if (top == null) NavigationBar {
                        tabs.forEach { (t, icon, label) ->
                            NavigationBarItem(tab == t, { tab = t }, { Icon(icon, null) }, label = { Text(stringResource(label)) })
                        }
                    }
                }
            },
        ) { pad ->
            AnimatedContent(
                targetState = stack,
                contentKey = { it.size to it.lastOrNull() },
                modifier = Modifier.fillMaxSize().padding(pad),
                transitionSpec = {
                    when (navigationDirection(initialState.size, targetState.size)) {
                        NavigationDirection.FORWARD ->
                            (slideInHorizontally(tween(DkordMotion.SCREEN, easing = DkordMotion.easing)) { it / 10 } + fadeIn(tween(DkordMotion.CONTENT))) togetherWith fadeOut(tween(DkordMotion.QUICK))
                        NavigationDirection.BACK ->
                            fadeIn(tween(DkordMotion.CONTENT)) togetherWith (slideOutHorizontally(tween(DkordMotion.SCREEN, easing = DkordMotion.easing)) { it / 10 } + fadeOut(tween(DkordMotion.CONTENT)))
                        NavigationDirection.FADE -> fadeIn(tween(DkordMotion.CONTENT)) togetherWith fadeOut(tween(DkordMotion.QUICK))
                    }
                },
                label = "dest",
            ) { entries ->
                when (val dest = entries.lastOrNull()) {
                    null -> TabContent(tab, savedTabs, ::say, { push(Dest.Settings) }, ::push, ::onItem, ::play, { menuTrack = it })
                    is Dest.Detail -> DetailScreen(dest.toItem(), ::pop, ::onItem, ::play, { menuTrack = it }, { pickTracks = it })
                    is Dest.Playlist -> LocalPlaylistScreen(dest.id, ::pop, ::play) { menuTrack = it }
                    Dest.Liked -> LikedScreen(::pop, ::play) { menuTrack = it }
                    Dest.Recent -> RecentScreen(::pop, ::play) { menuTrack = it }
                    Dest.Settings -> SettingsScreen(::pop, { push(Dest.Logs) }, ::say)
                    Dest.ListenTogether -> ListenTogetherScreen(onBack = ::pop, onOpenPlayer = { pop(); playerOpen = true }, onMessage = ::say)
                    Dest.Logs -> DebugLogScreen(::pop, ::say)
                    is Dest.Video -> VideoScreen(dest.id, dest.title, ::pop)
                }
            }
        }

        // Full player slides over everything, including bottom bar.
        AnimatedVisibility(
            playerOpen && state.current != null,
            enter = slideInVertically(tween(DkordMotion.SHEET, easing = DkordMotion.easing)) { it } + fadeIn(tween(DkordMotion.CONTENT)),
            exit = slideOutVertically(tween(DkordMotion.SCREEN, easing = DkordMotion.easing)) { it } + fadeOut(tween(DkordMotion.CONTENT)),
        ) { FullPlayer(state, onClose = { playerOpen = false }, onAddToPlaylist = { pickTracks = listOf(it) }, onPlayVideo = { playerOpen = false; push(Dest.Video(it.id, it.title)) }, onListenTogether = { playerOpen = false; push(Dest.ListenTogether) }) }
    }

    ObserveAppUpdates()

    menuTrack?.let { t -> TrackMenu(t, onDismiss = { menuTrack = null }, onAddToPlaylist = { pickTracks = listOf(it) }) }
    pickTracks?.let { PlaylistPicker(it, onDismiss = { pickTracks = null }, onResult = ::say) }
}

private data class TabSpec(val tab: Tab, val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: Int)
private val tabs = listOf(
    TabSpec(Tab.HOME, Icons.Default.Home, R.string.tab_home),
    TabSpec(Tab.SEARCH, Icons.Default.Search, R.string.tab_search),
    TabSpec(Tab.LIBRARY, Icons.Default.LibraryMusic, R.string.tab_library),
    TabSpec(Tab.DOWNLOADS, Icons.Default.Download, R.string.tab_downloads),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabContent(
    tab: Tab,
    savedTabs: SaveableStateHolder,
    say: (String) -> Unit,
    openSettings: () -> Unit,
    push: (Dest) -> Unit,
    onItem: (app.dkord.core.BrowseItem) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onMenu: (Track) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.logo), null, Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape))
                            Text(stringResource(R.string.app_title), Modifier.padding(start = 10.dp))
                        }
                    },
                    actions = { IconButton(openSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } },
                )
                HomeUpdateNotice(visible = tab == Tab.HOME, onOpenSettings = openSettings)
            }
        },
    ) { pad ->
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.fillMaxSize().padding(pad),
            transitionSpec = { fadeIn(tween(DkordMotion.CONTENT)) togetherWith fadeOut(tween(DkordMotion.QUICK)) },
            label = "tab",
        ) { selected ->
            // The holder lives above destination transitions, preserving tab scroll/filter state.
            savedTabs.SaveableStateProvider(selected.name) {
                val m = Modifier.fillMaxSize()
                when (selected) {
                    Tab.HOME -> HomeScreen(onItem, onPlay, m)
                    Tab.SEARCH -> SearchScreen(onItem, onPlay, onMenu, m)
                    Tab.LIBRARY -> LibraryScreen({ push(Dest.Playlist(it)) }, { push(Dest.Liked) }, { push(Dest.Recent) }, onItem, say, m)
                    Tab.DOWNLOADS -> DownloadsScreen(onPlay, onMenu, m)
                }
            }
        }
    }
}
