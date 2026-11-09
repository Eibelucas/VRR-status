package de.luca.mitfahrt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class TripPaging { NONE, EARLIER, LATER }

data class TripResultsState(
    val query: TripQuery? = null,
    val journeys: List<TripJourney> = emptyList(),
    val loading: Boolean = false,
    val paging: TripPaging = TripPaging.NONE,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** Error of an earlier/later or refresh request; existing results stay visible. */
    val pageError: String? = null,
    /** Time of the newest successful answer for this query. */
    val fetchedAt: Instant? = null,
    val fromCache: Boolean = false,
    val noEarlier: Boolean = false,
    val noLater: Boolean = false
) {
    val searched: Boolean get() = query != null && !loading && error == null
    val empty: Boolean get() = searched && journeys.isEmpty()
}

/**
 * Runs journey searches. A new search cancels the running one; every answer is checked
 * against the current generation, so a late answer of an older search is never shown.
 */
class TripSearchEngine(
    private val scope: CoroutineScope,
    private val fetch: suspend (TripQuery, TripPage) -> List<TripJourney>,
    private val clock: () -> Instant = Instant::now,
    private val cacheSeconds: Long = 60,
    private val maxJourneys: Int = 30
) {
    private val mutable = MutableStateFlow(TripResultsState())
    val state: StateFlow<TripResultsState> = mutable.asStateFlow()

    private var generation = 0L
    private var job: Job? = null
    private var pageJob: Job? = null
    private val cache = object : LinkedHashMap<String, Pair<Instant, List<TripJourney>>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Instant, List<TripJourney>>>?) = size > 12
    }

    @Synchronized fun search(query: TripQuery, force: Boolean = false) {
        val current = ++generation
        job?.cancel(); pageJob?.cancel()
        val page = TripPage(query.mode, query.at)
        val key = query.key()
        val cached = synchronized(cache) { cache[key] }?.takeIf { !force && clock().epochSecond - it.first.epochSecond in 0..cacheSeconds }
        if (cached != null) {
            mutable.value = TripResultsState(query, cached.second, fetchedAt = cached.first, fromCache = true)
            return
        }
        mutable.value = TripResultsState(query, loading = true)
        job = scope.launch {
            val result = runCatching { fetch(query, page) }
            if (result.exceptionOrNull() is CancellationException) return@launch
            commit(current) {
                result.fold(
                    onSuccess = { journeys ->
                        val sorted = order(journeys)
                        val now = clock()
                        synchronized(cache) { cache[key] = now to sorted }
                        TripResultsState(query, sorted, fetchedAt = now)
                    },
                    onFailure = { TripResultsState(query, error = message(it)) }
                )
            }
        }
    }

    /** Clears results and cancels everything, e.g. when start or destination becomes incomplete. */
    @Synchronized fun clear() {
        generation++
        job?.cancel(); pageJob?.cancel()
        mutable.value = TripResultsState()
    }

    @Synchronized fun earlier() = page(TripPaging.EARLIER)
    @Synchronized fun later() = page(TripPaging.LATER)

    private fun page(direction: TripPaging) {
        val snapshot = mutable.value
        val query = snapshot.query ?: return
        if (snapshot.loading || snapshot.paging != TripPaging.NONE || snapshot.journeys.isEmpty()) return
        val current = generation
        mutable.update { it.copy(paging = direction, pageError = null) }
        pageJob = scope.launch {
            val result = runCatching {
                val known = snapshot.journeys.map { it.key }.toSet()
                var added = emptyList<TripJourney>()
                // Same minute first (several journeys may leave at once), then one minute further.
                for (shift in listOf(0L, 60L)) {
                    val page = if (direction == TripPaging.LATER) {
                        val last = snapshot.journeys.mapNotNull { it.plannedDeparture }.maxOrNull() ?: break
                        TripPage(TripTimeMode.DEPART, last.plusSeconds(shift))
                    } else {
                        val first = snapshot.journeys.mapNotNull { it.plannedArrival }.minOrNull() ?: break
                        TripPage(TripTimeMode.ARRIVE, first.minusSeconds(shift))
                    }
                    added = fetch(query, page).filter { it.key !in known }
                    if (added.isNotEmpty()) break
                }
                added
            }
            if (result.exceptionOrNull() is CancellationException) return@launch
            commit(current) { state ->
                result.fold(
                    onSuccess = { added ->
                        val merged = order(state.journeys + added)
                        val trimmed = if (merged.size <= maxJourneys) merged
                            else if (direction == TripPaging.LATER) merged.takeLast(maxJourneys) else merged.take(maxJourneys)
                        state.copy(journeys = trimmed, paging = TripPaging.NONE,
                            noEarlier = state.noEarlier || direction == TripPaging.EARLIER && added.isEmpty(),
                            noLater = state.noLater || direction == TripPaging.LATER && added.isEmpty())
                    },
                    onFailure = { state.copy(paging = TripPaging.NONE, pageError = message(it)) }
                )
            }
        }
    }

    /**
     * Updates live data of the shown journeys. Journeys that VRR no longer returns keep
     * their own (older) fetch time so the screen can show their real data age.
     */
    @Synchronized fun refresh() {
        val snapshot = mutable.value
        val query = snapshot.query ?: return
        if (snapshot.loading || snapshot.refreshing || snapshot.paging != TripPaging.NONE) return
        if (snapshot.journeys.isEmpty()) { search(query, force = true); return }
        val current = generation
        val first = snapshot.journeys.mapNotNull { it.plannedDeparture }.minOrNull() ?: return
        mutable.update { it.copy(refreshing = true, pageError = null) }
        pageJob = scope.launch {
            val result = runCatching { fetch(query, TripPage(TripTimeMode.DEPART, first, (snapshot.journeys.size + 1).coerceAtMost(8))) }
            if (result.exceptionOrNull() is CancellationException) return@launch
            commit(current) { state ->
                result.fold(
                    onSuccess = { fresh ->
                        val byKey = fresh.associateBy { it.key }
                        val updated = state.journeys.map { byKey[it.key] ?: it }
                        val now = clock()
                        state.copy(journeys = order(updated), refreshing = false, fetchedAt = now, fromCache = false)
                    },
                    onFailure = { state.copy(refreshing = false, pageError = message(it)) }
                )
            }
        }
    }

    private fun commit(expected: Long, change: (TripResultsState) -> TripResultsState) {
        synchronized(this) {
            if (expected != generation) return
            mutable.update(change)
        }
    }

    private fun order(journeys: List<TripJourney>): List<TripJourney> = journeys.distinctBy { it.key }
        .sortedWith(compareBy<TripJourney> { it.plannedDeparture ?: Instant.MAX }.thenBy { it.plannedArrival ?: Instant.MAX })

    private fun message(error: Throwable): String = when (error) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "Keine Verbindung zum VRR. Prüfe dein Internet."
        is TripSearchException -> error.message ?: "Die Suche ist fehlgeschlagen."
        else -> "Der VRR hat die Suche nicht beantwortet."
    }
}

class TripSearchException(message: String) : Exception(message)
