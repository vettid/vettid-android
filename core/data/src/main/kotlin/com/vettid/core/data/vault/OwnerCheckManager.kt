package com.vettid.core.data.vault

import com.vettid.core.vault.FeedItem
import com.vettid.core.vault.HeldCounts
import com.vettid.core.vault.HeldNotice
import com.vettid.core.vault.OwnerCheckPassed
import com.vettid.core.vault.OwnerCheckStatus
import com.vettid.core.vault.Settings
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** The vault operations the owner check needs (VaultApi in the app; a fake in tests). */
interface OwnerCheckOps {
    /** `vault.status`'s `owner_check`, or null from a vault older than 0.13.0. */
    suspend fun status(): OwnerCheckStatus?

    suspend fun check(pin: String, password: String, hold: Boolean?, holdOffUntil: String?): OwnerCheckPassed

    suspend fun settingsGet(): Settings

    suspend fun settingsSet(version: Long, set: Map<String, JsonElement>)
}

/**
 * The app's side of the daily owner check (VAULT-MESSAGING 0.13.0 §3.6): follows the vault's deadline and hold,
 * sends the check, changes the interval and the hold, and keeps what it learnt in [persist] (a Keystore-encrypted
 * file in the app) so that a locked vault past its deadline asks for the PIN and the password together.
 *
 * Gating the screens (when to show the check, never over an action in progress) is the UI's part (§3.6.5).
 */
