package app.dkord.data.releases

import app.dkord.core.Track
import app.dkord.data.yt.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

class ReleaseDatesUnavailable : IOException("Release dates could not be verified")

/** Lazy per-category cache. Switching windows reuses dates, not results from another category. */
class NewReleasesRepository(
    private val sources: List<suspend (ReleaseCategory) -> List<Track>>,
    private val uploadDate: suspend (Track) -> Long?,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Cache(val loadedAt: Long, val tracks: List<DatedTrack>)
    private val cache = mutableMapOf<ReleaseCategory, Cache>()
    private val mutex = Mutex()
    private val dates = Semaphore(6)

    suspend fun songs(window: ReleaseWindow, category: ReleaseCategory = ReleaseCategory.ALL, refresh: Boolean = false): List<Track> = mutex.withLock {
        val stored = cache[category]
        val ttl = if (stored?.tracks.isNullOrEmpty()) EMPTY_CACHE_MS else CACHE_MS
        val tracks = if (!refresh && stored != null && elapsed() - stored.loadedAt in 0 until ttl) stored.tracks
        else fetch(category).also { cache[category] = Cache(elapsed(), it) }
        releasesInWindow(tracks, window, now())
    }

    private suspend fun fetch(category: ReleaseCategory): List<DatedTrack> = coroutineScope {
        val results = sources.map { source -> async {
            try { Result.success(source(category)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure<List<Track>>(e) }
        } }.awaitAll()
        if (results.isNotEmpty() && results.all { it.isFailure }) {
            throw IOException("New release sources unavailable", results.first().exceptionOrNull())
        }
        val lists = results.map { it.getOrDefault(emptyList()) }
        val candidates = LinkedHashMap<String, Track>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
            for (list in lists) {
                val track = list.getOrNull(i) ?: continue
                if (track.id.isNotBlank() && track.title.isNotBlank()) candidates.putIfAbsent(track.id, track)
                if (candidates.size >= MAX_CANDIDATES) break
            }
            if (candidates.size >= MAX_CANDIDATES) break
        }
        if (candidates.isEmpty() && results.any { it.isFailure }) {
            throw IOException("New release sources unavailable", results.firstOrNull { it.isFailure }?.exceptionOrNull())
        }
        val dated = candidates.values.map { track -> async {
            dates.withPermit {
                try { uploadDate(track)?.takeIf { it > 0 }?.let { DatedTrack(track, it) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
        } }.awaitAll().filterNotNull()
        // A metadata/parser/network failure is not evidence that no songs were released.
        if (candidates.isNotEmpty() && dated.isEmpty()) throw ReleaseDatesUnavailable()
        val time = now()
        dated.filter { it.uploadedAtMs <= time && time - it.uploadedAtMs <= ReleaseWindow.LAST_10_DAYS.durationMs }
    }

    companion object {
        const val CACHE_MS = 30L * 60 * 1000
        const val EMPTY_CACHE_MS = 60_000L
        const val MAX_CANDIDATES = 40

        fun fromMusic(music: MusicRepository): NewReleasesRepository {
            val clock = System::currentTimeMillis
            val dates = MusicUploadDates()
            return NewReleasesRepository(ReleaseDiscovery.fromMusic(music, clock).sources(), dates::fetch, now = clock)
        }
    }
}
