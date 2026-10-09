package com.vettid.feature.items

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.items.FetchOutcome
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.ItemsRepository
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.RuleOverlaps
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

/** The connection's title for the sharing screens: its display name (the account's "First Last", or the placeholder), null when blank. */
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
    /** The limit a `limit` error named (VAULT-MESSAGING 0.21.0 §10.1). */
    val limit: com.vettid.core.data.vault.VaultLimit? = null,
    val confirmRevoke: GrantView? = null,
) {
    val active: List<GrantView> get() = given.filter { it.active }

    /** Items the rules include, by id, that the connection can only ask to use (critical, §10.13). */
    val usable: List<String>
        get() = rules.flatMap { it.included }.distinct()
            .filter { items[it]?.sensitivity == com.vettid.core.data.items.Sensitivity.CRITICAL }

    val pendingCount: Int get() = rules.sumOf { it.pending.size }

    /** Each rule's id → the other rules of this connection covering the same tags or items (§10.12). */
    val overlaps: Map<String, List<ShareRule>> get() = RuleOverlaps.of(rules)

    /** The connection has the most rules it can have (§10.12 `share_rules_subject`, 64): no new one. */
    val atRuleLimit: Boolean get() = rules.size >= com.vettid.core.data.items.RuleDraft.MAX_RULES_PER_SUBJECT
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
                state.update { it.copy(loading = false, error = e.kind, limit = e.limit) }
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
                state.update { it.copy(busy = false, error = e.kind, limit = e.limit) }
            }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }
}

// --- a share rule ---

/**
 * How long a rule lasts (§10.12 `expires_at`: in the future, at most 3,650 days ahead; absent, until deleted). The
 * presets count from now in the member's time zone; [CUSTOM] is a date and time the member picked.
 */
enum class RuleExpiry {
    NEVER,
    DAY,
    WEEK,
    MONTH,
    THREE_MONTHS,
    YEAR,

    /** A date and time the member picked with the date and time pickers (local time, sent as UTC). */
    CUSTOM,

    /** An existing rule's own end, kept as it is. */
    KEEP,
    ;

    /** The end this preset gives from [now] in [zone]; null for [NEVER] (and for [CUSTOM] and [KEEP], which carry their own). */
    @Suppress("MagicNumber") // three months
    fun endFrom(now: Instant, zone: ZoneId): Instant? {
        val z = now.atZone(zone)
        return when (this) {
            DAY -> z.plusDays(1)
            WEEK -> z.plusWeeks(1)
            MONTH -> z.plusMonths(1)
            THREE_MONTHS -> z.plusMonths(3)
            YEAR -> z.plusYears(1)
            NEVER, CUSTOM, KEEP -> null
        }?.toInstant()
    }

    companion object {
        /** The choices offered, in order ("Custom…" after them). */
        val PRESETS: List<RuleExpiry> = listOf(NEVER, DAY, WEEK, MONTH, THREE_MONTHS, YEAR)
    }
}

/** The custom end of a rule (§10.12 `expires_at`): a day from the Material3 date picker and a time in the member's zone. */
object RuleEnds {
    /** The date picker's day (UTC midnight of that day, as Material3 gives it) at [hour]:[minute] in [zone], as an instant. */
    fun of(dateUtcMillis: Long, hour: Int, minute: Int, zone: ZoneId): Instant =
        LocalDateTime.of(day(dateUtcMillis), LocalTime.of(hour, minute)).atZone(zone).toInstant()

    /** Whether [at] can end a rule: in the future and at most 3,650 days ahead (§10.12). */
    fun allowed(at: Instant, now: Instant): Boolean = at.isAfter(now) && at.isBefore(now.plus(Duration.ofDays(RuleDraft.MAX_EXPIRY_DAYS)))

    /** Whether the date picker offers this day: today (in [zone]) up to the last day 3,650 days ahead. */
    fun selectable(dateUtcMillis: Long, now: Instant, zone: ZoneId): Boolean {
        val d = day(dateUtcMillis)
        val today = now.atZone(zone).toLocalDate()
        val last = now.plus(Duration.ofDays(RuleDraft.MAX_EXPIRY_DAYS)).atZone(zone).toLocalDate()
        return !d.isBefore(today) && !d.isAfter(last)
    }

    /** The day the date picker shows for [at] in [zone] (UTC midnight millis). */
    fun pickerDay(at: Instant, zone: ZoneId): Long = at.atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun day(dateUtcMillis: Long): LocalDate = Instant.ofEpochMilli(dateUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
}

/** The custom end being picked: the day first, then the time on it. */
sealed interface EndPicker {
    data object Date : EndPicker

