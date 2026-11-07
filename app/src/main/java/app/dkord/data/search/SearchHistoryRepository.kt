package app.dkord.data.search

import app.dkord.data.yt.SearchFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface SearchHistoryStorage {
    val entries: Flow<List<SavedSearch>>
    suspend fun update(transform: (List<SavedSearch>) -> List<SavedSearch>)
    /** Clear must also work if the old document is corrupt or from an unsupported version. */
    suspend fun clear()
}

data class SearchHistoryState(val entries: List<SavedSearch> = emptyList(), val loading: Boolean = true, val loadFailed: Boolean = false)
enum class SearchHistoryNotice { WRITE_FAILED, PIN_LIMIT }

/** App-lifetime, ordered writes: Clear cannot be undone by an older pending record/pin operation. */
class SearchHistoryRepository(
    private val storage: SearchHistoryStorage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private sealed interface Command {
        data class Record(val query: String, val filter: SearchFilter) : Command
        data class Remove(val key: String) : Command
        data class Pin(val key: String, val pinned: Boolean) : Command
        data object Clear : Command
        data object Reload : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val messages = Channel<SearchHistoryNotice>(Channel.BUFFERED)
    private val mutableState = MutableStateFlow(SearchHistoryState())
    val state: StateFlow<SearchHistoryState> = mutableState.asStateFlow()
    val notices: Flow<SearchHistoryNotice> = messages.receiveAsFlow()
    private var reader: Job? = null
    private val readerLock = Any()
    private var readGeneration = 0L

    init {
        restartReader()
        scope.launch {
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Record -> storage.update { rememberSearch(it, command.query, command.filter) }
                        is Command.Remove -> storage.update { entries -> entries.filterNot { it.key == command.key } }
                        is Command.Pin -> storage.update { entries ->
                            val entry = entries.firstOrNull { it.key == command.key }
                            // A second click on an old "Pin" menu must not accidentally unpin it.
                            if (entry == null || entry.pinned == command.pinned) entries else toggleSearchPin(entries, command.key)
                        }
                        Command.Clear -> storage.clear()
                        Command.Reload -> restartReader()
                    }
                    // Clear can finish before an older document's decoder reports its failure.
                    if (command == Command.Clear) restartReader(clearEntries = true)
                    else if (command != Command.Reload && state.value.loadFailed) restartReader()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SearchPinLimitException) {
                    messages.trySend(SearchHistoryNotice.PIN_LIMIT)
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    messages.trySend(SearchHistoryNotice.WRITE_FAILED)
                }
            }
        }
    }

    fun record(query: String, filter: SearchFilter) {
        val normalized = normalizeSearchQuery(query)
        if (normalized.isNotBlank()) commands.trySend(Command.Record(normalized, filter))
    }
    fun remove(entry: SavedSearch) { commands.trySend(Command.Remove(entry.key)) }
    fun togglePinned(entry: SavedSearch) { commands.trySend(Command.Pin(entry.key, !entry.pinned)) }
    fun clearAll() { commands.trySend(Command.Clear) }

    fun reload() { commands.trySend(Command.Reload) }

    private fun restartReader(clearEntries: Boolean = false) = synchronized(readerLock) {
        val generation = ++readGeneration
        reader?.cancel()
        mutableState.update { if (clearEntries) SearchHistoryState() else it.copy(loading = true, loadFailed = false) }
        reader = scope.launch {
            try {
                storage.entries.collect { entries ->
                    synchronized(readerLock) {
                        if (generation == readGeneration) mutableState.value = SearchHistoryState(entries, loading = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                synchronized(readerLock) {
                    if (generation == readGeneration) mutableState.update { it.copy(loading = false, loadFailed = true) }
                }
            }
        }
    }
}
