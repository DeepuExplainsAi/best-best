package app.dkord.data.update

import app.dkord.data.yt.at
import app.dkord.data.yt.lenientJson
import app.dkord.data.yt.list
import app.dkord.data.yt.str
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Only public release metadata; no account/session information is involved. */
data class UpdateInfo(val version: String, val apkUrl: String, val sizeBytes: Long? = null, val sha256: String? = null)

data class UpdateProgress(val downloadedBytes: Long = 0, val totalBytes: Long? = null) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    val remainingBytes: Long? get() = totalBytes?.takeIf { it > 0 }?.let { (it - downloadedBytes).coerceAtLeast(0) }
}

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }
enum class UpdateFailure { NETWORK, VERIFICATION, INSTALLER }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val info: UpdateInfo? = null,
    val progress: UpdateProgress = UpdateProgress(),
    val failure: UpdateFailure? = null,
    val promptAllowed: Boolean = false,
    val needsInstallPermission: Boolean = false,
)

data class DeferredUpdate(val version: String, val untilMs: Long)
class UpdateVerificationException : IOException("Update could not be verified")

internal fun shouldPromptUpdate(info: UpdateInfo, deferred: DeferredUpdate?, nowMs: Long): Boolean =
    deferred == null || deferred.version != info.version || nowMs >= deferred.untilMs

/** Pure parsing/validation, also used by the existing version-comparison tests. */
object UpdateReleases {
    private val version = Regex("v?\\d+(?:\\.\\d+){1,3}")
    private val digest = Regex("sha256:([0-9a-fA-F]{64})")

    fun parse(body: String, currentVersion: String): UpdateInfo? {
        val root = lenientJson.parseToJsonElement(body)
        if (root.at("draft")?.jsonPrimitive?.booleanOrNull == true || root.at("prerelease")?.jsonPrimitive?.booleanOrNull == true) return null
        val tag = root.at("tag_name").str() ?: throw IOException("Missing release version")
        if (!version.matches(tag)) throw IOException("Unsupported release version")
        if (!isNewer(tag, currentVersion)) return null
        val apks = root.at("assets").list().filter { it.at("name").str()?.endsWith(".apk", ignoreCase = true) == true }
        if (apks.size != 1) throw IOException("Expected one release APK")
        val asset = apks.single()
        val url = asset.at("browser_download_url").str()?.toHttpUrlOrNull() ?: throw IOException("Missing APK URL")
        if (url.scheme != "https" || url.host != "github.com" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || url.pathSegments.size != 6 ||
            url.pathSegments.take(4) != listOf("DeepuExplainsAi", "dkord", "releases", "download") ||
            url.pathSegments[4] != tag || !url.pathSegments.last().endsWith(".apk", ignoreCase = true)
        ) throw IOException("Unexpected APK URL")
        val size = asset.at("size")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
        val rawDigest = asset.at("digest").str()
        val sha = rawDigest?.let { digest.matchEntire(it)?.groupValues?.get(1)?.lowercase() ?: throw IOException("Invalid APK digest") }
        return UpdateInfo(tag.removePrefix("v"), url.toString(), size, sha)
    }

    fun isNewer(tag: String, current: String): Boolean {
        fun parts(value: String) = value.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(tag); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
