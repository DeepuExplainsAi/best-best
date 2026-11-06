package app.dkord.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.dkord.R
import app.dkord.data.download.DownloadStorage
import app.dkord.data.settings.SettingsRepository
import app.dkord.data.settings.ThemeMode
import app.dkord.data.stream.AudioQuality
import app.dkord.data.stream.ExtractorHealth
import app.dkord.data.stream.StreamModule
import app.dkord.player.AudioCache
import app.dkord.ui.common.ConfirmDialog
import app.dkord.ui.common.SectionTitle
import app.dkord.ui.common.formatSize
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogs: () -> Unit, onMessage: (String) -> Unit) {
    val repo: SettingsRepository = koinInject()
    val cache: AudioCache = koinInject()
    val streams: StreamModule = koinInject()
    val storage: DownloadStorage = koinInject()
    val s by repo.settings.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf("") }
    var cacheSize by remember { mutableStateOf(cache.sizeBytes) }
    val updater: app.dkord.data.update.AppUpdater = koinInject()
    var pending by remember { mutableStateOf<app.dkord.data.update.UpdateInfo?>(null) }
    var updating by remember { mutableStateOf(false) }
    var latest by rememberSaveable { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { storage.keepAccess(uri) }
        repo.setDownloadTree(uri.toString())
    }

    val qualityNames = mapOf(AudioQuality.LOW to R.string.q_low, AudioQuality.MEDIUM to R.string.q_medium, AudioQuality.HIGH to R.string.q_high)
    val themeNames = mapOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark, ThemeMode.AMOLED to R.string.theme_amoled)

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.audio))
            Item(stringResource(R.string.audio_quality), stringResource(qualityNames.getValue(s.quality))) { dialog = "quality" }

            SectionTitle(stringResource(R.string.appearance))
            Item(stringResource(R.string.theme), stringResource(themeNames.getValue(s.theme))) { dialog = "theme" }
            if (Build.VERSION.SDK_INT >= 31) {
                ListItem(
                    modifier = Modifier.clickable { repo.setDynamicColor(!s.dynamicColor) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.dynamic_color)) },
                    supportingContent = { Text(stringResource(R.string.dynamic_color_hint)) },
                    trailingContent = { Switch(s.dynamicColor, { repo.setDynamicColor(it) }) },
                )
            }

            SectionTitle(stringResource(R.string.storage))
            Item(
                stringResource(R.string.download_folder),
                s.downloadTree?.let { Uri.decode(it).substringAfterLast(':') } ?: stringResource(R.string.default_folder),
            ) { dialog = "folder" }
            Item(stringResource(R.string.cache_size), "${s.cacheMb} MB • ${stringResource(R.string.in_use, formatSize(cacheSize))}") { dialog = "cache" }
            Item(stringResource(R.string.clear_cache), formatSize(cacheSize)) {
                cache.clear(); streams.clearCache(); cacheSize = cache.sizeBytes
                onMessage(ctx.getString(R.string.cache_cleared))
            }

            SectionTitle(stringResource(R.string.app_update))
            Item(stringResource(R.string.app_update), if (updating) stringResource(R.string.update_downloading) else stringResource(R.string.app_version, updater.currentVersion)) {
                if (updating) return@Item
                onMessage(ctx.getString(R.string.update_checking))
                scope.launch {
                    runCatching { updater.check() }.onFailure { onMessage(ctx.getString(R.string.update_failed)) }
                        .onSuccess { if (it == null) onMessage(ctx.getString(R.string.update_none)) else { pending = it; dialog = "appupdate" } }
                }
            }

            SectionTitle(stringResource(R.string.troubleshooting))
            Item(stringResource(R.string.update_extractor), latest?.let { stringResource(R.string.extractor_versions, ExtractorHealth.BUNDLED_VERSION, it) } ?: stringResource(R.string.extractor_bundled, ExtractorHealth.BUNDLED_VERSION)) {
                scope.launch {
                    val v = ExtractorHealth.latestUpstream()
                    latest = v
                    if (v == null) onMessage(ctx.getString(R.string.err_network))
                    else if (v == ExtractorHealth.BUNDLED_VERSION) onMessage(ctx.getString(R.string.extractor_current))
                    else { dialog = "update" }
                }
            }
            Item(stringResource(R.string.report_broken), stringResource(R.string.report_broken_hint)) {
                val body = ExtractorHealth.reportBody("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                val clip = ctx.getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("report", body))
                onMessage(ctx.getString(R.string.report_copied))
                ctx.startActivity(Intent(Intent.ACTION_VIEW, (ExtractorHealth.ISSUES_NEW + "?title=Playback+broken").toUri()))
            }
            Item(stringResource(R.string.debug_log), stringResource(R.string.debug_log_hint), onLogs)

            SectionTitle(stringResource(R.string.about))
            Item("Dkord", stringResource(R.string.about_text)) {}
        }
    }

    when (dialog) {
        "quality" -> Choice(stringResource(R.string.audio_quality), AudioQuality.entries.map { it to stringResource(qualityNames.getValue(it)) }, s.quality, { repo.setQuality(it) }) { dialog = "" }
        "theme" -> Choice(stringResource(R.string.theme), ThemeMode.entries.map { it to stringResource(themeNames.getValue(it)) }, s.theme, { repo.setTheme(it) }) { dialog = "" }
        "cache" -> Choice(stringResource(R.string.cache_size), listOf(128, 256, 512, 1024, 2048).map { it to "$it MB" }, s.cacheMb, { repo.setCacheMb(it); onMessage(ctx.getString(R.string.applies_on_restart)) }) { dialog = "" }
        "folder" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(stringResource(R.string.download_folder)) },
            text = { Text(stringResource(R.string.folder_hint)) },
            confirmButton = { TextButton({ dialog = ""; pickFolder.launch(null) }) { Text(stringResource(R.string.choose_folder)) } },
            dismissButton = { TextButton({ dialog = ""; repo.setDownloadTree(null) }) { Text(stringResource(R.string.use_default)) } },
        )
        "appupdate" -> pending?.let { u -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(stringResource(R.string.app_update)) },
            text = { Text(stringResource(R.string.update_available, u.version)) },
            confirmButton = { TextButton({
                dialog = ""; updating = true
                scope.launch { runCatching { updater.install(u) }.onFailure { onMessage(ctx.getString(R.string.update_failed)) }; updating = false }
            }) { Text(stringResource(R.string.update_install)) } },
            dismissButton = { TextButton({ dialog = "" }) { Text(stringResource(R.string.cancel)) } },
        ) }
        "update" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(stringResource(R.string.update_extractor)) },
            text = { Text(stringResource(R.string.update_text, ExtractorHealth.BUNDLED_VERSION, latest.orEmpty())) },
            confirmButton = { TextButton({ dialog = ""; ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/DeepuExplainsAi/best-best/releases".toUri())) }) { Text(stringResource(R.string.open_releases)) } },
            dismissButton = { TextButton({ dialog = "" }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Item(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(v); onDismiss() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { RadioButton(v == selected, null) },
                        headlineContent = { Text(label) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
