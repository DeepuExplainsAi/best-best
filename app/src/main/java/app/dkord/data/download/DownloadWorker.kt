package app.dkord.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.dkord.R
import app.dkord.core.DebugLog
import app.dkord.data.db.AppDatabase
import app.dkord.data.db.DownloadState
import app.dkord.data.db.DownloadWithTrack
import app.dkord.data.settings.SettingsRepository
import app.dkord.data.stream.StreamError
import app.dkord.data.stream.StreamModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Works through the download queue one track at a time, as a foreground (dataSync) worker. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val db: AppDatabase by inject()
    private val manager: DownloadManager by inject()
    private val storage: DownloadStorage by inject()
    private val streams: StreamModule by inject()
    private val settings: SettingsRepository by inject()
    private val http by lazy { app.dkord.data.net.Http.client }

    private val dao get() = db.downloads()
    private var finished = 0

    override suspend fun doWork(): Result {
        dao.resetRunning(System.currentTimeMillis()) // leftovers of a killed process (only one worker runs at a time)
        if (dao.nextQueued() == null) return Result.success()
        setForeground(foreground("", 0, 0))

        while (true) {
            val next = dao.nextQueued() ?: break
            val id = next.download.trackId
            if (dao.markRunning(id, System.currentTimeMillis()) == 0) continue
            runOne(next)
        }
        return Result.success()
    }

    /** Runs one download as a child job so pause/cancel can stop just that one. */
    private suspend fun runOne(item: DownloadWithTrack) = coroutineScope {
        val id = item.download.trackId
        // Lazy + register + recheck: a pause/remove that landed between "claim" and "register" is seen here.
        val job = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { download(item) }
        manager.active[id] = job
        try {
            if (dao.get(id)?.state != DownloadState.RUNNING) { job.cancel(); return@coroutineScope }
            job.start()
            job.await()
        } catch (e: CancellationException) {
            // Cancelled by pause/remove: the caller already set the new state. If the *worker* was
            // cancelled (WorkManager stopped it) put the item back so the next run continues it.
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable) { dao.requeueIfRunning(id, System.currentTimeMillis()) }
                throw e
            }
        } finally {
            manager.active.remove(id, job)
        }
    }

    private suspend fun download(item: DownloadWithTrack) {
        val id = item.download.trackId
        val track = item.track
        val part = storage.partFile(id)
        var lastWrite = 0L
        var lastUi = 0L
        try {
            val quality = settings.settings.value.quality
            val downloader = TrackDownloader(streams, http, streams::invalidate)
            val res = downloader.fetch(id, quality, part, item.download.mime) { bytes, total, ident ->
                val now = System.currentTimeMillis()
                if (now - lastWrite >= 500) {
                    lastWrite = now
                    dao.setPartial(id, ident, bytes, total)
                    if (now - lastUi >= 1000) { lastUi = now; setForeground(foreground(track.title, bytes, total)) }
                }
            }
            // Past this point the bytes are complete: finish tagging, publishing and the DB commit even if
            // the user pauses now. Cancelling halfway would leave a file nobody tracks.
            withContext(NonCancellable) { finish(id, track, part, res) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamError) {
            fail(id, e.message ?: "Unavailable")
        } catch (e: Exception) {
            DebugLog.e("download", "$id failed", e)
            fail(id, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun finish(id: String, track: app.dkord.core.Track, part: java.io.File, res: TrackDownloader.Result) {
        val ext = if (res.mime.contains("mp4")) "m4a" else "webm"
        val tagged = if (ext == "m4a") tag(id, part, track.title, track.artist, track.album, track.thumbnail) else part
        val name = DownloadStorage.safeName(track.artist, track.title, ext)
        val uri = storage.publish(tagged, name, res.mime, settings.settings.value.downloadTree)
        part.delete()
        if (dao.done(id, uri, res.size, res.mime, System.currentTimeMillis()) == 0) {
            storage.delete(uri) // the user removed the download while it was being saved
            return
        }
        finished++
        DebugLog.d("download", "$id saved ($ext, ${res.size / 1024} KB)")
    }

    private suspend fun fail(id: String, message: String) {
        withContext(NonCancellable) { dao.failIfRunning(id, message, System.currentTimeMillis()) }
    }

    /** Tags the M4A and returns the tagged file. If tagging fails the untagged file is kept: playable beats perfect. */
    private fun tag(id: String, src: java.io.File, title: String, artist: String, album: String?, cover: String?): java.io.File {
        val out = java.io.File(src.parentFile, "$id.tagged")
        return try {
            val art = cover?.let(::fetchCover)
            Mp4Tagger.tag(src, out, Mp4Tagger.Tags(title, artist, album, art, coverIsPng = art?.let { it.size > 3 && it[1] == 'P'.code.toByte() } == true))
            src.delete()
            out
        } catch (e: Exception) {
            DebugLog.w("download", "tagging $id failed: ${e.message}")
            out.delete()
            src
        }
    }

    private fun fetchCover(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val b = r.body.bytes().takeIf { it.size in 100..MAX_COVER } ?: return null
            val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
            val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
            b.takeIf { jpeg || png }
        }
    }.getOrNull()

    private fun foreground(title: String, bytes: Long, total: Long): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.downloading))
            .setContentText(title)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setProgress(if (total > 0) 100 else 0, if (total > 0) (bytes * 100 / total).toInt() else 0, total <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    private companion object {
        const val CHANNEL = "dkord_downloads"
        const val NOTIF_ID = 2001
        const val MAX_COVER = 6 * 1024 * 1024
    }
}
