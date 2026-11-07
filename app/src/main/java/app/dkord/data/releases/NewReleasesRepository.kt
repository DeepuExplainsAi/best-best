package app.dkord.data.releases

import app.dkord.core.ItemKind
import app.dkord.core.Track
import app.dkord.data.yt.MusicRepository
import app.dkord.data.yt.SearchFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

/** Shared in-memory cache. Construction and Home composition do not start any network work. */
class NewReleasesRepository(
    private val sources: List<suspend () -> List<Track>>,
    private val uploadDate: suspend (Track) -> Long?,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Cache(val loadedAt: Long, val tracks: List<DatedTrack>)
    private var cache: Cache? = null
    private val mutex = Mutex()
    private val dates = Semaphore(6)

    suspend fun songs(window: ReleaseWindow): List<Track> = mutex.withLock {
        val stored = cache
        val tracks = if (stored != null && elapsed() - stored.loadedAt in 0 until CACHE_MS) stored.tracks
        else fetch().also { cache = Cache(elapsed(), it) }
        releasesInWindow(tracks, window, now())
    }

    private suspend fun fetch(): List<DatedTrack> = coroutineScope {
        val results = sources.map { source -> async {
            try { Result.success(source()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure<List<Track>>(e) }
        } }.awaitAll()
        if (results.isNotEmpty() && results.all { it.isFailure }) {
            throw IOException("New release sources unavailable", results.first().exceptionOrNull())
        }
        // Interleave sources so a large release page cannot crowd out all four song searches.
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
        candidates.values.map { track -> async {
            dates.withPermit {
                try { uploadDate(track)?.takeIf { it > 0 }?.let { DatedTrack(track, it) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } // No reliable upload date: do not guess.
            }
        } }.awaitAll().filterNotNull()
    }

    companion object {
        const val CACHE_MS = 30L * 60 * 1000
        const val MAX_CANDIDATES = 40

        fun fromMusic(music: MusicRepository): NewReleasesRepository {
            val releaseSongs: suspend () -> List<Track> = {
                val page = music.newReleases()
                page.tracks + page.shelves.flatMap { shelf ->
                    shelf.items.filter { it.kind == ItemKind.SONG }.mapNotNull { it.track }
                }
            }
            val searches = listOf("new songs", "new hindi songs", "new punjabi songs", "new bhojpuri songs")
            return NewReleasesRepository(
                sources = listOf(releaseSongs) + searches.map { query ->
                    suspend { music.search(query, SearchFilter.SONGS).items.mapNotNull { it.track } }
                },
                uploadDate = { track -> withContext(Dispatchers.IO) {
                    StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${track.id}")
                        .uploadDate?.instant?.toEpochMilli()
                } },
            )
        }
    }
}
