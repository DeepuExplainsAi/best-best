package app.dkord.data.auth

import android.content.Context

/** Source of the session cookie string; abstracted so network code is testable on the JVM. */
fun interface CookieSource { fun cookies(): String? }

/**
 * Sign-in is intentionally unsupported: YouTube Music's library/home data is only reachable with web
 * cookies, and the supported Google OAuth flow (Custom Tabs + PKCE) does not grant access to it.
 * Dkord never captures cookies or shows Google's login page in a WebView. This class only removes
 * the session an older version may have stored.
 */
class AuthStore(context: Context) : CookieSource {
    init { context.deleteSharedPreferences("dkord_auth") }

    override fun cookies(): String? = null
}
