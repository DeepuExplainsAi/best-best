package app.dkord.data.auth

import java.security.MessageDigest

/** Builds the headers YouTube Music expects from the web session cookies. Pure, unit-tested. */
object SapisidHash {
    const val ORIGIN = "https://music.youtube.com"

    fun cookieValue(cookies: String, name: String): String? =
        cookies.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    fun sapisid(cookies: String): String? =
        cookieValue(cookies, "SAPISID") ?: cookieValue(cookies, "__Secure-3PAPISID")

    fun isLoggedIn(cookies: String?): Boolean = cookies != null && sapisid(cookies) != null

    fun authorization(sapisid: String, nowSec: Long = System.currentTimeMillis() / 1000, origin: String = ORIGIN): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$nowSec $sapisid $origin".toByteArray())
        return "SAPISIDHASH ${nowSec}_${digest.joinToString("") { "%02x".format(it) }}"
    }

    /** Extra request headers for a logged-in session; empty map when not logged in. */
    fun headers(cookies: String?, nowSec: Long = System.currentTimeMillis() / 1000): Map<String, String> {
        val c = cookies ?: return emptyMap()
        val id = sapisid(c) ?: return emptyMap()
        return mapOf(
            "Cookie" to c,
            "Authorization" to authorization(id, nowSec),
            "X-Goog-AuthUser" to "0",
            "X-Origin" to ORIGIN,
            "Origin" to ORIGIN,
        )
    }
}
