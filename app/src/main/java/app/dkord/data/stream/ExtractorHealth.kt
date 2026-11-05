package app.dkord.data.stream

import app.dkord.core.DebugLog
import app.dkord.data.net.Http
import app.dkord.data.yt.lenientJson
import app.dkord.data.yt.at
import app.dkord.data.yt.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Backs the "Update extractor / report broken" setting. */
object ExtractorHealth {
    const val BUNDLED_VERSION = "v0.26.5"
    const val RELEASES_API = "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest"
    const val RELEASES_PAGE = "https://github.com/TeamNewPipe/NewPipeExtractor/releases"
    const val ISSUES_NEW = "https://github.com/DeepuExplainsAi/best-best/issues/new"

    /** Latest upstream tag, or null when offline. Dkord ships the extractor inside the APK, so "update" means a new app build. */
    suspend fun latestUpstream(): String? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(RELEASES_API).header("Accept", "application/vnd.github+json").build())
                .execute().use { lenientJson.parseToJsonElement(it.body.string()).at("tag_name").str() }
        }.onFailure { DebugLog.w("health", "update check failed: ${it.message}") }.getOrNull()
    }

    fun reportBody(deviceInfo: String): String =
        "**App**: Dkord\n**Extractor (bundled)**: $BUNDLED_VERSION\n**Device**: $deviceInfo\n\n**Log (no secrets)**\n```\n${DebugLog.dump().takeLast(6000)}\n```"
}
