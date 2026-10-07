package com.vettid.feature.history

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.vault.AuditCategory
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.AuditRequest
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.HistoryRepository
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** The date presets of the History filter (ANDROID-PLAN 0.1.11); [CUSTOM] is a range the member picked. */
@Suppress("MagicNumber") // the plan's day counts
enum class DatePreset(val days: Long?) {
    ANY(null),
    TODAY(1),
    WEEK(7),
    MONTH(30),
    CUSTOM(null),
}

/** A connection the History filter offers. */
data class ConnectionOption(val id: String, val name: String)

/** Immutable UI state of the History screen. */
data class HistoryUiState(
    val entries: List<AuditRecord> = emptyList(),
    val filter: AuditFilter = AuditFilter(),
    val datePreset: DatePreset = DatePreset.ANY,
    /** id → title of the member's connections, for the rows and the connection filter. */
    val connectionNames: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val end: Boolean = false,
    val localSearch: Boolean = false,
    /** The vault's search ran out of its budget on the last page and is going on with older entries. */
    val partial: Boolean = false,
    /** The unfiltered log did not chain (§10.9): the red banner. */
    val chainBroken: Boolean = false,
    val error: FailureKind? = null,
) {
    val connections: List<ConnectionOption>
        get() = connectionNames.map { (id, name) -> ConnectionOption(id, name) }.sortedBy { it.name.lowercase() }
}

/** Builds the filter of the History screen from the member's choices (kept pure for tests). */
object HistoryFilters {
    /** One category at a time: choosing the selected one again (or null) is "All". */
    fun category(f: AuditFilter, c: AuditCategory?): AuditFilter = f.copy(category = c?.takeIf { it != f.category })

    /**
     * The range of [preset] in [zone] (ANDROID-PLAN 0.1.11): `since` the start of the first day (today, 7 or 30
     * days including today), no `until`; ANY clears it; CUSTOM takes [since] and [until] as picked.
     */
    fun dates(
        f: AuditFilter,
        preset: DatePreset,
        now: Instant,
        zone: ZoneId,
        since: Instant? = null,
        until: Instant? = null,
    ): AuditFilter = when (preset) {
        DatePreset.ANY -> f.copy(since = null, until = null)
        DatePreset.CUSTOM -> f.copy(since = since, until = until)
        else -> {
            val today = now.atZone(zone).toLocalDate()
            f.copy(since = today.minusDays(preset.days!! - 1).atStartOfDay(zone).toInstant(), until = null)
        }
    }

    /** A custom range of whole local days: from the start of [first] to the start of the day after [last]. */
    fun days(first: LocalDate, last: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
        val (a, b) = if (last.isBefore(first)) last to first else first to last
        return a.atStartOfDay(zone).toInstant() to b.plusDays(1).atStartOfDay(zone).toInstant()
    }
}

