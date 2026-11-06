package app.dkord.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.dkord.data.stream.AudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

data class Settings(
    val quality: AudioQuality = AudioQuality.HIGH,
    val theme: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    /** SAF tree uri chosen by the user, or null for the app's own music folder. */
    val downloadTree: String? = null,
    val cacheMb: Int = 512,
    /** When the queue runs out, keep playing related songs. */
    val autoplay: Boolean = true,
)

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val kQuality = stringPreferencesKey("quality")
    private val kTheme = stringPreferencesKey("theme")
    private val kDynamic = stringPreferencesKey("dynamic")
    private val kTree = stringPreferencesKey("download_tree")
    private val kCache = intPreferencesKey("cache_mb")
    private val kAutoplay = stringPreferencesKey("autoplay")

    private fun Preferences.toSettings() = Settings(
        quality = enumOr(this[kQuality], AudioQuality.HIGH),
        theme = enumOr(this[kTheme], ThemeMode.DARK),
        dynamicColor = this[kDynamic] == "1",
        downloadTree = this[kTree],
        cacheMb = this[kCache] ?: 512,
        autoplay = this[kAutoplay] != "0",
    )

    /** First value is loaded synchronously (a tiny file) so the player and theme never start with wrong defaults. */
    val settings: StateFlow<Settings> = store.data.map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { store.data.first().toSettings() })

    fun setQuality(v: AudioQuality) = edit { it[kQuality] = v.name }
    fun setTheme(v: ThemeMode) = edit { it[kTheme] = v.name }
    fun setDynamicColor(v: Boolean) = edit { it[kDynamic] = if (v) "1" else "0" }
    fun setAutoplay(v: Boolean) = edit { it[kAutoplay] = if (v) "1" else "0" }
    fun setCacheMb(v: Int) = edit { it[kCache] = v }
    fun setDownloadTree(uri: String?) = edit { if (uri == null) it.remove(kTree) else it[kTree] = uri }

    private fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { store.edit { block(it) } }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
