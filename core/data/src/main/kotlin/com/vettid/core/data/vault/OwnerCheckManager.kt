package com.vettid.core.data.vault

import com.vettid.core.vault.FeedItem
import com.vettid.core.vault.HeldCounts
import com.vettid.core.vault.HeldNotice
import com.vettid.core.vault.OwnerCheckPassed
import com.vettid.core.vault.OwnerCheckStatus
import com.vettid.core.vault.RecoveryStatus
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

    /** `vault.status`'s `owner_check` and (release R) `recovery`, from one read. */
    suspend fun statusAndRecovery(): Pair<OwnerCheckStatus?, RecoveryStatus?> = status() to null

    /** `vault.status`'s `owner_check`, (release R) `recovery` and (0.43.0) `time_scale`, from one read. */
    suspend fun statusRead(): StatusRead = statusAndRecovery().let { (o, r) -> StatusRead(o, r) }

    suspend fun settingsGet(): Settings

    suspend fun settingsSet(version: Long, set: Map<String, JsonElement>)
}

/** One `vault.status` read for the owner check: [ownerCheck], [recovery], and [timeScale] (null when absent). */
data class StatusRead(val ownerCheck: OwnerCheckStatus?, val recovery: RecoveryStatus?, val timeScale: Long? = null)

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
    /**
     * (Release R) Each `vault.status`'s `recovery`, for the clock of a counter that stays unavailable (§11.14.5), with
     * the [mark] read before the status was asked for (the account record it belongs to).
     */
    private val onRecoveryStatus: suspend (RecoveryStatus?, mark: Int) -> Unit = { _, _ -> },
    /**
     * (STAGING-TIME-SCALE §5.2, VAULT-MESSAGING 0.43.0 §11.10.8) Each `vault.status`'s `time_scale` (null when absent),
     * with the [mark] read before the status was asked for, before the deadline it came with is taken in: the early
     * warning (S2) is reckoned with the scale of the vault that set the deadline.
     */
    private val onTimeScale: suspend (Long?, mark: Int) -> Unit = { _, _ -> },
    private val now: () -> Instant = Instant::now,
    private val mark: () -> Int = { 0 },
) : OwnerCheckRepository {
    private val saved = restore()
    private val view = MutableStateFlow(saved?.toView())
    private val locked = MutableStateFlow(false)
    private val unlockOutcome = MutableStateFlow<OwnerCheckOutcome?>(null)
    private val noticeFlow = MutableStateFlow<List<OwnerCheckNotice>>(emptyList())
    private val presenceFlow = MutableStateFlow(saved?.presenceUnrecorded == true)
    private val checkAfterCancelFlow = MutableStateFlow(false)

    /** (Release L) The vault locked itself; kept on the phone until it opens again. */
    private val idleFlow = MutableStateFlow(saved?.takeIf { it.idleLocked }?.let { IdleLocked(it.idleAfter) })

    /** (Release L) The lowest idle-lock value set since the last check, when one was set; null: the set value is in force. */
    private var idleInForce: Long? = saved?.idleInForce
    private val idleSettingFlow = MutableStateFlow<IdleLockSetting?>(null)

    /**
     * (Release R) When the check whose presence was not recorded passed, on the vault's clock (its answer's deadline less
     * its interval), so that only a presence recorded since then ends the notice; null when the answer did not say.
     */
    private var presenceCheckAt: Instant? = instant(saved?.presenceCheckAt)

    /** The `ts` of the newest `vault.held` applied (kept with the view: an older one never overwrites newer counts). */
    private var heldTs: Instant? = instant(saved?.heldTs)

    override val ownerCheck: StateFlow<OwnerCheckView?> = view.asStateFlow()
    override val lockedByOwnerCheck: StateFlow<Boolean> = locked.asStateFlow()
    override val unlockCheckOutcome: StateFlow<OwnerCheckOutcome?> = unlockOutcome.asStateFlow()
    override val notices: StateFlow<List<OwnerCheckNotice>> = noticeFlow.asStateFlow()
    override val presenceUnrecorded: StateFlow<Boolean> = presenceFlow.asStateFlow()
    override val checkAfterCancel: StateFlow<Boolean> = checkAfterCancelFlow.asStateFlow()
    override val idleLocked: StateFlow<IdleLocked?> = idleFlow.asStateFlow()
    override val idleLockSetting: StateFlow<IdleLockSetting?> = idleSettingFlow.asStateFlow()

    override fun consumeCheckAfterCancel() {
        checkAfterCancelFlow.value = false
    }

    // --- what the vault tells ---

    /** Events the manager follows: `vault.held`, `sync.event{owner_check}`, `vault.locking` (the notices come from [onFeed]). */
    fun onEvent(m: VaultMessage) {
        when (m.type) {
            "vault.held" -> onHeld(m)
            "sync.event" -> when (VaultJson.str(m.body, "kind")) {
                // Another device saw a check pass (§3.6.3); the holder learns it from its own answer.
                "owner_check" -> {
                    // A passed check: the idle lock's time moved with the deadline (the vault says it again), and a
                    // longer idle-lock value set before it is in force now (§3.6.8).
                    checkPassedForIdleLock()
                    update { v ->
                        val deadline = instant(VaultJson.str(m.body, "deadline")) ?: v.deadline
                        v.copy(state = OwnerCheckState.OK, deadline = deadline, failures = 0, waiting = null, idleLockAt = null)
                    }
                }
                "settings.changed" -> scope.launch { runCatching { refreshOwnerCheck() } }
            }
            "vault.locking" -> when (VaultJson.str(m.body, "reason")) {
                REASON_OWNER_CHECK -> locked.value = true
                // (Release L, §3.6.8) It locked itself: said on the unlock screen and in the notification, with how
                // long past the deadline that was, as the vault last said.
                REASON_IDLE -> {
                    idleFlow.value = IdleLocked(view.value?.idleAfterSeconds)
                    view.value?.let { set(it) }
                }
            }
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
        set(
            base.copy(
                state = state, deadline = instant(n.deadline) ?: base.deadline, waiting = counts,
                // (Release L) A vault.held from a release before L says nothing of the idle lock: what was read stays.
                idleLockAt = instant(n.idleLockAt) ?: base.idleLockAt,
            ),
        )
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
        endIdleLocked()
        scope.launch { runCatching { refreshOwnerCheck() } }
    }

    /** The vault is open again: it no longer is locked by itself. */
    private fun endIdleLocked() {
        if (idleFlow.value == null) return
        idleFlow.value = null
        view.value?.let { set(it) }
    }

    /**
     * The unlock of a vault that may be past its deadline (§3.6.5): reads `vault.status` and, if the vault is
     * held or due, sends the check with the same entries; the answer waits in [unlockCheckOutcome].
     */
    suspend fun afterUnlock(pin: String, password: String) {
        locked.value = false
        endIdleLocked()
        val v = try {
            readStatus()
        } catch (_: VaultFailure) {
            null
        }
        if (v != null && v.gated(now())) unlockOutcome.value = check(pin, password, null)
    }

    /**
     * (Release R, ANDROID-PLAN "Other screens") Right after an unlock that cancelled a recovery: the owner check at once,
     * with the same entries, whatever the deadline, because it records that the member is present (§11.14.5). A check
     * that does not pass waits in [unlockCheckOutcome], and [checkAfterCancel] asks for the check screen.
     */
    suspend fun afterCancelledRecovery(pin: String, password: String) {
        locked.value = false
        val r = check(pin, password, null)
        if (r != OwnerCheckOutcome.Passed) {
            unlockOutcome.value = r
            checkAfterCancelFlow.value = true
        }
    }

    // --- OwnerCheckRepository ---

    override suspend fun refreshOwnerCheck() {
        readStatus()
    }

    private suspend fun readStatus(): OwnerCheckView? {
        val since = mark()
        val (st0, recovery, timeScale) = vaultGuard(resend = true) { ops().statusRead() }
        runCatching { onTimeScale(timeScale, since) }
        presenceFromStatus(recovery)
        runCatching { onRecoveryStatus(recovery, since) }
        val st = st0 ?: return view.value
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
            // (Release L) As the vault computes it now; absent before L.
            idleLockAt = instant(st.idleLockAt),
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
            presenceFromCheck(r)
            idleFlow.value = null
            checkPassedForIdleLock()
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
        else -> OwnerCheckOutcome.Failed(e.kind, e.code, e.shownCode)
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

    // --- the idle lock's setting (release L, §3.6.8) ---

    override suspend fun refreshIdleLockSetting() {
        val st = vaultGuard { ops().settingsGet() }
        val seconds = (st.settings[KEY_IDLE] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        idleSettingFlow.value = seconds?.let { settingOf(it) }
    }

    override suspend fun setIdleLockAfter(seconds: Long) {
        require(seconds in IdleLockSetting.CHOICES_S)
        if (idleSettingFlow.value == null) runCatching { refreshIdleLockSetting() }
        val before = idleSettingFlow.value ?: throw VaultFailure(FailureKind.NOT_SUPPORTED)
        setSetting(KEY_IDLE, JsonPrimitive(seconds))
        // The vault keeps the lowest value since the last check (owner decision 2026-10-23): a longer one waits for it.
        val inForce = minOf(before.inForceSeconds, seconds)
        idleInForce = inForce.takeIf { it < seconds }
        idleSettingFlow.value = IdleLockSetting(seconds, inForce)
        view.value?.let { set(it) }
        // A shorter value moves the idle lock's time at once.
        runCatching { readStatus() }
    }

    private fun settingOf(seconds: Long): IdleLockSetting = IdleLockSetting(seconds, minOf(seconds, idleInForce ?: seconds))

    /** A check passed: the value set is the one in force from now on. */
    private fun checkPassedForIdleLock() {
        idleInForce = null
        idleSettingFlow.value?.let { idleSettingFlow.value = IdleLockSetting(it.seconds) }
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

    /** §11.14.5: `present_recorded` false starts the notice; true ends it; absent (before release R) changes nothing. */
    private fun presenceFromCheck(r: OwnerCheckPassed) {
        when (r.recovery?.presentRecorded) {
            false -> {
                val deadline = instant(r.deadline)
                val interval = r.intervalSeconds
                presenceCheckAt = if (deadline != null && interval != null) deadline.minusSeconds(interval) else null
                presenceFlow.value = true
            }
            true -> {
                presenceCheckAt = null
                presenceFlow.value = false
            }
            null -> Unit
        }
    }

    /** A `vault.status` whose presence was recorded at or after the check that could not record it ends the notice. */
    private fun presenceFromStatus(r: RecoveryStatus?) {
        val at = instant(r?.presentRecordedAt)
        // Without the check's time (an answer without deadline or interval) no status can show it was recorded since.
        val since = presenceCheckAt
        val recordedSince = at != null && since != null && !at.isBefore(since)
        if (presenceFlow.value && recordedSince) {
            presenceCheckAt = null
            presenceFlow.value = false
            view.value?.let { set(it) }
        }
    }

    /** Forgets everything (a wipe, a deleted vault). */
    fun clear() {
        view.value = null
        locked.value = false
        unlockOutcome.value = null
        noticeFlow.value = emptyList()
        heldTs = null
        presenceFlow.value = false
        presenceCheckAt = null
        checkAfterCancelFlow.value = false
        idleFlow.value = null
        idleInForce = null
        idleSettingFlow.value = null
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
        val idle = idleFlow.value
        val saved = Saved.of(v, heldTs).copy(
            presenceUnrecorded = presenceFlow.value, presenceCheckAt = presenceCheckAt?.toString(),
            idleLocked = idle != null, idleAfter = idle?.afterSeconds, idleInForce = idleInForce,
        )
        runCatching { persist(json.encodeToString(Saved.serializer(), saved).toByteArray()) }
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
        /** (Release R) The presence notice is showing ([presenceUnrecorded]), and since which check. */
        val presenceUnrecorded: Boolean = false,
        val presenceCheckAt: String? = null,
        /** (Release L) [idleLockAt]; the vault locked itself ([idleLocked], [idleAfter]); [idleInForce]. */
        val idleLockAt: String? = null,
        val idleLocked: Boolean = false,
        val idleAfter: Long? = null,
        val idleInForce: Long? = null,
    ) {
        fun toView() = OwnerCheckView(
            runCatching { OwnerCheckState.valueOf(state) }.getOrDefault(OwnerCheckState.OK),
            instant(deadline), interval, failures, hold, instant(holdOffUntil),
            waiting = waiting?.let { WaitingCounts(it.messages, it.requests, it.calls, it.other) },
            idleLockAt = instant(idleLockAt),
        )

        companion object {
            fun of(v: OwnerCheckView, heldTs: Instant?) = Saved(
                v.state.name, v.deadline?.toString(), v.intervalSeconds, v.failures, v.hold, v.holdOffUntil?.toString(),
                waiting = v.waiting?.let { SavedCounts(it.messages, it.requests, it.calls, it.other) },
                heldTs = heldTs?.toString(),
                idleLockAt = v.idleLockAt?.toString(),
            )
        }
    }

    @Serializable
    private data class SavedCounts(val messages: Int = 0, val requests: Int = 0, val calls: Int = 0, val other: Int = 0)

    companion object {
        const val REASON_OWNER_CHECK = "owner_check"

        /** (Release L, §3.6.8) `vault.locking`'s reason for the idle lock. */
        const val REASON_IDLE = "idle"

        /** (Release L, §10.8) The idle lock's setting. */
        const val KEY_IDLE = "idle_lock.after_seconds"
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