    data class Time(val dateUtcMillis: Long) : EndPicker
}

/** Immutable UI state of the share-rule editor. */
data class RuleEditUiState(
    val draft: RuleDraft,
    val connectionName: String? = null,
    /** The member's tags a rule can name (no reserved tag, §10.12). */
    val tags: List<String> = emptyList(),
    val expiry: RuleExpiry = RuleExpiry.NEVER,
    val usesText: String = "",
    /** §10.12 (0.23.0): fetches of all the rule's items together per hour (1–3,600) and per day (1–86,400); empty: none. */
    val perHourText: String = "",
    val perDayText: String = "",
    val preview: RulePreview? = null,
    val previewing: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    /** The limit a `limit` error named (VAULT-MESSAGING 0.21.0 §10.1). */
    val limit: com.vettid.core.data.vault.VaultLimit? = null,
    val confirmDelete: Boolean = false,
    val done: Boolean = false,
    /** Every rule of this connection as last listed (the edited one included). */
    val rules: List<ShareRule> = emptyList(),
    /** The custom end's pickers, while open. */
    val endPicker: EndPicker? = null,
    /** The custom end picked was not in the future or more than 3,650 days ahead. */
    val endInvalid: Boolean = false,
    /** Something was changed since the rule was opened: leaving asks "Discard changes to this rule?". */
    val dirty: Boolean = false,
    val confirmDiscard: Boolean = false,
    /**
     * A rule naming several tags (made before one tag per rule, owner decision 2026-10-09): shown as it is and can
     * be deleted, not changed, so that nothing it shares changes by surprise. A rule per tag replaces it.
     */
    val readOnly: Boolean = false,
) {
    val isNew: Boolean get() = draft.ruleId == null

    val usesValid: Boolean get() = usesText.isBlank() || usesText.toIntOrNull()?.let { it in 1..RuleDraft.MAX_USES } == true

    val perHourValid: Boolean
        get() = perHourText.isBlank() || perHourText.toIntOrNull()?.let { it in 1..RuleDraft.MAX_PER_HOUR } == true

    val perDayValid: Boolean
        get() = perDayText.isBlank() || perDayText.toIntOrNull()?.let { it in 1..RuleDraft.MAX_PER_DAY } == true

    /** A new rule while the connection has the most it can have (§10.12 `share_rules_subject`, 64). */
    val atRuleLimit: Boolean get() = isNew && rules.size >= RuleDraft.MAX_RULES_PER_SUBJECT

    /** The one tag chosen (one tag per rule, owner decision 2026-10-09). */
    val tag: String? get() = draft.tags.singleOrNull()

    /** Tags another rule of this connection already shares on its own, → that rule: "Already shared — edit its rule". */
    val taken: Map<String, String>
        get() = rules.filter { it.ruleId != draft.ruleId && it.tags.size == 1 }.associate { it.tags[0] to it.ruleId }

    /** The tags this rule can take. */
    val freeTags: List<String> get() = tags.filter { it !in taken }

    /** Save is offered once a tag is chosen and every limit reads. */
    val canSave: Boolean
        get() = !readOnly && tag != null && usesValid && perHourValid && perDayValid && !busy && !atRuleLimit && !loading

    /** The connection's other rules covering the same tags or matched items (§10.12 Overlapping rules: `ask` wins). */
    val overlaps: List<ShareRule>
        get() = RuleOverlaps.forDraft(draft.ruleId, draft.tags, preview?.matches?.map { it.itemId }.orEmpty(), rules)

    /** How each rule of the connection is named (§10.12: by its tags). */
    val names: Map<String, com.vettid.core.data.items.RuleNames.Name> get() = com.vettid.core.data.items.RuleNames.of(rules)
}

/**
 * One share rule for one connection (§10.12; a connection has up to 64, each with its own settings): the one tag it
 * shares (owner decision 2026-10-09: one tag per rule, a tag another rule already shares opens that rule), its mode —
 * "Ask me each time" (the default, owner decision 2026-10-03) or "Share automatically" — fetches of each item, and
 * since 0.23.0 the fetches per hour and per day of all its items together, an end (presets or a date and time picked),
 * and whether items that already carry the tag count. The vault's dry run shows what it would match and what saving
 * does to each item (`outcome`, and the `ask` rule that holds an item, `ask_rule_id`: `ask` wins); the connection's
 * other rules covering the same items are shown before it is saved. Saving is "Save rule" in the top bar; leaving with
 * changes asks first.
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
    private val state = MutableStateFlow(RuleEditUiState(RuleDraft(connectionId), connections.nameOf(connectionId), loading = true))
    val uiState: StateFlow<RuleEditUiState> = state.asStateFlow()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val reg = sharing.tags.value ?: sharing.refreshTags()
                state.update { it.copy(tags = reg.tags.filterNot { t -> t.reserved }.map { t -> t.tag }.sorted()) }
                val rules = sharing.rules(connectionId).filter { it.connectionId == connectionId }
                state.update { it.copy(rules = rules, loading = ruleId != null) }
                if (ruleId != null) {
                    val r = rules.firstOrNull { it.ruleId == ruleId } ?: throw VaultFailure(FailureKind.NOT_FOUND)
                    state.update {
                        it.copy(
                            draft = RuleDraft.of(r).copy(connectionId = connectionId),
                            expiry = if (r.expiresAt == null) RuleExpiry.NEVER else RuleExpiry.KEEP,
                            usesText = r.uses?.toString().orEmpty(),
                            perHourText = r.perHour?.toString().orEmpty(),
                            perDayText = r.perDay?.toString().orEmpty(),
                            loading = false,
                            tags = (it.tags + r.tags).distinct().sorted(),
                            readOnly = r.multiTag,
                        )
                    }
                    schedulePreview()
                } else {
                    state.update { it.copy(loading = false) }
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(loading = false, error = e.kind, limit = e.limit) }
            }
        }
    }

    private fun change(f: (RuleDraft) -> RuleDraft) {
        if (state.value.readOnly) return
        state.update { it.copy(draft = f(it.draft), error = null, dirty = true) }
        schedulePreview()
    }

    /**
     * Chooses the one tag the rule shares (owner decision 2026-10-09). A tag another rule of this connection already
     * shares is not taken here: the screen opens that rule instead.
     */
    fun selectTag(t: String) {
        val s = state.value
        if (t in s.taken || t == s.tag) return
        change { d -> d.copy(tags = listOf(t), match = TagMatch.ANY) }
    }

