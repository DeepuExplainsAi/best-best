package app.dkord.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import app.dkord.data.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Shared updater. App data/signing identity are never reset or replaced to make an update work. */
class AppUpdater(context: Context) {
    private val app = context.applicationContext
    val currentVersion: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    private val preferences = app.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val downloader = UpdateDownloader()
    private fun file(info: UpdateInfo) = File(File(app.cacheDir, "updates"), "Dkord-${info.version}.apk")

    private val coordinator = UpdateCoordinator(
        platform = object : UpdatePlatform {
            override suspend fun findUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Update check failed")
                    UpdateReleases.parse(response.body.string(), currentVersion)
                }
            }
            override suspend fun download(info: UpdateInfo, onProgress: (UpdateProgress) -> Unit) {
                val apk = file(info)
                downloader.download(info, apk, onProgress)
                withContext(Dispatchers.IO) {
                    try { verifyApk(apk, info) }
                    catch (e: Exception) { apk.delete(); throw e }
                }
            }
            override fun canInstall() = app.packageManager.canRequestPackageInstalls()
            override fun openInstaller(info: UpdateInfo) {
                if (!canInstall()) {
                    app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                }
                val apk = file(info)
                if (!apk.isFile || apk.length() == 0L) throw IOException("Update file missing")
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", apk)
                app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        readDeferred = { preferences.getString("later-version", null)?.let { DeferredUpdate(it, preferences.getLong("later-until", 0)) } },
        saveDeferred = { preferences.edit().putString("later-version", it.version).putLong("later-until", it.untilMs).apply() },
    )

    val state = coordinator.state
    fun onForeground() = coordinator.onForeground()
    fun check() = coordinator.check(force = true)
    fun later() = coordinator.later()
    fun download() = coordinator.download()
    fun cancel() = coordinator.cancel()
    fun install() = coordinator.install()

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, info: UpdateInfo) {
        val manager = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = manager.getPackageInfo(app.packageName, flags)
        val candidate = manager.getPackageArchiveInfo(apk.absolutePath, flags) ?: throw UpdateVerificationException()
        fun signatures(value: PackageInfo): Set<String> {
            val signers = if (Build.VERSION.SDK_INT >= 28) value.signingInfo?.apkContentsSigners else value.signatures
            return signers.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }.toSet()
        }
        val currentSigners = signatures(installed)
        if (candidate.packageName != app.packageName || candidate.versionName != info.version ||
            PackageInfoCompat.getLongVersionCode(candidate) <= PackageInfoCompat.getLongVersionCode(installed) ||
            currentSigners.isEmpty() || signatures(candidate) != currentSigners
        ) throw UpdateVerificationException()
    }

    companion object {
        const val LATEST = "https://api.github.com/repos/DeepuExplainsAi/dkord/releases/latest"
        fun isNewer(tag: String, current: String) = UpdateReleases.isNewer(tag, current)
    }
}
