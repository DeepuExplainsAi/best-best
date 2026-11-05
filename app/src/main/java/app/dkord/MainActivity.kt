package app.dkord

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dkord.core.BrowseItem
import app.dkord.core.DebugLog
import app.dkord.data.auth.AuthStore
import app.dkord.data.stream.StreamResolver
import app.dkord.data.yt.MusicRepository
import app.dkord.data.yt.SearchFilter
import app.dkord.player.PlaybackEvents
import app.dkord.player.PlayerController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import app.dkord.ui.LoginScreen
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val repo: MusicRepository by inject()
    private val resolver: StreamResolver by inject()
    private val auth: AuthStore by inject()
    private val player: PlayerController by inject()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player.connect()
        // Android 13+: without this the media notification and lock-screen controls are hidden.
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { MaterialTheme { Part2Harness() } }
    }

    /** Temporary screen to exercise Part 2 on a device. Replaced by the real UI in Part 5. */
    @Composable
    private fun Part2Harness() {
        val scope = rememberCoroutineScope()
        var query by rememberSaveable { mutableStateOf("") }
        var results by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
        var status by rememberSaveable { mutableStateOf("") }
        var login by rememberSaveable { mutableStateOf(false) }
        val loggedIn by auth.loggedIn.collectAsState()
        val log by DebugLog.entries.collectAsState()
        val ps by player.state.collectAsState()
        val sleep by player.sleep.collectAsState()
        LaunchedEffect(Unit) { PlaybackEvents.failures.collect { status = "Can't play ${it.title}: ${it.problem} ${it.detail.orEmpty()}" } }

        if (login) { LoginScreen(onDone = { login = false; status = if (it) "Signed in" else "Login cancelled" }); return }

        Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search songs") })
            Button(onClick = {
                scope.launch {
                    status = "searching…"
                    runCatching { repo.search(query, SearchFilter.SONGS) }
                        .onSuccess { results = it.items; status = "${it.items.size} results. Tap Resolve for the first." }
                        .onFailure { status = "Search failed: ${it.message}" }
                }
            }) { Text("Search") }
            Button(onClick = {
                val first = results.firstOrNull() ?: return@Button
                scope.launch {
                    status = "resolving ${first.title}…"
                    runCatching { resolver.resolve(first.id) }
                        .onSuccess { status = "OK via ${it.source}: ${it.mimeType} ${it.bitrate / 1000}kbps" }
                        .onFailure { status = "ERROR: ${it.message}" }
                }
            }) { Text("Resolve first") }
            Button(onClick = {
                if (loggedIn) { auth.logout(); status = "Logged out" } else login = true
            }) { Text(if (loggedIn) "Log out" else "Sign in (use a secondary account)") }
            Button(onClick = {
                scope.launch {
                    runCatching { repo.likedSongs() }
                        .onSuccess { status = "Liked songs: ${it.tracks.size}" }
                        .onFailure { status = "Liked failed: ${it.message}" }
                }
            }, enabled = loggedIn) { Text("Load liked songs") }
            Row {
                Button(onClick = { if (results.isNotEmpty()) player.play(results.mapNotNull { it.track }, 0) }) { Text("Play all") }
                OutlinedButton(onClick = player::togglePlay) { Text(if (ps.isPlaying) "Pause" else "Play") }
                OutlinedButton(onClick = player::previous) { Text("Prev") }
                OutlinedButton(onClick = player::next) { Text("Next") }
            }
            Row {
                OutlinedButton(onClick = player::toggleShuffle) { Text(if (ps.shuffle) "Shuffle ON" else "Shuffle") }
                OutlinedButton(onClick = player::cycleRepeat) { Text("Repeat ${ps.repeatMode}") }
                OutlinedButton(onClick = { player.setSpeed(if (ps.speed >= 2f) 0.75f else ps.speed + 0.25f) }) { Text("${ps.speed}x") }
                OutlinedButton(onClick = { player.sleepIn(1) }) { Text("Sleep 1m") }
            }
            Text("Now: ${ps.current?.title ?: "-"}  ${ps.positionMs / 1000}/${ps.durationMs / 1000}s  sleep=$sleep")
            Text(status)
            LazyColumn(Modifier.weight(1f)) {
                items(results) { Text("${it.title} - ${it.subtitle}", Modifier.padding(vertical = 4.dp)) }
                items(log.takeLast(30)) { Text("${it.level}/${it.tag}: ${it.msg}", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
