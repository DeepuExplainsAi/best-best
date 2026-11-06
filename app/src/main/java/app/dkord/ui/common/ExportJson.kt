package app.dkord.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.dkord.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Download" for a whole playlist/album: saves a JSON file (name + tracks) instead of audio files. */
@Composable
fun ExportJsonButton(fileName: String, json: () -> String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = json()
        scope.launch {
            withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) } }
        }
    }
    IconButton({ launcher.launch(fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "playlist" } + ".json") }) {
        Icon(Icons.Default.FileDownload, stringResource(R.string.export_playlist_json))
    }
}
