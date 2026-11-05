package app.dkord.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dkord.R
import app.dkord.core.DebugLog
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onBack: () -> Unit, onMessage: (String) -> Unit) {
    val entries by DebugLog.entries.collectAsState()
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    val fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM)
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) list.scrollToItem(entries.lastIndex) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.debug_log)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton({
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", DebugLog.dump()))
                    onMessage(ctx.getString(R.string.copied))
                }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy)) }
                IconButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, DebugLog.dump()), null))
                }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                IconButton(DebugLog::clear) { Icon(Icons.Default.Delete, stringResource(R.string.clear)) }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 8.dp), state = list) {
            items(entries) { e ->
                Text(
                    "${fmt.format(Date(e.time))} ${e.level}/${e.tag}: ${e.msg}",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    color = when (e.level) { 'E' -> MaterialTheme.colorScheme.error; 'W' -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurface },
                )
            }
        }
    }
}
