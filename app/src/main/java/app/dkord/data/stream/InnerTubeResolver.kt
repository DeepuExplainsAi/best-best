package app.dkord.data.stream

import app.dkord.core.DebugLog
import app.dkord.data.net.Http
import app.dkord.data.yt.lenientJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Player client identities that currently return plain audio URLs without a PO token.
 * These are the first thing to update when YouTube breaks things: see [ClientProfile.ALL].
 * Values were verified against the live API on 2026-11-05.
 */
data class ClientProfile(val name: String, val version: String, val userAgent: String, val extra: Map<String, String>, val androidSdk: Int? = null) {
    companion object {
        val ANDROID_VR = ClientProfile(
            "ANDROID_VR", "1.62.27",
            "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android", "osVersion" to "12L"), 32,
        )
        val IOS = ClientProfile(
            "IOS", "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
        )
        val ALL = listOf(ANDROID_VR, IOS)
    }
}

/** Visitor identity. Rotating it often clears a temporary "confirm you're not a bot" block. */
class VisitorData(private val http: okhttp3.OkHttpClient = Http.client) {
    private val mutex = Mutex()
    @Volatile private var value: String? = null

    suspend fun get(): String? = value ?: refresh()

    suspend fun refresh(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20251020.01.00"}}}"""
                val req = Request.Builder().url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                    .post(body.toRequestBody(Http.JSON)).build()
                http.newCall(req).execute().use { r ->
                    val el = lenientJson.parseToJsonElement(r.body.string())
                    ((el as? JsonObject)?.get("responseContext") as? JsonObject)?.get("visitorData")
                        ?.let { (it as? JsonPrimitive)?.content }
                }
            }.onFailure { DebugLog.w("visitor", "refresh failed: ${it.message}") }
                .getOrNull()?.also { value = it; DebugLog.d("visitor", "fresh visitor data acquired") }
        }
    }
}

/** Fallback 2/3: direct /player call with an alternate client. */
class InnerTubeResolver(
    private val profile: ClientProfile,
    private val visitor: VisitorData,
    private val useFreshVisitor: Boolean = false,
    private val http: okhttp3.OkHttpClient = Http.client,
) : StreamResolver {
    override val name = "innertube:${profile.name}" + if (useFreshVisitor) "+fresh" else ""

    override suspend fun resolve(videoId: String, quality: AudioQuality, preferM4a: Boolean): ResolvedStream {
        val vd = if (useFreshVisitor) visitor.refresh() else visitor.get()
        val json = withContext(Dispatchers.IO) { fetch(videoId, vd) }
        return PlayerResponseParser.parse(videoId, json, quality, preferM4a, name, profile.userAgent)
    }

    private fun fetch(videoId: String, visitorData: String?): JsonElement {
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", profile.name); put("clientVersion", profile.version)
                    profile.extra.forEach { (k, v) -> put(k, v) }
                    profile.androidSdk?.let { put("androidSdkVersion", it) }
                    put("hl", "en"); put("gl", "US")
                    visitorData?.let { put("visitorData", it) }
                })
            })
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
        }
        val req = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", profile.userAgent)
            .apply { visitorData?.let { header("X-Goog-Visitor-Id", it) } }
            .post(body.toString().toRequestBody(Http.JSON)).build()
        try {
            http.newCall(req).execute().use { r ->
                if (r.code == 429) throw StreamError.BotCheck("(rate limited)")
                return lenientJson.parseToJsonElement(r.body.string())
            }
        } catch (e: IOException) {
            if (e is StreamError) throw e
            throw StreamError.Network(e)
        }
    }
}