    fun setMode(m: ShareMode) = change { it.copy(mode = m) }

    fun setIncludeExisting(on: Boolean) = change { it.copy(includeExisting = on) }

    /** Fetches of each item (§10.12 `uses`, 1–10,000; empty for none): digits only. */
    fun setUses(v: String) {
        val digits = v.filter { it.isDigit() }.take(USES_DIGITS)
        state.update { it.copy(usesText = digits) }
        change { it.copy(uses = digits.toIntOrNull()) }
    }

    /** Fetches per hour of all the rule's items (0.23.0 §10.12 `per_hour`, 1–3,600; empty: no limit). */
    fun setPerHour(v: String) {
        val digits = v.filter { it.isDigit() }.take(PER_HOUR_DIGITS)
        state.update { it.copy(perHourText = digits) }
        change { it.copy(perHour = digits.toIntOrNull()) }
    }

    /** Fetches per day of all the rule's items (0.23.0 §10.12 `per_day`, 1–86,400; empty: no limit). */
    fun setPerDay(v: String) {
        val digits = v.filter { it.isDigit() }.take(PER_DAY_DIGITS)
        state.update { it.copy(perDayText = digits) }
        change { it.copy(perDay = digits.toIntOrNull()) }
    }

    /** A preset end from [now] in [zone]; [RuleExpiry.CUSTOM] opens the date picker (the end changes once a time is picked). */
    fun setExpiry(e: RuleExpiry, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        if (state.value.readOnly) return
        when (e) {
            RuleExpiry.KEEP -> state.update { it.copy(expiry = e, endInvalid = false) }
            RuleExpiry.CUSTOM -> state.update { it.copy(endPicker = EndPicker.Date, endInvalid = false) }
            else -> {
                state.update { it.copy(expiry = e, endInvalid = false) }
                change { it.copy(expiresAt = e.endFrom(now, zone)) }
            }
        }
    }

    /** The custom end's day was picked (UTC midnight millis, as the date picker gives it): now the time. */
    fun pickEndDate(dateUtcMillis: Long) = state.update { it.copy(endPicker = EndPicker.Time(dateUtcMillis)) }

    /**
     * The custom end's time was picked: the day at [hour]:[minute] in [zone] becomes the end, sent as UTC (§10.12). An
     * end not in the future, or more than 3,650 days ahead, is refused ([RuleEditUiState.endInvalid]) and the end stays.
     */
    fun pickEndTime(hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()) {
        val p = state.value.endPicker as? EndPicker.Time ?: return
        val at = RuleEnds.of(p.dateUtcMillis, hour, minute, zone)
        if (!RuleEnds.allowed(at, now)) return state.update { it.copy(endPicker = null, endInvalid = true) }
        state.update { it.copy(endPicker = null, endInvalid = false, expiry = RuleExpiry.CUSTOM) }
        change { it.copy(expiresAt = at) }
    }

