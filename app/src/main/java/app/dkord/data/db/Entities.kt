package app.dkord.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.dkord.core.Track

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Long,
    val thumbnail: String?,
)

fun Track.toEntity() = TrackEntity(id, title, artist, album, durationSec, thumbnail)
fun TrackEntity.toTrack() = Track(id, title, artist, album, durationSec, thumbnail)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackId: String,
    val position: Int,
)

@Entity(
    tableName = "liked",
    foreignKeys = [ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE)],
)
data class LikedEntity(@PrimaryKey val trackId: String, val likedAt: Long)

@Entity(
    tableName = "history",
    foreignKeys = [ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE)],
)
data class HistoryEntity(@PrimaryKey val trackId: String, val playedAt: Long)

enum class DownloadState { QUEUED, RUNNING, PAUSED, DONE, FAILED }

@Entity(
    tableName = "downloads",
    foreignKeys = [ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("state")],
)
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val state: DownloadState,
    val bytes: Long = 0,
    val total: Long = 0,
    /** Mime of the partial/final file; a partial is only resumed if the new stream has the same mime. */
    val mime: String? = null,
    /** file:// or content:// uri of the finished file. */
    val localUri: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

data class DownloadWithTrack(
    @Embedded val download: DownloadEntity,
    @Relation(parentColumn = "trackId", entityColumn = "id") val track: TrackEntity,
)

data class PlaylistSummary(
    @Embedded val playlist: PlaylistEntity,
    val trackCount: Int,
    val cover: String?,
)

/** A track inside a playlist; [itemId] identifies this occurrence (for remove/reorder). */
data class PlaylistEntryRow(
    @Embedded val track: TrackEntity,
    val itemId: Long,
)
