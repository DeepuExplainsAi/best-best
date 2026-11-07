package app.dkord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.dkord.R
import app.dkord.data.room.RoomController
import app.dkord.data.room.RoomError
import app.dkord.data.room.RoomRole
import app.dkord.ui.common.Cover
import app.dkord.ui.common.sharePlainText
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherScreen(onBack: () -> Unit) {
    val rooms: RoomController = koinInject()
    val state by rooms.state.collectAsState()
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.listen_together)) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.room_privacy_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.room_anyone_can_join), color = MaterialTheme.colorScheme.primary)
            if (state.role == null) {
                Button(rooms::create, Modifier.fillMaxWidth()) { Text(stringResource(R.string.room_create)) }
                OutlinedTextField(input, { input = it.take(32) }, label = { Text(stringResource(R.string.room_code)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    isError = state.error == RoomError.INVALID_CODE)
                OutlinedButton({ rooms.join(input) }, Modifier.fillMaxWidth(), enabled = input.isNotBlank()) {
                    Text(stringResource(R.string.room_join))
                }
            } else {
                val code = state.code.orEmpty()
                val invitation = stringResource(R.string.room_invitation, code)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (state.role == RoomRole.HOST) R.string.room_host else R.string.room_guest))
                        SelectionContainer { Text(code, style = MaterialTheme.typography.headlineMedium) }
                        TextButton({ sharePlainText(context, invitation) }) {
                            Icon(Icons.Default.Share, null)
                            Text(stringResource(R.string.room_share_code), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.role == RoomRole.HOST) Text(stringResource(R.string.room_host_controls))
                else {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val host = state.host
                            if (host == null) Text(stringResource(R.string.room_waiting))
                            else {
                                Text(stringResource(R.string.room_host_listening, host.title))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Cover(host.thumbnail, 56.dp)
                                    Text(host.artist, Modifier.padding(start = 12.dp).weight(1f))
                                }
                            }
                            Button(rooms::syncNow, enabled = host != null) { Text(stringResource(R.string.room_sync_play)) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.room_follow), Modifier.weight(1f))
                                Switch(state.followAutomatically, rooms::followAutomatically)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.room_local_audio), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.room_stays_connected), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(rooms::leave, Modifier.fillMaxWidth()) { Text(stringResource(R.string.room_leave)) }
            }
            state.error?.let { error ->
                val message = when (error) {
                    RoomError.INVALID_CODE -> R.string.room_invalid_code
                    RoomError.NETWORK -> R.string.room_network_error
                    RoomError.RATE_LIMIT -> R.string.room_rate_limit
                    RoomError.INVALID_MESSAGE -> R.string.room_message_error
                    RoomError.PLAYBACK -> R.string.room_playback_error
                }
                Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                if (state.role != null && error != RoomError.PLAYBACK) TextButton(rooms::retry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
