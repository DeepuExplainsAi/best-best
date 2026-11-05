package app.dkord.data.library

import androidx.room.withTransaction
import app.dkord.core.Track
import app.dkord.data.db.AppDatabase
import app.dkord.data.db.HistoryEntity
import app.dkord.data.db.LikedEntity
import app.dkord.data.db.PlaylistEntity
import app.dkord.data.db.PlaylistItemEntity
import app.dkord.data.db.PlaylistEntryRow
import app.dkord.data.db.PlaylistSummary
import app.dkord.data.db.toEntity
import app.dkord.data.db.toTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Playlists, liked songs and play history, all local. */
class LibraryRepository(private val db: AppDatabase) {
    private val playlists = db.playlists()
    private val library = db.library()

    // ---- playlists ----

    fun playlists(): Flow<List<PlaylistSummary>> = playlists.observeAll()
    fun playlist(id: Long): Flow<PlaylistEntity?> = playlists.observe(id)
    fun entries(id: Long): Flow<List<PlaylistEntryRow>> = playlists.observeEntries(id)
    fun tracks(id: Long): Flow<List<Track>> = entries(id).map { rows -> rows.map { it.track.toTrack() } }

    suspend fun create(name: String): Long {
        val now = System.currentTimeMillis()
        return playlists.insert(PlaylistEntity(name = name.trim().ifBlank { "Playlist" }, createdAt = now, updatedAt = now))
    }

    suspend fun rename(id: Long, name: String) {
        if (name.isNotBlank()) playlists.rename(id, name.trim(), System.currentTimeMillis())
    }

    suspend fun delete(id: Long) = playlists.delete(id)

    /** Returns how many tracks were actually added (tracks already in the playlist are skipped). */
    suspend fun add(playlistId: Long, tracks: List<Track>): Int = db.withTransaction {
        val existing = playlists.items(playlistId).map { it.trackId }.toHashSet()
        val fresh = tracks.distinctBy { it.id }.filter { existing.add(it.id) }
        if (fresh.isEmpty()) return@withTransaction 0
        db.tracks().upsertAll(fresh.map { it.toEntity() })
        val start = playlists.maxPosition(playlistId) + 1
        playlists.insertItems(fresh.mapIndexed { i, t -> PlaylistItemEntity(playlistId = playlistId, trackId = t.id, position = start + i) })
        playlists.touch(playlistId, System.currentTimeMillis())
        fresh.size
    }

    suspend fun remove(playlistId: Long, itemId: Long) = db.withTransaction {
        playlists.deleteItem(itemId)
        renumber(playlistId, playlists.items(playlistId))
    }

    /** Moves the entry at [from] to [to] (both indexes into the displayed list). */
    suspend fun move(playlistId: Long, from: Int, to: Int) = db.withTransaction {
        val items = playlists.items(playlistId).toMutableList()
        if (from !in items.indices || to !in items.indices || from == to) return@withTransaction
        items.add(to, items.removeAt(from))
        renumber(playlistId, items)
    }

    private suspend fun renumber(playlistId: Long, items: List<PlaylistItemEntity>) {
        playlists.updateItems(items.mapIndexed { i, it -> it.copy(position = i) })
        playlists.touch(playlistId, System.currentTimeMillis())
    }

    // ---- liked ----

    fun liked(): Flow<List<Track>> = library.observeLiked().map { l -> l.map { it.toTrack() } }
    fun likedIds(): Flow<Set<String>> = library.observeLikedIds().map { it.toHashSet() }

    suspend fun setLiked(track: Track, liked: Boolean) = db.withTransaction {
        if (liked) {
            db.tracks().upsert(track.toEntity())
            library.like(LikedEntity(track.id, System.currentTimeMillis()))
        } else library.unlike(track.id)
    }

    // ---- history ----

    fun recent(limit: Int = 50): Flow<List<Track>> = library.observeRecent(limit).map { l -> l.map { it.toTrack() } }

    suspend fun recordPlayed(track: Track) = db.withTransaction {
        db.tracks().upsert(track.toEntity())
        library.played(HistoryEntity(track.id, System.currentTimeMillis()))
        library.trimHistory(HISTORY_KEEP)
    }

    suspend fun clearHistory() = library.clearHistory()

    // ---- JSON import / export ----

    suspend fun exportJson(playlistIds: Collection<Long>? = null, includeLiked: Boolean = true): String {
        val all = playlists.observeAll().first().map { it.playlist }
        val picked = if (playlistIds == null) all else all.filter { it.id in playlistIds }
        val docs = picked.map { p -> PlaylistDoc(p.name, playlists.observeEntries(p.id).first().map { it.track.toTrack() }) }
        val liked = if (includeLiked) library.observeLiked().first().map { it.toTrack() } else emptyList()
        return PlaylistJson.encode(LibraryDoc(playlists = docs, liked = liked))
    }

    /** Imports every playlist as a new playlist and merges liked songs. Returns playlists created. */
    suspend fun importJson(text: String): Int {
        val doc = PlaylistJson.decode(text)
        doc.playlists.forEach { p -> add(create(p.name), p.tracks) }
        doc.liked.reversed().forEach { setLiked(it, true) }
        return doc.playlists.size
    }

    private companion object { const val HISTORY_KEEP = 300 }
}
