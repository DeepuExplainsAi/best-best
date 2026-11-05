package app.dkord.data.yt

import app.dkord.core.DebugLog
import app.dkord.data.auth.CookieSource
import app.dkord.data.auth.SapisidHash
import app.dkord.data.net.Http
import app.dkord.data.stream.StreamError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

/** YouTube Music web client (WEB_REMIX). Sends the SAPISIDHASH header when a session exists. */
class InnerTubeClient(
    private val cookies: CookieSource,
    private val http: OkHttpClient = Http.client,
    private val locale: () -> Locale = { Locale.getDefault() },
) {
    class AuthExpired : IOException("Your YouTube Music session expired. Please sign in again.")

    suspend fun browse(browseId: String? = null, params: String? = null, continuation: String? = null): JsonElement =
        call("browse") {
            browseId?.let { put("browseId", it) }
            params?.let { put("params", it) }
            continuation?.let { put("continuation", it) }
        }

    suspend fun accountMenu(): JsonElement = call("account/account_menu") {}

    private suspend fun call(endpoint: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonElement =
        withContext(Dispatchers.IO) {
            val loc = locale()
            val payload = buildJsonObject {
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", CLIENT_NAME); put("clientVersion", CLIENT_VERSION)
                        put("hl", loc.language.ifEmpty { "en" }); put("gl", loc.country.ifEmpty { "US" })
                    })
                })
                body()
            }
            val authHeaders = SapisidHash.headers(cookies.cookies())
            val req = Request.Builder().url("$BASE/$endpoint?prettyPrint=false")
                .header("User-Agent", WEB_UA)
                .header("Origin", SapisidHash.ORIGIN)
                .header("Referer", SapisidHash.ORIGIN + "/")
                .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
                .post(payload.toString().toRequestBody(Http.JSON)).build()
            try {
                http.newCall(req).execute().use { r ->
                    val text = r.body.string()
                    if ((r.code == 401 || r.code == 403) && authHeaders.isNotEmpty()) {
                        DebugLog.w("innertube", "$endpoint -> ${r.code} with session: expired")
                        throw AuthExpired()
                    }
                    if (r.code == 429) throw StreamError.BotCheck("(rate limited)")
                    if (!r.isSuccessful) throw IOException("YouTube Music returned HTTP ${r.code}")
                    lenientJson.parseToJsonElement(text).also {
                        if (it !is JsonObject) throw IOException("Unexpected response")
                    }
                }
            } catch (e: IOException) {
                DebugLog.e("innertube", "$endpoint failed", e); throw e
            }
        }

    companion object {
        const val BASE = "https://music.youtube.com/youtubei/v1"
        const val CLIENT_NAME = "WEB_REMIX"
        /** Verified live on 2026-11-05. Older versions keep working for a long time; bump if browse breaks. */
        const val CLIENT_VERSION = "1.20260121.03.00"
        /** Desktop Chrome UA: Google blocks sign-in inside UAs that identify as a WebView. */
        const val WEB_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}
