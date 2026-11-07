package app.dkord

import app.dkord.data.search.SavedSearch
import app.dkord.data.search.SearchHistoryCodec
import app.dkord.data.search.SearchHistoryStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** JSON round-trips on each write, like the production document; no Android/network dependency. */
internal class MemorySearchStorage(seed: List<SavedSearch> = emptyList()) : SearchHistoryStorage {
    val raw = MutableStateFlow<String?>(SearchHistoryCodec.encode(seed))
    var failRead = false
    var failWrite = false
    var readDelay = 0L
    var writeDelay = 0L
    var writes = 0
    private val mutex = Mutex()
    override val entries = flow {
        if (failRead) throw IOException("read unavailable")
        raw.map { delay(readDelay); SearchHistoryCodec.decode(it) }.collect { emit(it) }
    }
    override suspend fun update(transform: (List<SavedSearch>) -> List<SavedSearch>) {
        mutex.withLock {
            delay(writeDelay)
            if (failWrite) throw IOException("write unavailable")
            raw.value = SearchHistoryCodec.encode(transform(SearchHistoryCodec.decode(raw.value)))
            writes++
        }
    }
    override suspend fun clear() {
        mutex.withLock {
            delay(writeDelay)
            if (failWrite) throw IOException("clear unavailable")
            raw.value = null
            writes++
        }
    }
}
