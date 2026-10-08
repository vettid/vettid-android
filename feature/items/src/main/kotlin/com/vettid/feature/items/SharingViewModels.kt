package com.vettid.feature.items

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.items.FetchOutcome
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.ItemsRepository
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.RulePreview
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharedContent
import com.vettid.core.data.items.SharingRepository
import com.vettid.core.data.items.TagMatch
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** The connection's title for the sharing screens (§10.8: "First Last", the alias, or null before the names arrived). */
private fun ConnectionsRepository.nameOf(id: String): String? =
    connections.value.firstOrNull { it.id == id }?.displayName?.takeIf { it.isNotBlank() }

/** Active grants first, then by name. */
private val GRANT_ORDER = compareBy<GrantView>({ !it.active }, { it.name.lowercase() })

// --- what one connection can see ---

/** Immutable UI state of a connection's sharing. */
data class ConnectionSharingUiState(
    val connectionId: String,
    val connectionName: String? = null,
    val rules: List<ShareRule> = emptyList(),
    /** The grants given to this connection (rule and one-off), active ones first. */
    val given: List<GrantView> = emptyList(),
    val items: Map<String, ItemSummary> = emptyMap(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val confirmRevoke: GrantView? = null,
) {
    val active: List<GrantView> get() = given.filter { it.active }

    /** Items the rules include, by id, that the connection can only ask to use (critical, §10.13). */
    val usable: List<String>
        get() = rules.flatMap { it.included }.distinct()
            .filter { items[it]?.sensitivity == com.vettid.core.data.items.Sensitivity.CRITICAL }

    val pendingCount: Int get() = rules.sumOf { it.pending.size }
}

/**
 * What one connection can see (VAULT-ITEMS §6, VAULT-MESSAGING §10.12): its share rules by tag (each with the items it
 * includes, the ones waiting for the member's decision and the declined ones), and the grants given to it (from
 * rules and one-off requests), which the member can revoke. Tags never reach the connection; it sees the items only.
 */
@HiltViewModel
class ConnectionSharingViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val sharing: SharingRepository,
    private val items: ItemsRepository,
    private val connections: ConnectionsRepository,
) : ViewModel() {
    private val id: String = checkNotNull(saved[ConnectionSharingRoute.ARG])
    private val state = MutableStateFlow(ConnectionSharingUiState(id, connections.nameOf(id)))
    val uiState: StateFlow<ConnectionSharingUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { items.items.collect { l -> state.update { s -> s.copy(items = l.associateBy { it.itemId }) } } }
        load()
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val rules = sharing.rules(id)
                val given = sharing.grants().given.filter { it.connectionId == id }.sortedWith(GRANT_ORDER)
                state.update {
                    it.copy(rules = rules, given = given, loading = false, connectionName = connections.nameOf(id) ?: it.connectionName)
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(loading = false, error = e.kind) }
            }
        }
    }

    fun askRevoke(g: GrantView?) = state.update { it.copy(confirmRevoke = g) }

    fun revoke() {
        val g = state.value.confirmRevoke ?: return
        state.update { it.copy(confirmRevoke = null, busy = true) }
        viewModelScope.launch {
            try {
                sharing.revokeGrant(g.grantId)
                state.update { it.copy(busy = false) }
                load()
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }
}

// --- a share rule ---

/** How long a rule lasts (§10.12 `expires_at`, at most 3,650 days ahead). */
@Suppress("MagicNumber") // the presets' days
enum class RuleExpiry(val days: Long?) {
    NEVER(null),
    MONTH(30),
    YEAR(365),

    /** An existing rule's own end, kept as it is. */
    KEEP(null),
}

/** Immutable UI state of the share-rule editor. */
data class RuleEditUiState(
    val draft: RuleDraft,
    val connectionName: String? = null,
    /** The member's tags a rule can name (no reserved tag, §10.12). */
    val tags: List<String> = emptyList(),
    val expiry: RuleExpiry = RuleExpiry.NEVER,
    val usesText: String = "",
    val preview: RulePreview? = null,
    val previewing: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val confirmDelete: Boolean = false,
    val done: Boolean = false,
) {
    val isNew: Boolean get() = draft.ruleId == null

    val usesValid: Boolean get() = usesText.isBlank() || usesText.toIntOrNull()?.let { it in 1..RuleDraft.MAX_USES } == true

    val canSave: Boolean get() = draft.tags.isNotEmpty() && usesValid && !busy
}

/**
 * A share rule for one connection (§10.12): the tags it names (any or all), its mode — "Ask me for each new item"
 * (the default, owner decision 2026-10-03) or "Share automatically" — counted uses, an end, and whether items that
 * already carry the tags count. The vault's dry run shows what it would match before it is saved.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class RuleEditViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val sharing: SharingRepository,
    connections: ConnectionsRepository,
) : ViewModel() {
    private val connectionId: String = checkNotNull(saved[RuleEditRoute.ARG_CONNECTION])
    private val ruleId: String? = saved[RuleEditRoute.ARG_RULE]
    private val state = MutableStateFlow(
        RuleEditUiState(RuleDraft(connectionId), connections.nameOf(connectionId), loading = ruleId != null),
    )
    val uiState: StateFlow<RuleEditUiState> = state.asStateFlow()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val reg = sharing.tags.value ?: sharing.refreshTags()
                state.update { it.copy(tags = reg.tags.filterNot { t -> t.reserved }.map { t -> t.tag }.sorted()) }
                if (ruleId != null) {
                    val r = sharing.rules(connectionId).firstOrNull { it.ruleId == ruleId } ?: throw VaultFailure(FailureKind.NOT_FOUND)
                    state.update {
                        it.copy(
                            draft = RuleDraft.of(r).copy(connectionId = connectionId),
                            expiry = if (r.expiresAt == null) RuleExpiry.NEVER else RuleExpiry.KEEP,
                            usesText = r.uses?.toString().orEmpty(),
                            loading = false,
                            tags = (it.tags + r.tags).distinct().sorted(),
                        )
                    }
                    schedulePreview()
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(loading = false, error = e.kind) }
            }
        }
    }

    private fun change(f: (RuleDraft) -> RuleDraft) {
        state.update { it.copy(draft = f(it.draft), error = null) }
        schedulePreview()
    }

    fun toggleTag(t: String) = change { d -> d.copy(tags = if (t in d.tags) d.tags - t else (d.tags + t).take(RuleDraft.MAX_TAGS)) }

    fun setMatch(m: TagMatch) = change { it.copy(match = m) }

    fun setMode(m: ShareMode) = change { it.copy(mode = m) }

    fun setIncludeExisting(on: Boolean) = change { it.copy(includeExisting = on) }

    fun setUses(v: String) {
        val digits = v.filter { it.isDigit() }.take(USES_DIGITS)
        state.update { it.copy(usesText = digits) }
        change { it.copy(uses = digits.toIntOrNull()) }
    }

    fun setExpiry(e: RuleExpiry, now: Instant = Instant.now()) {
        state.update { it.copy(expiry = e) }
        when (e) {
            RuleExpiry.KEEP -> Unit
            else -> change { it.copy(expiresAt = e.days?.let { d -> now.plus(Duration.ofDays(d)) }) }
        }
    }

    private fun schedulePreview() {
        previewJob?.cancel()
        val d = state.value.draft
        if (d.tags.isEmpty()) return state.update { it.copy(preview = null, previewing = false) }
        state.update { it.copy(previewing = true) }
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            try {
                val p = sharing.preview(state.value.draft)
                state.update { it.copy(preview = p, previewing = false) }
            } catch (e: VaultFailure) {
                state.update { it.copy(previewing = false, error = e.kind) }
            }
        }
    }

    fun save() {
        val s = state.value
        if (!s.canSave) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                sharing.saveRule(s.draft)
                state.update { it.copy(busy = false, done = true) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    fun askDelete(show: Boolean) = state.update { it.copy(confirmDelete = show) }

    fun delete() {
        val id = state.value.draft.ruleId ?: return
        state.update { it.copy(busy = true, confirmDelete = false) }
        viewModelScope.launch {
            try {
                sharing.deleteRule(id)
                state.update { it.copy(busy = false, done = true) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    private companion object {
        const val PREVIEW_DEBOUNCE_MS = 400L
        const val USES_DIGITS = 5
    }
}

// --- shared with you ---

/** Immutable UI state of what a connection shares with the member. */
data class SharedWithYouUiState(
    val connectionId: String,
    val connectionName: String? = null,
    val received: List<GrantView> = emptyList(),
    /** Fetched contents by grant id, in memory while the screen is in front. */
    val opened: Map<String, SharedContent> = emptyMap(),
    /** The connection's vault refused a fetch, by grant id (`revoked`, `expired`, `exhausted`, `unavailable`). */
    val refused: Map<String, String> = emptyMap(),
    val fetching: String? = null,
    val loading: Boolean = true,
    val error: FailureKind? = null,
    val confirmGiveUp: GrantView? = null,
)

/**
 * What a connection shares with the member (§10.12 received grants): read-only and labelled as theirs. A value is
 * fetched on purpose (each fetch can count one of the connection's uses), sealed to this phone for that fetch only,
 * and kept only while the screen is in front.
 */
@HiltViewModel
class SharedWithYouViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val sharing: SharingRepository,
    private val connections: ConnectionsRepository,
) : ViewModel() {
    private val id: String = checkNotNull(saved[SharedWithYouRoute.ARG])
    private val state = MutableStateFlow(SharedWithYouUiState(id, connections.nameOf(id)))
    val uiState: StateFlow<SharedWithYouUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val r = sharing.grants().received.filter { it.connectionId == id }.sortedWith(GRANT_ORDER)
                state.update { it.copy(received = r, loading = false) }
            } catch (e: VaultFailure) {
                state.update { it.copy(loading = false, error = e.kind) }
            }
        }
    }

    fun fetch(grantId: String) {
        if (state.value.fetching != null) return
        state.update { it.copy(fetching = grantId, error = null) }
        viewModelScope.launch {
            try {
                when (val o = sharing.fetchShared(grantId)) {
                    is FetchOutcome.Shared -> state.update {
                        it.copy(opened = it.opened + (grantId to o.content), refused = it.refused - grantId)
                    }
                    is FetchOutcome.Refused -> state.update { it.copy(refused = it.refused + (grantId to o.reason)) }
                }
                state.update { it.copy(fetching = null) }
            } catch (e: VaultFailure) {
                state.update { it.copy(fetching = null, error = e.kind) }
            }
        }
    }

    /** Drops the fetched values (the screen stopped). */
    fun hide() = state.update { it.copy(opened = emptyMap()) }

    fun askGiveUp(g: GrantView?) = state.update { it.copy(confirmGiveUp = g) }

    fun giveUp() {
        val g = state.value.confirmGiveUp ?: return
        state.update { it.copy(confirmGiveUp = null) }
        viewModelScope.launch {
            try {
                sharing.revokeGrant(g.grantId)
                state.update { it.copy(opened = it.opened - g.grantId) }
                load()
            } catch (e: VaultFailure) {
                state.update { it.copy(error = e.kind) }
            }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }
}
