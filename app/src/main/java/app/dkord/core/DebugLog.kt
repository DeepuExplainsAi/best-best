package app.dkord.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory ring buffer shown on the debug log screen. Never logs cookies or tokens. */
object DebugLog {
    data class Entry(val time: Long, val level: Char, val tag: String, val msg: String)

    private const val MAX = 500
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** Set by the Android layer to mirror into logcat. */
    @Volatile var sink: ((Entry) -> Unit)? = null

    fun d(tag: String, msg: String) = add('D', tag, msg)
    fun w(tag: String, msg: String) = add('W', tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        add('E', tag, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message?.lineSequence()?.firstOrNull()}")

    fun clear() { _entries.value = emptyList() }

    fun dump(): String = _entries.value.joinToString("\n") { "${it.time} ${it.level}/${it.tag}: ${it.msg}" }

    private fun add(level: Char, tag: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), level, tag, redact(msg))
        _entries.value = (_entries.value + e).takeLast(MAX)
        sink?.invoke(e)
    }

    private val secret = Regex("(SAPISID|SID|HSID|SSID|APISID|PSID|PSIDTS|PSIDCC|LOGIN_INFO)[A-Za-z0-9_-]*=[^;\\s]+|SAPISIDHASH\\s+\\S+|[?&](signature|sig|lsig)=[^&\\s]+")
    private fun redact(s: String) = secret.replace(s, "<redacted>")
}
