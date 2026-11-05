package app.dkord.data.stream

import app.dkord.core.DebugLog
import java.util.concurrent.ConcurrentHashMap

/** Tries resolvers in order; throws the most informative error if all fail. */
class FallbackStreamResolver(private val chain: List<StreamResolver>) : StreamResolver {
    override val name = "chain"

    override suspend fun resolve(videoId: String, quality: AudioQuality, preferM4a: Boolean): ResolvedStream {
        val errors = ArrayList<StreamError>()
        for (r in chain) {
            try {
                val s = r.resolve(videoId, quality, preferM4a)
                DebugLog.d("resolve", "$videoId ok via ${r.name} ${s.mimeType} ${s.bitrate / 1000}kbps")
                return s
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: StreamError) {
                DebugLog.w("resolve", "$videoId ${r.name}: ${e.javaClass.simpleName}: ${e.message}")
                errors += e
                // These do not depend on the client: stop trying, the answer won't change.
                if (e is StreamError.Private || e is StreamError.Geo) break
            } catch (e: Exception) {
                DebugLog.e("resolve", "$videoId ${r.name} crashed", e)
                errors += StreamError.Unavailable(e.message.orEmpty())
            }
        }
        throw errors.maxByOrNull { it.priority } ?: StreamError.NoAudio()
    }
}

/** Caches resolved URLs until they expire. Call [invalidate] when playback fails with 403. */
class CachedStreamResolver(private val delegate: StreamResolver) : StreamResolver {
    override val name = "cache"
    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    override suspend fun resolve(videoId: String, quality: AudioQuality, preferM4a: Boolean): ResolvedStream {
        val key = "$videoId|$quality|$preferM4a"
        cache[key]?.takeIf { it.isFresh() }?.let { return it }
        return delegate.resolve(videoId, quality, preferM4a).also { cache[key] = it }
    }

    fun invalidate(videoId: String) { cache.keys.removeAll { it.startsWith("$videoId|") } }
    fun clear() = cache.clear()
}
