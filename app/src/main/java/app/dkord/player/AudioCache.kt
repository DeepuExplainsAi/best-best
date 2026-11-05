@file:OptIn(UnstableApi::class)

package app.dkord.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Streaming cache (LRU). One instance per process: SimpleCache forbids two on the same folder. */
class AudioCache(context: Context, maxBytes: Long = DEFAULT_MAX_BYTES) {
    val cache: SimpleCache = SimpleCache(
        File(context.cacheDir, "audio_cache"),
        LeastRecentlyUsedCacheEvictor(maxBytes),
        StandaloneDatabaseProvider(context),
    )

    val sizeBytes: Long get() = cache.cacheSpace

    fun remove(videoId: String) {
        cache.keys.filter { it.startsWith(videoId) }.forEach { runCatching { cache.removeResource(it) } }
    }

    fun clear() = cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }

    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
        /** Key includes quality: partial data of one format must never be mixed with another. */
        fun key(videoId: String, quality: app.dkord.data.stream.AudioQuality) = "$videoId|$quality"
    }
}
