package app.dkord.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.dkord.core.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Source of the session cookie string; abstracted so network code is testable on the JVM. */
fun interface CookieSource { fun cookies(): String? }

/** Session cookies live only in EncryptedSharedPreferences. Nothing is logged or committed. */
class AuthStore(private val context: Context) : CookieSource {

    private val prefs: SharedPreferences by lazy { open() }
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    init { _loggedIn.value = SapisidHash.isLoggedIn(cookies()) }

    override fun cookies(): String? = runCatching { prefs.getString(KEY_COOKIES, null) }.getOrNull()

    var accountName: String?
        get() = runCatching { prefs.getString(KEY_NAME, null) }.getOrNull()
        set(v) { prefs.edit().putString(KEY_NAME, v).apply() }

    var accountPhoto: String?
        get() = runCatching { prefs.getString(KEY_PHOTO, null) }.getOrNull()
        set(v) { prefs.edit().putString(KEY_PHOTO, v).apply() }

    /** Reads the WebView cookie jar and persists it if it holds a real session. */
    fun captureFromWebView(): Boolean {
        val cm = CookieManager.getInstance()
        val raw = listOf("https://music.youtube.com", "https://www.youtube.com", "https://accounts.google.com")
            .mapNotNull { cm.getCookie(it) }.joinToString("; ")
        val merged = raw.split(';').map { it.trim() }.filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
            .entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (!SapisidHash.isLoggedIn(merged)) return false
        prefs.edit().putString(KEY_COOKIES, merged).apply()
        _loggedIn.value = true
        DebugLog.d("auth", "session captured (${merged.count { it == ';' } + 1} cookies)")
        return true
    }

    fun logout() {
        prefs.edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        _loggedIn.value = false
        DebugLog.d("auth", "logged out, cookies cleared")
    }

    private fun open(): SharedPreferences {
        fun create() = EncryptedSharedPreferences.create(
            context, FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return try { create() } catch (e: Exception) {
            // Keystore invalidated (restore, lock-screen change): the old blob is unreadable, start clean.
            DebugLog.e("auth", "encrypted prefs unreadable, resetting", e)
            context.deleteSharedPreferences(FILE)
            create()
        }
    }

    private companion object {
        const val FILE = "dkord_auth"
        const val KEY_COOKIES = "cookies"
        const val KEY_NAME = "name"
        const val KEY_PHOTO = "photo"
    }
}
