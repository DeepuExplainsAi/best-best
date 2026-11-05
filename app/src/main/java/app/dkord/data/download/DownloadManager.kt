package app.dkord.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.dkord.core.Track
import app.dkord.data.db.AppDatabase
import app.dkord.data.db.DownloadEntity
import app.dkord.data.db.DownloadState
import app.dkord.data.db.DownloadWithTrack
import app.dkord.data.db.toEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/** UI-facing API for downloads. The work itself runs in [DownloadWorker] so it survives app kill. */
class DownloadManager(
    private val context: Context,
    private val db: AppDatabase,
    private val storage: DownloadStorage,
) {
    private val dao = db.downloads()

    /** Jobs of the downloads currently running, so pause/cancel can stop them immediately. */
    internal val active = ConcurrentHashMap<String, Job>()

    fun downloads(): Flow<List<DownloadWithTrack>> = dao.observeAll()

    suspend fun enqueue(tracks: List<Track>) {
        val now = System.currentTimeMillis()
        for (t in tracks.distinctBy { it.id }) {
            val existing = dao.get(t.id)
            if (existing?.state == DownloadState.DONE && existing.localUri?.let(storage::exists) == true) continue
            if (existing != null && existing.state != DownloadState.DONE) {
                if (existing.state != DownloadState.QUEUED && existing.state != DownloadState.RUNNING) {
                    dao.setState(t.id, DownloadState.QUEUED, null, now)
                }
                continue
            }
            db.tracks().upsert(t.toEntity())
            dao.upsert(DownloadEntity(t.id, DownloadState.QUEUED, createdAt = now, updatedAt = now))
        }
        kick()
    }

    suspend fun pause(id: String) {
        dao.setState(id, DownloadState.PAUSED, null, System.currentTimeMillis())
        active[id]?.cancel()
    }

    suspend fun resume(id: String) {
        dao.setState(id, DownloadState.QUEUED, null, System.currentTimeMillis())
        kick()
    }

    suspend fun pauseAll() {
        dao.pauseAll(System.currentTimeMillis())
        active.values.forEach { it.cancel() }
    }

    suspend fun resumeAll() {
        val now = System.currentTimeMillis()
        dao.resumeAll(now); dao.requeueFailed(now)
        kick()
    }

    /** Cancels a running/queued download, or deletes a finished one (file included). */
    suspend fun remove(id: String) {
        val row = dao.get(id)
        dao.delete(id)
        active[id]?.cancel()
        active[id]?.join()
        storage.partFile(id).delete()
        row?.localUri?.let(storage::delete)
    }

    /** The playable uri of a finished download, or null if it was never downloaded or the file is gone. */
    suspend fun localUri(id: String): String? {
        val uri = dao.localUri(id) ?: return null
        if (storage.exists(uri)) return uri
        dao.delete(id) // the user deleted the file outside the app
        return null
    }

    fun kick() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // Append: a worker that is just finishing its loop must not strand a newly queued item.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object { const val WORK_NAME = "downloads" }
}