    fun cancelEndPicker() = state.update { it.copy(endPicker = null) }

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
                state.update { it.copy(previewing = false, error = e.kind, limit = e.limit) }
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
                state.update { it.copy(busy = false, done = true, dirty = false) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind, limit = e.limit) }
            }
        }
    }

    /** Back or up with changes: "Discard changes to this rule?" (shown), or not. */
    fun askDiscard(show: Boolean) = state.update { it.copy(confirmDiscard = show) }

    fun askDelete(show: Boolean) = state.update { it.copy(confirmDelete = show) }

    fun delete() {
        val id = state.value.draft.ruleId ?: return
        state.update { it.copy(busy = true, confirmDelete = false) }
        viewModelScope.launch {
            try {
                sharing.deleteRule(id)
                state.update { it.copy(busy = false, done = true, dirty = false) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind, limit = e.limit) }
            }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    private companion object {
        const val PREVIEW_DEBOUNCE_MS = 400L
        const val USES_DIGITS = 5
        const val PER_HOUR_DIGITS = 4
        const val PER_DAY_DIGITS = 5
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
    /** The connection's vault refused a fetch, by grant id (`revoked`, `expired`, `exhausted`, `unavailable`, `rate_limited`). */
    val refused: Map<String, String> = emptyMap(),
    /** A `rate_limited` refusal's end (0.23.0 §10.12 `retry_after`), by grant id: "Try again in …". */
    val retryAt: Map<String, Instant> = emptyMap(),
    val fetching: String? = null,
    val loading: Boolean = true,
    val error: FailureKind? = null,
    /** The limit a `limit` error named (VAULT-MESSAGING 0.21.0 §10.1). */
    val limit: com.vettid.core.data.vault.VaultLimit? = null,
    val confirmGiveUp: GrantView? = null,
    /** Requests this vault made of the connection (§10.12 `grant.list` `requested`). */
    val requested: List<com.vettid.core.data.items.GrantAsk> = emptyList(),
    /** The "ask for something" form, while open. */
    val ask: GrantAskForm? = null,
    val asked: Boolean = false,
)

/** Asking a connection for "your <category>" (§10.12 `grant.request`, a category entry they answer). */
data class GrantAskForm(val category: String = "other", val label: String = "", val reason: String = "", val busy: Boolean = false)

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
    /** The clock for a refusal's `retry_after` (tests set it). */
    internal var now: () -> Instant = Instant::now

    private val id: String = checkNotNull(saved[SharedWithYouRoute.ARG])
    private val state = MutableStateFlow(SharedWithYouUiState(id, connections.nameOf(id)))
    val uiState: StateFlow<SharedWithYouUiState> = state.asStateFlow()

    init {
        load()
        if (saved.get<Boolean>(SharedWithYouRoute.ARG_ASK) == true) openAsk(true)
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val g = sharing.grants()
                val r = g.received.filter { it.connectionId == id }.sortedWith(GRANT_ORDER)
                state.update { it.copy(received = r, requested = g.requested.filter { a -> a.connectionId == id }, loading = false) }
            } catch (e: VaultFailure) {
                state.update { it.copy(loading = false, error = e.kind, limit = e.limit) }
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
                        it.copy(opened = it.opened + (grantId to o.content), refused = it.refused - grantId, retryAt = it.retryAt - grantId)
                    }
                    is FetchOutcome.Refused -> state.update {
                        val until = o.retryAfter?.let { s -> now().plusSeconds(s) }
                        it.copy(
                            refused = it.refused + (grantId to o.reason),
                            retryAt = if (until != null) it.retryAt + (grantId to until) else it.retryAt - grantId,
                        )
                    }
                }
                state.update { it.copy(fetching = null) }
            } catch (e: VaultFailure) {
                state.update { it.copy(fetching = null, error = e.kind, limit = e.limit) }
            }
        }
    }

    fun openAsk(show: Boolean) = state.update { it.copy(ask = if (show) GrantAskForm() else null, asked = false) }

    fun setAsk(f: GrantAskForm) = state.update { it.copy(ask = f) }

    fun sendAsk() {
        val f = state.value.ask ?: return
        state.update { it.copy(ask = f.copy(busy = true), error = null) }
        viewModelScope.launch {
            try {
                sharing.requestGrant(id, f.category, f.label, f.reason)
                state.update { it.copy(ask = null, asked = true) }
                load()
            } catch (e: VaultFailure) {
                state.update { it.copy(ask = f.copy(busy = false), error = e.kind, limit = e.limit) }
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
                state.update { it.copy(error = e.kind, limit = e.limit) }
            }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }
}
