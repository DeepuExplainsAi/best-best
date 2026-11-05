package app.dkord.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class, PlaylistEntity::class, PlaylistItemEntity::class,
        LikedEntity::class, HistoryEntity::class, DownloadEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun library(): LibraryDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun build(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "dkord.db").build()
    }
}
