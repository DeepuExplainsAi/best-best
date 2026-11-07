package app.dkord.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dkord.R
import app.dkord.data.room.RoomController
import app.dkord.data.room.RoomError
import app.dkord.data.room.RoomRole
import app.dkord.ui.common.Cover
import app.dkord.ui.common.DkordMotion
import app.dkord.ui.common.sharePlainText
import org.koin.compose.koinInject
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherScreen(onBack: () -> Unit, onOpenPlayer: () -> Unit, onMessage: (String) -> Unit) {
    val rooms: RoomController = koinInject()
    val state by rooms.state.collectAsState()
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.listen_together), maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.role == null) {
                RoomInstructions()
                Button(rooms::create, Modifier.fillMaxWidth()) { Text(stringResource(R.string.room_create)) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.room_join), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(input, { input = it.uppercase(Locale.ROOT).take(32); rooms.clearCodeError() },
                            label = { Text(stringResource(R.string.room_code)) },
                            supportingText = { Text(stringResource(if (state.error == RoomError.INVALID_CODE) R.string.room_invalid_code else R.string.room_join_hint)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) rooms.join(input) }),
                            isError = state.error == RoomError.INVALID_CODE)
                        OutlinedButton({ rooms.join(input) }, Modifier.fillMaxWidth(), enabled = input.isNotBlank()) {
                            Text(stringResource(R.string.room_join))
                        }
                    }
                }
            } else {
                val isHost = state.role == RoomRole.HOST
                val code = state.code.orEmpty()
                val invitation = stringResource(R.string.room_invitation, code)
                val status = when {
                    state.connecting && state.error != null -> R.string.room_reconnecting
                    state.connecting -> R.string.room_connecting
                    state.error != null -> R.string.room_attention
                    isHost -> R.string.room_ready
                    state.syncing -> R.string.room_syncing
                    state.host == null -> R.string.room_waiting
                    state.followAutomatically && state.hasSynced -> R.string.room_following
                    else -> R.string.room_ready_sync
                }
                Card(Modifier.fillMaxWidth().animateContentSize(tween(DkordMotion.CONTENT))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(if (isHost) R.string.room_host else R.string.room_guest), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(status), style = MaterialTheme.typography.labelMedium,
                                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp).weight(1f))
                        }
                        SelectionContainer {
                            Text(code, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp))
                        }
                        Row(Modifier.fillMaxWidth()) {
                            TextButton({
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.room_code), code))
                                onMessage(context.getString(R.string.room_code_copied))
                            }, Modifier.weight(1f)) {
                                Icon(Icons.Default.ContentCopy, null)
                                Text(stringResource(R.string.room_copy_code), Modifier.padding(start = 8.dp))
                            }
                            TextButton({ sharePlainText(context, invitation) }, Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null)
                                Text(stringResource(R.string.room_share_code), Modifier.padding(start = 8.dp))
                            }
                        }
                        if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                val host = state.host
                if (host != null) Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(host.thumbnail, 56.dp)
                        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.room_current_song), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(host.title, style = MaterialTheme.typography.titleMedium)
                            if (host.artist.isNotBlank()) Text(host.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (isHost) {
                    Text(stringResource(R.string.room_host_controls), style = MaterialTheme.typography.bodyMedium)
                    Button(onOpenPlayer, Modifier.fillMaxWidth()) { Text(stringResource(R.string.room_open_player)) }
                } else {
                    if (!state.hasSynced) Text(stringResource(R.string.room_guest_start), style = MaterialTheme.typography.bodyMedium)
                    Button(rooms::syncNow, Modifier.fillMaxWidth(), enabled = host != null && !state.syncing && !state.connecting) {
                        Text(stringResource(if (state.syncing) R.string.room_syncing else R.string.room_sync_play))
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.room_follow), Modifier.weight(1f))
                            Switch(state.followAutomatically, rooms::followAutomatically)
                        }
                    }
                }
                state.error?.let { error ->
                    val message = when (error) {
                        RoomError.INVALID_CODE -> R.string.room_invalid_code
                        RoomError.NETWORK -> R.string.room_network_error
                        RoomError.RATE_LIMIT -> R.string.room_rate_limit
                        RoomError.INVALID_MESSAGE -> R.string.room_message_error
                        RoomError.PLAYBACK -> R.string.room_playback_error
                    }
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!state.connecting && error != RoomError.PLAYBACK) TextButton(rooms::retry) { Text(stringResource(R.string.retry)) }
                }
                OutlinedButton(rooms::leave, Modifier.fillMaxWidth()) { Text(stringResource(R.string.room_leave)) }
            }
        }
    }
}

@Composable
private fun RoomInstructions() {
    val badgeSize = with(LocalDensity.current) { 16.sp.toDp() + 10.dp }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.room_how_to), style = MaterialTheme.typography.titleMedium)
            listOf(R.string.room_step_create, R.string.room_step_share, R.string.room_step_join, R.string.room_step_sync).forEachIndexed { index, text ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(badgeSize).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Text((index + 1).toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(stringResource(text), Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
