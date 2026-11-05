package app.dkord.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert suspend fun upsert(track: TrackEntity)
    @Upsert suspend fun upsertAll(tracks: List<TrackEntity>)
}

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.*, " +
            "(SELECT COUNT(*) FROM playlist_items WHERE playlistId = p.id) AS trackCount, " +
            "(SELECT t.thumbnail FROM playlist_items i JOIN tracks t ON t.id = i.trackId " +
            " WHERE i.playlistId = p.id ORDER BY i.position LIMIT 1) AS cover " +
            "FROM playlists p ORDER BY p.updatedAt DESC",
    )
    fun observeAll(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id") fun observe(id: Long): Flow<PlaylistEntity?>
    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun get(id: Long): PlaylistEntity?

    @Query(
        "SELECT t.*, i.id AS itemId FROM playlist_items i JOIN tracks t ON t.id = i.trackId " +
            "WHERE i.playlistId = :id ORDER BY i.position",
    )
    fun observeEntries(id: Long): Flow<List<PlaylistEntryRow>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position")
    suspend fun items(id: Long): List<PlaylistItemEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int

    @Insert suspend fun insert(playlist: PlaylistEntity): Long
    @Insert suspend fun insertItems(items: List<PlaylistItemEntity>)
    @Update suspend fun updateItems(items: List<PlaylistItemEntity>)

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id") suspend fun touch(id: Long, now: Long)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM playlist_items WHERE id = :itemId") suspend fun deleteItem(itemId: Long)
}

@Dao
interface LibraryDao {
    @Upsert suspend fun like(e: LikedEntity)
    @Query("DELETE FROM liked WHERE trackId = :id") suspend fun unlike(id: String)

    @Query("SELECT t.* FROM liked l JOIN tracks t ON t.id = l.trackId ORDER BY l.likedAt DESC")
    fun observeLiked(): Flow<List<TrackEntity>>

    @Query("SELECT trackId FROM liked") fun observeLikedIds(): Flow<List<String>>

    @Upsert suspend fun played(e: HistoryEntity)

    @Query("SELECT t.* FROM history h JOIN tracks t ON t.id = h.trackId ORDER BY h.playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TrackEntity>>

    @Query("DELETE FROM history WHERE trackId NOT IN (SELECT trackId FROM history ORDER BY playedAt DESC LIMIT :keep)")
    suspend fun trimHistory(keep: Int)

    @Query("DELETE FROM history") suspend fun clearHistory()
}

@Dao
interface DownloadDao {
    @Upsert suspend fun upsert(e: DownloadEntity)
    @Query("SELECT * FROM downloads WHERE trackId = :id") suspend fun get(id: String): DownloadEntity?

    @Transaction
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadWithTrack>>

    @Transaction
    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY createdAt LIMIT 1")
    suspend fun nextQueued(): DownloadWithTrack?

    @Query("SELECT localUri FROM downloads WHERE trackId = :id AND state = 'DONE'")
    suspend fun localUri(id: String): String?

    @Query("UPDATE downloads SET state = 'RUNNING', error = NULL, updatedAt = :now WHERE trackId = :id AND state = 'QUEUED'")
    suspend fun markRunning(id: String, now: Long): Int

    @Query("UPDATE downloads SET state = :state, error = :error, updatedAt = :now WHERE trackId = :id")
    suspend fun setState(id: String, state: DownloadState, error: String?, now: Long)

    @Query("UPDATE downloads SET state = 'FAILED', error = :error, updatedAt = :now WHERE trackId = :id AND state = 'RUNNING'")
    suspend fun failIfRunning(id: String, error: String, now: Long)

    @Query("UPDATE downloads SET state = 'QUEUED', updatedAt = :now WHERE trackId = :id AND state = 'RUNNING'")
    suspend fun requeueIfRunning(id: String, now: Long)

    @Query("UPDATE downloads SET mime = :mime, bytes = :bytes, total = :total WHERE trackId = :id")
    suspend fun setPartial(id: String, mime: String, bytes: Long, total: Long)

    @Query("UPDATE downloads SET bytes = :bytes, total = :total WHERE trackId = :id AND state = 'RUNNING'")
    suspend fun progress(id: String, bytes: Long, total: Long)

    @Query(
        "UPDATE downloads SET state = 'DONE', localUri = :uri, bytes = :size, total = :size, mime = :mime, " +
            "error = NULL, updatedAt = :now WHERE trackId = :id",
    )
    suspend fun done(id: String, uri: String, size: Long, mime: String, now: Long)

    @Query("UPDATE downloads SET state = 'QUEUED', updatedAt = :now WHERE state = 'RUNNING'")
    suspend fun resetRunning(now: Long)

    @Query("UPDATE downloads SET state = 'QUEUED', error = NULL, updatedAt = :now WHERE state = 'FAILED'")
    suspend fun requeueFailed(now: Long)

    @Query("UPDATE downloads SET state = 'PAUSED', updatedAt = :now WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun pauseAll(now: Long)

    @Query("UPDATE downloads SET state = 'QUEUED', updatedAt = :now WHERE state = 'PAUSED'")
    suspend fun resumeAll(now: Long)

    @Query("DELETE FROM downloads WHERE trackId = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM downloads WHERE state != 'DONE'") suspend fun unfinished(): List<DownloadEntity>
}