/**
 * History (ANDROID-PLAN 0.1.11): the member's audit log through `audit.list` (VAULT-MESSAGING 0.20.0 §10.9), newest
 * first, read in pages as the list scrolls; searched by the vault (`q`), filtered by one category, a connection and
 * dates. Read-only. Opened from a connection's detail, the connection is preset ([ConnectionHistoryRoute]).
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val history: HistoryRepository,
    private val connections: ConnectionsRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val state = MutableStateFlow(HistoryUiState(filter = AuditFilter(connectionId = saved.get<String>("connectionId"))))
    val uiState: StateFlow<HistoryUiState> = state.asStateFlow()

    private var known: Map<String, ConnectionInfo> = emptyMap()
    private val pager = HistoryPager(history, localText = { localText(it) })
    private var job: Job? = null

    init {
        viewModelScope.launch {
            connections.connections.collect { list ->
                known = list.associateBy { it.id }
                state.update { it.copy(connectionNames = names(list)) }
            }
        }
        viewModelScope.launch { runCatching { connections.refresh() } }
        reload()
    }

    private fun names(list: List<ConnectionInfo>): Map<String, String> =
        list.associate { c -> c.id to c.displayName.ifEmpty { context.getString(R.string.history_connection_unnamed) } }

    /**
     * What this phone can search when the vault does not (§10.9's fields it holds): the kind in both forms, the
     * connection's names and alias, and the entry's title in the app's language.
     */
    private fun localText(r: AuditRecord): List<String> {
        val c = r.connectionId?.let { known[it] }
        return AuditFilter.kindText(r.kind) + listOfNotNull(
            HistoryText.title(context, r.kind),
            c?.name, c?.alias, c?.firstName, c?.lastName, c?.accountName,
        )
    }

    fun setCategory(c: AuditCategory?) = setFilter(HistoryFilters.category(state.value.filter, c))

    fun setConnection(id: String?) = setFilter(state.value.filter.copy(connectionId = id))

    fun setDates(preset: DatePreset, since: Instant? = null, until: Instant? = null) {
        state.update { it.copy(datePreset = preset) }
        setFilter(HistoryFilters.dates(state.value.filter, preset, Instant.now(), ZoneId.systemDefault(), since, until))
    }

    fun setQuery(q: String) {
        val before = state.value.filter
        val f = before.copy(query = q)
        state.update { it.copy(filter = f) }
        // Only a change of what is sent (trimmed, NFC, at most 128 bytes) restarts the list.
        if (f.q != before.q) reload(debounce = true)
    }

    fun clearFilters() {
        state.update { it.copy(datePreset = DatePreset.ANY) }
        setFilter(AuditFilter())
    }

    fun retry() {
        if (state.value.entries.isEmpty()) {
            reload()
        } else {
            state.update { it.copy(error = null) }
            loadMore()
        }
    }

    private fun setFilter(f: AuditFilter) {
        if (f == state.value.filter) return
        state.update { it.copy(filter = f) }
        reload()
    }

    private fun reload(debounce: Boolean = false) {
        job?.cancel()
        state.update { it.copy(loading = true, error = null, entries = if (debounce) it.entries else emptyList()) }
        job = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            run { pager.reset(state.value.filter) }
        }
    }

    /** The list's end came into view: the next page, unless one is loading or the results have no more. */
    fun loadMore() {
        val s = state.value
        val busy = s.loading || s.loadingMore
        if (busy || s.end || s.error != null) return
        state.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch { run { pager.more() } }
    }

    private suspend fun run(block: suspend () -> HistoryPager.Snapshot) {
        try {
            val snap = block()
            state.update {
                it.copy(
                    entries = snap.entries, end = snap.end, localSearch = snap.localSearch, partial = snap.partial,
                    chainBroken = it.chainBroken || snap.chainBroken, loading = false, loadingMore = false, error = null,
                )
            }
        } catch (e: VaultFailure) {
            state.update { it.copy(loading = false, loadingMore = false, error = e.kind) }
        }
    }

    private companion object {
        /** ANDROID-PLAN 0.1.11: the search is sent after a 300 ms pause. */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

/** Immutable UI state of an entry's detail. */
data class HistoryEntryUiState(
    val entry: AuditRecord? = null,
    val connectionName: String? = null,
    /** The entry's connection is still in the vault's list (the detail opens it). */
    val connectionExists: Boolean = false,
    val loading: Boolean = true,
    val missing: Boolean = false,
    val error: FailureKind? = null,
)

/** One audit entry (read-only): from the entries History read, else `audit.list{before_seq: seq + 1, limit: 1}`. */
@HiltViewModel
class HistoryEntryViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val history: HistoryRepository,
    private val connections: ConnectionsRepository,
) : ViewModel() {
    private val seq = saved.toRoute<HistoryEntryRoute>().seq
    private val state = MutableStateFlow(HistoryEntryUiState())
    val uiState: StateFlow<HistoryEntryUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val e = history.cached(seq)
                    ?: history.auditPage(AuditRequest(beforeSeq = seq + 1, limit = 1)).entries.firstOrNull { it.seq == seq }
                val c = e?.connectionId?.let { id -> connections.connections.value.firstOrNull { it.id == id } }
                state.update {
                    it.copy(entry = e, connectionName = c?.displayName, connectionExists = c != null, loading = false, missing = e == null)
                }
            } catch (f: VaultFailure) {
                state.update { it.copy(loading = false, error = f.kind) }
            }
        }
    }
}