@Suppress("TooManyFunctions")
class OwnerCheckManager(
    private val scope: CoroutineScope,
    private val ops: suspend () -> OwnerCheckOps,
    private val load: () -> ByteArray?,
    private val persist: (ByteArray) -> Unit,
    /** After a passed check: catch up on what the vault held back (§3.6.3: `sync.since`, `feed.list`, `message.list`). */
    private val onPassed: () -> Unit,
    /** Marks feed items read (the feed's `FeedManager`, the one source of the notices, ANDROID-PLAN 0.1.23). */
    private val readNotices: suspend (List<String>) -> Unit = {},
    private val now: () -> Instant = Instant::now,
) : OwnerCheckRepository {
    private val saved = restore()
    private val view = MutableStateFlow(saved?.toView())
    private val locked = MutableStateFlow(false)
    private val unlockOutcome = MutableStateFlow<OwnerCheckOutcome?>(null)
    private val noticeFlow = MutableStateFlow<List<OwnerCheckNotice>>(emptyList())

    /** The `ts` of the newest `vault.held` applied (kept with the view: an older one never overwrites newer counts). */
    private var heldTs: Instant? = instant(saved?.heldTs)

    override val ownerCheck: StateFlow<OwnerCheckView?> = view.asStateFlow()
    override val lockedByOwnerCheck: StateFlow<Boolean> = locked.asStateFlow()
    override val unlockCheckOutcome: StateFlow<OwnerCheckOutcome?> = unlockOutcome.asStateFlow()
    override val notices: StateFlow<List<OwnerCheckNotice>> = noticeFlow.asStateFlow()

    // --- what the vault tells ---

    /** Events the manager follows: `vault.held`, `sync.event{owner_check}`, `vault.locking` (the notices come from [onFeed]). */
    fun onEvent(m: VaultMessage) {
        when (m.type) {
            "vault.held" -> onHeld(m)
            "sync.event" -> when (VaultJson.str(m.body, "kind")) {
                // Another device saw a check pass (§3.6.3); the holder learns it from its own answer.
                "owner_check" -> update { v ->
                    val deadline = instant(VaultJson.str(m.body, "deadline")) ?: v.deadline
                    v.copy(state = OwnerCheckState.OK, deadline = deadline, failures = 0, waiting = null)
                }
                "settings.changed" -> scope.launch { runCatching { refreshOwnerCheck() } }
            }
            "vault.locking" -> if (VaultJson.str(m.body, "reason") == REASON_OWNER_CHECK) locked.value = true
        }
    }

    private fun onHeld(m: VaultMessage) {
        val n = runCatching { VaultJson.decode(HeldNotice.serializer(), m.body) }.getOrNull() ?: return
        // A device keeps the newest by ts (§3.6.3).
        val prev = heldTs
        if (prev != null && m.ts.isBefore(prev)) return
        heldTs = m.ts
        val counts = n.waiting.toCounts()
        val base = view.value ?: unknownHeld()
        val state = if (base.holdOff(now())) OwnerCheckState.DUE else OwnerCheckState.HELD
        set(base.copy(state = state, deadline = instant(n.deadline) ?: base.deadline, waiting = counts))
    }

    /** A request was answered `owner_check_required` (§3.6.3): the vault is past its deadline. */
    fun onRequired() {
        val v = view.value
        if (v == null || v.state == OwnerCheckState.OK) {
            val base = v ?: unknownHeld()
            set(base.copy(state = if (base.holdOff(now())) OwnerCheckState.DUE else OwnerCheckState.HELD))
        }
        scope.launch { runCatching { refreshOwnerCheck() } }
    }

    /** The vault opened (an unlock, or the app started with it unlocked): re-read the check (the notices come with the feed). */
    fun onOpened() {
        scope.launch { runCatching { refreshOwnerCheck() } }
    }

    /**
     * The unlock of a vault that may be past its deadline (§3.6.5): reads `vault.status` and, if the vault is
     * held or due, sends the check with the same entries; the answer waits in [unlockCheckOutcome].
     */
    suspend fun afterUnlock(pin: String, password: String) {
        locked.value = false
        val v = try {
            readStatus()
        } catch (_: VaultFailure) {
            null
        }
        if (v != null && v.gated(now())) unlockOutcome.value = check(pin, password, null)
    }

    // --- OwnerCheckRepository ---

    override suspend fun refreshOwnerCheck() {
        readStatus()
    }

    private suspend fun readStatus(): OwnerCheckView? {
        val st = vaultGuard { ops().status() } ?: return view.value
        val prev = view.value
        val state = when (st.state) {
            "held" -> OwnerCheckState.HELD
            "due" -> OwnerCheckState.DUE
            else -> OwnerCheckState.OK
        }
        val v = OwnerCheckView(
            state = state,
            deadline = instant(st.deadline),
            intervalSeconds = st.intervalSeconds ?: OwnerCheckView.MAX_INTERVAL_S,
            failures = st.failures,
            hold = st.hold,
            holdOffUntil = instant(st.holdOffUntil),
            // 0.19.0: the status says what is waiting; from an older vault only `vault.held` does.
            waiting = if (state == OwnerCheckState.OK) null else st.waiting?.toCounts() ?: prev?.waiting,
        )
        set(v)
        return v
    }

    override suspend fun check(pin: String, password: String, holdOff: HoldOff?): OwnerCheckOutcome {
        val until = holdOff?.until?.let { clampHoldOff(it) }
        val outcome = try {
            val r = vaultGuard { ops().check(pin, password, if (holdOff != null) false else null, until?.toString()) }
            locked.value = false
            heldTs = null
            set(
                OwnerCheckView(
                    state = OwnerCheckState.OK,
                    deadline = instant(r.deadline),
                    intervalSeconds = r.intervalSeconds ?: view.value?.intervalSeconds ?: OwnerCheckView.MAX_INTERVAL_S,
                    failures = 0,
                    hold = r.hold,
                    holdOffUntil = instant(r.holdOffUntil),
                ),
            )
            onPassed()
            OwnerCheckOutcome.Passed
        } catch (e: VaultFailure) {
            failed(e)
        }
        return outcome
    }

    private suspend fun failed(e: VaultFailure): OwnerCheckOutcome = when (e.code) {
        CODE_BAD_PIN -> OwnerCheckOutcome.BadPin(checksLeftAfterFailure())
        CODE_BAD_PASSWORD -> OwnerCheckOutcome.BadPassword(checksLeftAfterFailure())
        CODE_BACKOFF -> OwnerCheckOutcome.Backoff(retryAfter(e))
        else -> OwnerCheckOutcome.Failed(e.kind, e.code)
    }

    /** A failed check counts (§3.6.4): re-read `failures` (the tenth locks the vault, and then nothing answers). */
    private suspend fun checksLeftAfterFailure(): Int? {
        val before = view.value
        val after = withTimeoutOrNull(STATUS_AFTER_FAILURE_MS) { runCatching { readStatus() }.getOrNull() }
        return when {
            after != null && after !== before -> after.checksLeft
            before != null -> {
                set(before.copy(failures = before.failures + 1))
                view.value?.checksLeft
            }
            else -> null
        }
    }

    /** A backoff's wait: the error body's `retry_after` (VAULT-MESSAGING 0.17.0 §3.6.1, §10.1); 0 from older vaults. */
    private fun retryAfter(e: VaultFailure): Long =
        e.retryAfterSeconds.takeIf { it > 0 } ?: (e.cause as? VaultOpException)?.let { retryAfterOf(it) } ?: 0

    private fun clampHoldOff(until: Instant): Instant {
        // At most 30 days ahead at the vault (§3.6.7): a small margin for the clocks of the phone and the enclave.
        val max = now().plus(OwnerCheckView.MAX_HOLD_OFF).minus(HOLD_OFF_MARGIN_MIN, ChronoUnit.MINUTES)
        return (if (until.isAfter(max)) max else until).truncatedTo(ChronoUnit.SECONDS)
    }

    override suspend fun setCheckInterval(seconds: Long) {
        require(seconds in OwnerCheckView.MIN_INTERVAL_S..OwnerCheckView.MAX_INTERVAL_S)
        setSetting(KEY_INTERVAL, JsonPrimitive(seconds))
        // Shortening may move the deadline at once, and may hold the vault (§3.6.2).
        runCatching { readStatus() }
    }

    override suspend fun turnHoldOn() {
        setSetting(KEY_HOLD, JsonPrimitive(true))
        update { it.copy(hold = true, holdOffUntil = null) }
        runCatching { readStatus() }
    }

    private suspend fun setSetting(key: String, value: JsonPrimitive) = vaultGuard {
        val o = ops()
        val current = o.settingsGet()
        if (current.settings[key] != value) o.settingsSet(current.version, mapOf(key to value))
    }

    override fun consumeUnlockCheckOutcome() {
        unlockOutcome.value = null
    }

    override suspend fun dismissNotices() {
        val items = noticeFlow.value
        noticeFlow.value = emptyList()
        readNotices(items.map { it.itemId })
    }

    /**
     * The feed as `FeedManager` holds it (one source, ANDROID-PLAN 0.1.23, 6): the banner shows its unread
     * `owner_check.*` items, so reading one in the Notifications list closes the banner, and closing the banner reads
     * them there.
     */
    fun onFeed(items: List<FeedItem>) {
        noticeFlow.value = items.mapNotNull { notice(it) }.sortedByDescending { it.at }
    }

    private fun notice(item: FeedItem): OwnerCheckNotice? {
        if (item.kind !in NOTICE_KINDS || item.status != STATUS_ACTIVE) return null
        return OwnerCheckNotice(item.itemId, item.kind, item.ref, instant(item.at), urgent = item.priority == PRIORITY_URGENT)
    }

    /** Forgets everything (a wipe, a deleted vault). */
    fun clear() {
        view.value = null
        locked.value = false
        unlockOutcome.value = null
        noticeFlow.value = emptyList()
        heldTs = null
        runCatching { persist(ByteArray(0)) }
    }

    // --- state ---

    private fun unknownHeld() =
        OwnerCheckView(OwnerCheckState.HELD, null, OwnerCheckView.MAX_INTERVAL_S, 0, hold = true, holdOffUntil = null)

    private fun update(f: (OwnerCheckView) -> OwnerCheckView) {
        view.value?.let { set(f(it)) }
    }

    private fun set(v: OwnerCheckView) {
        view.value = v
        runCatching { persist(json.encodeToString(Saved.serializer(), Saved.of(v, heldTs)).toByteArray()) }
    }

    private fun restore(): Saved? = try {
        load()?.takeIf { it.isNotEmpty() }?.let { json.decodeFromString(Saved.serializer(), String(it)) }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.io.IOException) {
        null
    } catch (_: java.security.GeneralSecurityException) {
        null
    }

    /**
     * What is kept on the phone. [waiting] null: the vault has not said what is waiting (never zero for unknown);
     * [heldTs]: the `ts` of the `vault.held` the counts came from.
     */
    @Serializable
    private data class Saved(
        val state: String,
        val deadline: String? = null,
        val interval: Long = OwnerCheckView.MAX_INTERVAL_S,
        val failures: Int = 0,
        val hold: Boolean = true,
        val holdOffUntil: String? = null,
        val waiting: SavedCounts? = null,
        val heldTs: String? = null,
    ) {
        fun toView() = OwnerCheckView(
            runCatching { OwnerCheckState.valueOf(state) }.getOrDefault(OwnerCheckState.OK),
            instant(deadline), interval, failures, hold, instant(holdOffUntil),
            waiting = waiting?.let { WaitingCounts(it.messages, it.requests, it.calls, it.other) },
        )

        companion object {
            fun of(v: OwnerCheckView, heldTs: Instant?) = Saved(
                v.state.name, v.deadline?.toString(), v.intervalSeconds, v.failures, v.hold, v.holdOffUntil?.toString(),
                waiting = v.waiting?.let { SavedCounts(it.messages, it.requests, it.calls, it.other) },
                heldTs = heldTs?.toString(),
            )
        }
    }

    @Serializable
    private data class SavedCounts(val messages: Int = 0, val requests: Int = 0, val calls: Int = 0, val other: Int = 0)

    companion object {
        const val REASON_OWNER_CHECK = "owner_check"
        const val KEY_INTERVAL = "owner_check.interval_seconds"
        const val KEY_HOLD = "owner_check.hold"
        private const val CODE_BAD_PIN = "bad_pin"
        private const val CODE_BAD_PASSWORD = "bad_password"
        private const val CODE_BACKOFF = "backoff"
        private const val STATUS_ACTIVE = "active"
        private const val PRIORITY_URGENT = "urgent"
        private const val STATUS_AFTER_FAILURE_MS = 5_000L
        private const val HOLD_OFF_MARGIN_MIN = 10L
        val NOTICE_KINDS = setOf("owner_check.failed", "owner_check.locked", "owner_check.hold_changed")
        private val json = Json { ignoreUnknownKeys = true }

        private fun HeldCounts.toCounts() = WaitingCounts(messages, requests, calls, other)

        private fun instant(s: String?): Instant? = s?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
