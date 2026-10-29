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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /** (ANDROID-PLAN 0.1.95) The member API's `GET /api/vault/status` `state` (`locked`, `unlocked`, ...); null: no answer. */
    private val memberState: suspend () -> String? = { null },
    /** (ANDROID-PLAN 0.1.95) A read found the vault locked: the app moves to its locked screen, as for a `vault.locking`. */
    private val onFoundLocked: () -> Unit = {},
) : OwnerCheckRepository {
    private val saved = restore()
    private val view = MutableStateFlow(saved?.toView())
    private val locked = MutableStateFlow(false)
    private val causeUnknown = MutableStateFlow(false)
    private val reconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val unlockOutcome = MutableStateFlow<OwnerCheckOutcome?>(null)
    private val noticeFlow = MutableStateFlow<List<OwnerCheckNotice>>(emptyList())
    private val presenceFlow = MutableStateFlow(saved?.presenceUnrecorded == true)
    private val checkAfterCancelFlow = MutableStateFlow(false)

    /** (Release L) The vault locked itself; kept on the phone until it opens again. */
    private val idleFlow = MutableStateFlow(saved?.takeIf { it.idleLocked }?.let { IdleLocked(it.idleAfter) })

    /** (Release L) The lowest idle-lock value set since the last check, when one was set; null: the set value is in force. */
    private var idleInForce: Long? = saved?.idleInForce

    /**
     * (Release L) How long past the deadline the vault locks itself, as read while the check was not yet due (then
     * `idle_lock_at` − `deadline` is the value in force; past the deadline an unlock's extra hour or a call can move
     * `idle_lock_at`, and the difference no longer says how long the member was away). Null: never read.
     */
    private var idleAfterKnown: Long? = saved?.idleAfterKnown

    /** The time scale of the last `vault.status` read (1 without one): an idle-lock value as it runs. */
    private var lastTimeScale: Long = 1

    /** (Release L) The member locked the vault on this phone since it last opened. */
    private var lockedHere: Boolean = saved?.lockedHere == true
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
    override val lockCauseUnknown: StateFlow<Boolean> = causeUnknown.asStateFlow()
    override val relayReconnects: Flow<Unit> = reconnects.asSharedFlow()
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
        if (m.type == "vault.locking") {
            unlockPin = null // a lock of any reason ends the kept PIN (ANDROID-PLAN 0.1.93)
            epoch.incrementAndGet() // and drops a probe that finishes after it, whatever the reason (ANDROID-PLAN 0.1.96)
            if (VaultJson.str(m.body, "reason") != REASON_OWNER_CHECK) lockedOther = true
        }
        when (m.type) {
            "vault.held" -> onHeld(m)
            "sync.event" -> when (VaultJson.str(m.body, "kind")) {
                // Another device saw a check pass (§3.6.3); the holder learns it from its own answer.
                "owner_check" -> {
                    // A passed check: the idle lock's time moved with the deadline (the vault says it again), and a
                    // longer idle-lock value set before it is in force now (§3.6.8).
                    checkPassedForIdleLock()
                    estimate = OwnerCheckView.MAX_FAILURES
                    update { v ->
                        val deadline = instant(VaultJson.str(m.body, "deadline")) ?: v.deadline
                        v.copy(state = OwnerCheckState.OK, deadline = deadline, failures = 0, waiting = null, idleLockAt = null)
                    }
                }
                "settings.changed" -> scope.launch { runCatching { refreshOwnerCheck() } }
            }
            "vault.locking" -> when (VaultJson.str(m.body, "reason")) {
                REASON_OWNER_CHECK -> markLocked(causeKnown = true)
                // (Release L, §3.6.8) It locked itself: said on the unlock screen and in the notification, with how
                // long past the deadline that was, as the vault last said.
                REASON_IDLE -> {
                    idleFlow.value = IdleLocked(idleAfterKnown)
                    view.value?.let { set(it) }
                }
            }
        }
    }

    /**
     * (ANDROID-PLAN 0.1.97) A `vault.locking` whose reason is not the owner check (idle, the member, an update, another
     * device) came since the vault last opened: a read that finds the vault locked after it never names the owner check as
     * the cause, whatever the caller assumed (never claim a lock from a guess).
     */
    @Volatile
    private var lockedOther = false

    /** The vault is known locked; [causeKnown]: by ten wrong checks (`vault.locking{owner_check}`, or an estimate of 0). */
    private fun markLocked(causeKnown: Boolean) {
        // A cause already known is never downgraded while the vault stays locked (a probe may finish after `vault.locking`).
        causeUnknown.value = !causeKnown && (!locked.value || causeUnknown.value)
        locked.value = true
    }

    /** Nothing says the vault is locked (an unlock, a passed check, a forgotten state). */
    private fun markOpen() {
        locked.value = false
        causeUnknown.value = false
        lockedOther = false
    }

    /**
     * (ANDROID-PLAN 0.1.95, VAULT-MESSAGING 0.73.0) The tries left as the app counts them: the count of the last
     * `vault.status` read, less one for each failed check answered since and for each check sent since that got no answer. With
     * no count read since the unlock or since the app started it is 0 (fail secure: it never assumes tries it did not read).
     */
    @Volatile
    private var estimate = 0

    /**
     * (ANDROID-PLAN 0.1.96, VAULT-MESSAGING 0.73.2) [afterUnlock] read the count: the [onOpened] that ends this unlock keeps it
     * (one count read "since the unlock" is a count read). Every unlock attempt clears it at its start ([beginUnlock]); a late
     * `vault.locking` leaves it alone.
     */
    @Volatile
    private var keepCount = false

    /** Counts the lock, wipe and unlock events a read must not outlive: a result is dropped if it changed during the read. */
    private val epoch = java.util.concurrent.atomic.AtomicInteger()

    init {
        // (ANDROID-PLAN 0.1.95) The other operations that count as failed checks (§3.6.4) spend the same estimate.
        CheckSink.counter = object : CheckSink.Counter {
            override fun answered() {
                spendEstimate()
            }

            override fun unanswered() {
                spendEstimate() // it may have counted; the owner check's own type is not in the list (see [failed])
            }
        }
    }

    /** One try less in the estimate (never below 0); the new estimate. */
    private fun spendEstimate(): Int {
        estimate = (estimate - 1).coerceAtLeast(0)
        return estimate
    }

    /** The relay connection came back (called by the vault manager). */
    fun onRelayReconnected() {
        reconnects.tryEmit(Unit)
    }

    override suspend fun probeVault(lockCauseKnown: Boolean): VaultProbe {
        val started = epoch.get()
        // An answer to vault.status, whatever it says of the check, means the vault is open (the relay is the vault's own voice).
        val answered = withTimeoutOrNull(STATUS_AFTER_FAILURE_MS) { attempt { readStatus() }.isSuccess } == true
        // The member API's `unlocked` is not an answer (the host saw a lease, not the enclave); only `locked` is taken.
        val api = if (answered) null else attempt { memberState() }.getOrNull()
        return when {
            // The member locked the vault, or it was wiped or unlocked, during the read: the result is stale and dropped.
            epoch.get() != started -> VaultProbe.NO_ANSWER
            answered -> VaultProbe.OPEN
            api != STATE_LOCKED -> VaultProbe.NO_ANSWER
            else -> {
                markLocked(lockCauseKnown && !lockedOther)
                onFoundLocked()
                VaultProbe.LOCKED
            }
        }
    }

    /** [runCatching] that lets a cancellation through (a cancelled read ends, it is not an answer). */
    @Suppress("TooGenericExceptionCaught") // a read that fails for any reason is simply no answer
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
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
    fun onOpened(afterUnlock: Boolean = true) {
        if (afterUnlock) {
            // An unlock or a start: a count is read next; until then none is known. A return to the foreground keeps the estimate,
            // and so does the unlock whose own count [afterUnlock] just read (ANDROID-PLAN 0.1.96, VAULT-MESSAGING 0.73.2).
            if (!keepCount) estimate = 0
            keepCount = false
            lockedOther = false // a lock before this open names no cause for one after it
            epoch.incrementAndGet()
        }
        endIdleLocked()
        scope.launch { runCatching { refreshOwnerCheck() } }
    }

    /** The vault is open again: it no longer is locked by itself. */
    private fun endIdleLocked() {
        if (idleFlow.value == null && !lockedHere) return
        idleFlow.value = null
        lockedHere = false
        view.value?.let { set(it) }
    }

    /**
     * The unlock of a vault that may be past its deadline (§3.6.5): reads `vault.status` and, if the vault is
     * held or due, sends the check with the same entries; the answer waits in [unlockCheckOutcome]. After a wrong password
     * or a backoff the held screen shows it and asks for the password only (ANDROID-PLAN 0.1.93): the accepted [pin] is
     * kept in memory for it ([takeUnlockPin]), and not at all if the app is not in front, or left the front since
     * [generation] ([foregroundGeneration] read before the unlock request) and came back before this answer.
     */
    suspend fun afterUnlock(pin: String, password: String, generation: Int) {
        unlockPin = null
        markOpen()
        estimate = 0
        keepCount = false
        epoch.incrementAndGet()
        endIdleLocked()
        val v = try {
            readStatus().also { keepCount = true } // the count is read: the open that follows keeps it (ANDROID-PLAN 0.1.96)
        } catch (_: VaultFailure) {
            null
        }
        if (v == null || !v.gated(now())) return
        val r = check(pin, password, null)
        unlockOutcome.value = r
        val retry = (r is OwnerCheckOutcome.BadPassword && (r.checksLeft ?: 0) > 0) || r is OwnerCheckOutcome.Backoff
        unlockPin = pin.takeIf { retry && foregroundFlow.value && generation == foregroundGeneration }
        // Closes the race with setForeground(false) on the main thread between the test above and the assignment.
        if (!foregroundFlow.value || generation != foregroundGeneration) unlockPin = null
    }

    /** An unlock attempt starts (ANDROID-PLAN 0.1.96): a count kept for an earlier one is not carried into this one. */
    fun beginUnlock() {
        keepCount = false
    }

    /** The PIN the unlock just had accepted, kept only for the password-only retry of a check that failed on the password. */
    @Volatile
    private var unlockPin: String? = null

    override fun takeUnlockPin(): String? = unlockPin.also { unlockPin = null }

    override fun setForeground(inFront: Boolean) {
        if (!inFront) {
            foregroundGen.incrementAndGet()
            unlockPin = null
        }
        foregroundFlow.value = inFront
    }

    private val foregroundGen = java.util.concurrent.atomic.AtomicInteger()
    override val foregroundGeneration: Int get() = foregroundGen.get()

    private val foregroundFlow = MutableStateFlow(true)
    override val inFront: StateFlow<Boolean> = foregroundFlow.asStateFlow()

    /**
     * (Release R, ANDROID-PLAN "Other screens") Right after an unlock that cancelled a recovery: the owner check at once,
     * with the same entries, whatever the deadline, because it records that the member is present (§11.14.5). A check
     * that does not pass waits in [unlockCheckOutcome], and [checkAfterCancel] asks for the check screen.
     */
    suspend fun afterCancelledRecovery(pin: String, password: String) {
        markOpen()
        // No count was read in this session: the check does not go out with the previous session's estimate (ANDROID-PLAN 0.1.96).
        estimate = 0
        keepCount = false
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
        lastTimeScale = timeScale?.takeIf { it >= 1 } ?: 1
        presenceFromStatus(recovery)
        runCatching { onRecoveryStatus(recovery, since) }
        val st = st0 ?: return view.value
        estimate = (OwnerCheckView.MAX_FAILURES - st.failures).coerceAtLeast(0)
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
        unlockPin = null
        val until = holdOff?.until?.let { clampHoldOff(it) }
        val outcome = try {
            val r = vaultGuard { ops().check(pin, password, if (holdOff != null) false else null, until?.toString()) }
            markOpen()
            heldTs = null
            presenceFromCheck(r)
            idleFlow.value = null
            lockedHere = false
            checkPassedForIdleLock()
            estimate = OwnerCheckView.MAX_FAILURES // a passed check ends the run of failures
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
        CODE_BAD_PIN -> checksLeftAfterFailure().let { (n, est) -> OwnerCheckOutcome.BadPin(n, est) }
        CODE_BAD_PASSWORD -> checksLeftAfterFailure().let { (n, est) -> OwnerCheckOutcome.BadPassword(n, est) }
        CODE_BACKOFF -> OwnerCheckOutcome.Backoff(retryAfter(e))
        else -> OwnerCheckOutcome.Failed(e.kind, e.code, e.shownCode).let { f ->
            // (ANDROID-PLAN 0.1.95) A check sent that got no answer may have counted at the vault.
            if (f.unanswered) f.copy(estimatedLeft = spendEstimate()) else f
        }
    }

    /**
     * A failed check counts (§3.6.4): re-read `failures` (the tenth locks the vault, and then nothing answers). The count and
     * whether it is only the app's estimate ([estimate], ANDROID-PLAN 0.1.95): the read after the failure did not come.
     */
    private suspend fun checksLeftAfterFailure(): Pair<Int?, Boolean> {
        val before = view.value
        val after = withTimeoutOrNull(STATUS_AFTER_FAILURE_MS) { runCatching { readStatus() }.getOrNull() }
        val fresh = after != null && after !== before
        // The tenth failure locks the vault (§3.6.4), whether or not `vault.locking` has arrived yet (ANDROID-PLAN 0.1.93):
        // marked only from a fresh `vault.status`, never from the estimate below.
        if (fresh && after != null && after.checksLeft <= 0) markLocked(causeKnown = true)
        if (fresh) return after?.checksLeft to false
        // Only a count the vault gave is kept in the view and on disk; the estimate lives in [estimate] alone.
        spendEstimate()
        return estimate to true
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

    override fun idleLockedNow(now: Instant): IdleLocked? = idleFlow.value
        ?: view.value?.idleLockAt?.takeIf { !lockedHere && !now.isBefore(it) }?.let { IdleLocked(idleAfterKnown) }

    override fun onLockedHere() {
        unlockPin = null
        epoch.incrementAndGet() // a read that finishes after the member's own lock does not mark it
        lockedHere = true
        lockedOther = true
        view.value?.let { set(it) }
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
        // The value set is in force from now on (a longer one set before the check included): the time said at an idle
        // lock is that value as it runs on this vault (second review of #191, G1).
        val setting = idleSettingFlow.value
        if (setting != null) {
            idleAfterKnown = TimeScales.divide(setting.seconds, lastTimeScale)
        } else if (idleInForce != null) {
            // A longer value waited for this check, but its value is not loaded (a new process that has not read the
            // setting): the time is not known, so the notice says so rather than the shorter value read before.
            idleAfterKnown = null
        }
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
        unlockPin = null
        epoch.incrementAndGet()
        estimate = 0
        keepCount = false
        view.value = null
        markOpen()
        unlockOutcome.value = null
        noticeFlow.value = emptyList()
        heldTs = null
        presenceFlow.value = false
        presenceCheckAt = null
        checkAfterCancelFlow.value = false
        idleFlow.value = null
        idleInForce = null
        idleAfterKnown = null
        lockedHere = false
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
        // Before the deadline the idle lock's time is the deadline plus the value in force (§3.6.8).
        val d = v.deadline
        if (v.state == OwnerCheckState.OK && d != null && now().isBefore(d)) v.idleAfterSeconds?.let { idleAfterKnown = it }
        val idle = idleFlow.value
        val saved = Saved.of(v, heldTs).copy(
            presenceUnrecorded = presenceFlow.value, presenceCheckAt = presenceCheckAt?.toString(),
            idleLocked = idle != null, idleAfter = idle?.afterSeconds, idleInForce = idleInForce,
            idleAfterKnown = idleAfterKnown, lockedHere = lockedHere,
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
        val idleAfterKnown: Long? = null,
        val lockedHere: Boolean = false,
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
        private const val STATE_LOCKED = "locked"
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
