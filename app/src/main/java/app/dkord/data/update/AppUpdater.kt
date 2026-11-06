package app.dkord.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.dkord.core.DebugLog
import app.dkord.data.net.Http
import app.dkord.data.yt.at
import app.dkord.data.yt.lenientJson
import app.dkord.data.yt.list
import app.dkord.data.yt.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

data class UpdateInfo(val version: String, val apkUrl: String)

/**
 * Checks this repo's latest GitHub release and installs its APK over the running app. Android keeps
 * all app data (Room database, settings, downloads index) on an in-place update, as long as the APK
 * has the same package name and signing key, which every CI build now has.
 */
class AppUpdater(private val context: Context) {

    val currentVersion: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Newer release, or null if up to date / offline. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val root = lenientJson.parseToJsonElement(r.body.string())
            val tag = root.at("tag_name").str() ?: return@use null
            val apk = root.at("assets").list().firstOrNull { it.at("name").str()?.endsWith(".apk") == true }
                ?.at("browser_download_url").str() ?: return@use null
            if (isNewer(tag, currentVersion)) UpdateInfo(tag.removePrefix("v"), apk) else null
        }
    }

    /** Downloads the APK to the cache and opens the system installer (it replaces the old app in place). */
    suspend fun install(info: UpdateInfo) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
            val out = File(dir, "Dkord-${info.version}.apk")
            Http.client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body.byteStream().use { i -> out.outputStream().use { i.copyTo(it) } }
            }
            out
        }
        DebugLog.d("update", "downloaded ${file.length() / 1024} KB")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val LATEST = "https://api.github.com/repos/DeepuExplainsAi/dkord/releases/latest"

        /** "v0.10.0" > "0.9.1": numeric, part by part. */
        fun isNewer(tag: String, current: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(tag); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
