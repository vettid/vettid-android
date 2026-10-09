package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** What the proactive release notice says (owner decision 2026-10-09, ANDROID-PLAN 0.1.19). */
enum class UpdateNoticeKind {
    /** A newer `active` release is listed: "A new vault release is available". */
    AVAILABLE,

    /** The vault's release is `retired`, or `deprecated` with an `ends_at`: the warning with the end date. */
    ENDING,

    /** The vault's release is `removed` and open only for a rescue (§11.10.5): only the move is offered. */
    ENDED,
}

/**
 * An update the member can approve (VAULT-MESSAGING §11.10.3, §11.10.6): the manifest the app trusts (the published
 * one, or a loaded canary manifest under the selection rule of §11.10.1) lists an `active` release newer than
 * [current], the release the vault is sealed to (the one this phone last unlocked into).
 *
 * The manifest has no security flag (VAULT-RELEASES keeps `security: none | recommended | urgent` in the release
 * log, which the app does not read), so a security release is not told apart: every notice is dismissed for
 * [ReleaseNotices.DISMISS_FOR] at most.
 */
data class ReleaseUpdateOffer(val current: ReleaseView, val target: ReleaseView) {
    val kind: UpdateNoticeKind
        get() = when {
            current.status == STATUS_REMOVED -> UpdateNoticeKind.ENDED
            current.status == STATUS_RETIRED -> UpdateNoticeKind.ENDING
            current.status == STATUS_DEPRECATED && current.endsAt != null -> UpdateNoticeKind.ENDING
            else -> UpdateNoticeKind.AVAILABLE
        }

    /** The current release's end, shown for `deprecated` and `retired` releases (§11.10.1 `ends_at`). */
    val endsAt: Instant? get() = current.endsAt

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_DEPRECATED = "deprecated"
        const val STATUS_RETIRED = "retired"
        const val STATUS_REMOVED = "removed"

        /** The offer for a vault sealed to [sealed] when [newest] is the manifest's newest `active` release; or none. */
        fun of(sealed: ReleaseView?, newest: ReleaseView?): ReleaseUpdateOffer? = when {
            sealed == null || newest == null -> null
            newest.status != STATUS_ACTIVE || newest.number <= sealed.number -> null
            else -> ReleaseUpdateOffer(sealed, newest)
        }
    }
}

/** The member dismissed the notice of [release] ([kind]) at [at]. */
data class ReleaseDismissal(val release: Long, val kind: UpdateNoticeKind, val at: Instant)

/** When the shell shows the release banner and when the app posts its local notification. */
object ReleaseNotices {
    /** A dismissed banner comes back after 24 h, whatever the kind (owner decision 2026-10-09). */
    val DISMISS_FOR: Duration = Duration.ofHours(24)

    /**
     * Whether the banner shows for [offer] at [now]: always without a dismissal of this release and kind (a notice
     * that turns from "available" into an end-date warning shows again), and again [DISMISS_FOR] after one. A
     * dismissal "in the future" (the clock was set back) does not hide it either.
     */
    fun bannerVisible(offer: ReleaseUpdateOffer?, dismissal: ReleaseDismissal?, now: Instant): Boolean = when {
        offer == null -> false
        dismissal == null || dismissal.release != offer.target.number || dismissal.kind != offer.kind -> true
        else -> now.isBefore(dismissal.at) || !now.isBefore(dismissal.at.plus(DISMISS_FOR))
    }

    /** One local notification per release: only for a target newer than the last one notified. */
    fun shouldNotify(offer: ReleaseUpdateOffer?, lastNotified: Long): Boolean = offer != null && offer.target.number > lastNotified
}

/** One unlock of the update (VaultManager in the app; a fake in tests). */
sealed interface UpdateStepResult {
    /** Unlocked, without an update member. */
    data object Opened : UpdateStepResult

    /** `update: moved`: the vault is sealed to the target and locked itself (§11.10.4 step 8). */
    data object Moved : UpdateStepResult

    /** `update: abandoned`: back on the previous release, unlocked (§11.10.4). */
    data object Abandoned : UpdateStepResult

    /** Unlocked, the update refused with [code] (`target`, `downgrade`, `approval`, `seal_key`, `pending`, `write`). */
    data class Refused(val code: String) : UpdateStepResult

    data class BadPin(val retryAfterSeconds: Long) : UpdateStepResult

    data class Backoff(val retryAfterSeconds: Long) : UpdateStepResult

    data class Failed(val kind: FailureKind, val code: String?, val retryAfterSeconds: Long = 0) : UpdateStepResult
}

/** What the update needs from the vault. */
interface ReleaseUpdateOps {
    /** The offer for the vault as this phone knows it, from the manifest it trusts; null for none. */
    suspend fun offer(): ReleaseUpdateOffer?

    /** The owner check is due or held (§3.6.5): the check comes first, the update never bypasses it. */
    fun ownerCheckDue(): Boolean

    /** Locks the vault and waits (bounded) until the member API no longer sees it unlocked. */
    suspend fun lock()

    /**
     * One unlock with [pin]: approving [approve] (§11.10.3) when non-null, or abandoning the unconfirmed move
     * ([abandon], §11.10.4); never following a move by itself. [onStarting] hears `503 release_starting`.
     */
    suspend fun unlock(pin: String, approve: ReleaseView?, abandon: Boolean, onStarting: (Long) -> Unit): UpdateStepResult

    /** An unconfirmed move can be abandoned: this phone knows the release it moved from. */
    fun canAbandon(): Boolean
}

/** Where the one-step update stands (owner decision 2026-10-09). */
enum class UpdateStep {
    /** Locking the vault before the unlock that carries the approval. */
    LOCKING,

    /** The unlock with the approved `release_update` is out. */
    APPROVING,

    /** Moved: the unlock that reaches the new release is out. */
    REOPENING,

    /** The vault runs the new release and is open. */
    DONE,

    /** The vault refused the update; it stayed on its release and is open again. */
    REFUSED,

    /** The PIN was wrong (or the enclave's backoff runs): the vault is locked on its release; nothing moved. */
    BAD_PIN,

    /** Nothing moved, but the update did not go through (network, service); the vault may be locked. */
    FAILED,

    /** Moved, but the new release did not open the vault: try again, or return to the previous release. */
    NEW_RELEASE_FAILED,

    /** Returning to the previous release (§11.10.4 abandonment). */
    ABANDONING,

    /** Back on the previous release, open. */
    ABANDONED,
}

/** The update's state for the screens. The PIN is never part of it. */
data class UpdateProgress(
    val step: UpdateStep,
    val from: ReleaseView,
    val to: ReleaseView,
    /** The routed release is starting (`503 release_starting`, §11.10.5): the wait may take minutes. */
    val starting: Boolean = false,
    val refusal: String? = null,
    val failure: FailureKind? = null,
    val failureCode: String? = null,
    /** Seconds the enclave's backoff asks to wait before the next PIN (§11.8). */
    val waitSeconds: Long = 0,
    /** The vault is open (on [from] after a refusal or an abandonment, on [to] when done). */
    val vaultOpen: Boolean = false,
    /** "Return to release N" is offered (only while the move is unconfirmed). */
    val canAbandon: Boolean = false,
) {
    /** A step that waits for the member (a PIN, a choice, or "Continue"). */
    val waiting: Boolean get() = step !in RUNNING

    private companion object {
        val RUNNING = setOf(UpdateStep.LOCKING, UpdateStep.APPROVING, UpdateStep.REOPENING, UpdateStep.ABANDONING)
    }
}

/** The proactive release update: the offer the shell shows, and the one-step update. */
interface ReleaseUpdateRepository {
    val offer: StateFlow<ReleaseUpdateOffer?>

    /** Non-null while an update is under way or its outcome is shown; the root of the UI shows it over every phase. */
    val progress: StateFlow<UpdateProgress?>

    /** Reads the offer again (the manifest; quiet on failure: the last offer stays). */
    suspend fun refreshOffer()

    /** Whether the owner check must come first (§3.6.5). */
    fun ownerCheckDue(): Boolean

    /**
     * The member approved [offer] with [pin] ("Approve and update"): locks, unlocks with the approval, and after
     * `moved` unlocks again with the same PIN. False when it cannot start (an update runs, or the check is due).
     */
    fun start(pin: String, offer: ReleaseUpdateOffer): Boolean

    /** [UpdateStep.BAD_PIN] or [UpdateStep.FAILED]: the approved update again; [UpdateStep.NEW_RELEASE_FAILED]: the unlock again. */
    fun retry(pin: String)

    /** [UpdateStep.NEW_RELEASE_FAILED] with [UpdateProgress.canAbandon]: back to the previous release (§11.10.4). */
    fun abandon(pin: String)

    /** Leaves the outcome (or gives up): the app follows the vault's phase again. Ignored while a step runs. */
    fun finish()
}

/**
 * The one-step update (owner decision 2026-10-09): no manual lock. Locks the vault, sends the unlock with the
 * approved `release_update` (§11.10.3, built as the unlock screen's), and after `update: moved` unlocks again with
 * the same PIN, which reaches the new release (§11.10.6). Waits while a release starts (`503 release_starting`,
 * [MAX_START_RETRIES] times beyond the API client's own wait); a refusal leaves the vault on its release, unlocked
 * again by that same unlock; a new release that does not open the vault offers the way back while the move is
 * unconfirmed (§11.10.4). The PIN is held only while a step runs and dropped when it ends.
 *
 * Nothing here sends an approval except [start] (and [retry] of a start that failed before anything moved): the
 * offer, its refresh and the notices never touch the vault.
 */
class ReleaseUpdateManager(
    private val scope: CoroutineScope,
    private val ops: ReleaseUpdateOps,
) : ReleaseUpdateRepository {
    private val offerFlow = MutableStateFlow<ReleaseUpdateOffer?>(null)
    private val progressFlow = MutableStateFlow<UpdateProgress?>(null)
    override val offer: StateFlow<ReleaseUpdateOffer?> = offerFlow.asStateFlow()
    override val progress: StateFlow<UpdateProgress?> = progressFlow.asStateFlow()
    private var job: Job? = null

    override suspend fun refreshOffer() {
        try {
            offerFlow.value = ops.offer()
        } catch (e: CancellationException) {
            throw e
        } catch (_: VaultFailure) {
            // display only: the last offer stays
        }
    }

    override fun ownerCheckDue(): Boolean = ops.ownerCheckDue()

    override fun start(pin: String, offer: ReleaseUpdateOffer): Boolean {
        if (running() || ops.ownerCheckDue() || pin.isEmpty()) return false
        progressFlow.value = UpdateProgress(UpdateStep.LOCKING, offer.current, offer.target)
        job = scope.launch {
            try {
                ops.lock()
            } catch (e: VaultFailure) {
                // Nothing changed: the vault was not locked, or not everywhere; the member decides again.
                set { it.copy(step = UpdateStep.FAILED, failure = e.kind, failureCode = e.code, vaultOpen = true) }
                return@launch
            }
            approve(pin)
        }
        return true
    }

    override fun retry(pin: String) {
        val p = progressFlow.value ?: return
        if (running() || pin.isEmpty()) return
        when (p.step) {
            UpdateStep.BAD_PIN -> launch { approve(pin) }
            UpdateStep.FAILED -> launch {
                if (p.vaultOpen) {
                    try {
                        set { it.copy(step = UpdateStep.LOCKING, failure = null, failureCode = null) }
                        ops.lock()
                    } catch (e: VaultFailure) {
                        set { it.copy(step = UpdateStep.FAILED, failure = e.kind, failureCode = e.code, vaultOpen = true) }
                        return@launch
                    }
                }
                approve(pin)
            }
            UpdateStep.NEW_RELEASE_FAILED -> launch { reopen(pin) }
            else -> Unit
        }
    }

    override fun abandon(pin: String) {
        val p = progressFlow.value ?: return
        val offered = p.step == UpdateStep.NEW_RELEASE_FAILED && p.canAbandon && ops.canAbandon()
        if (running() || pin.isEmpty() || !offered) return
        launch {
            set { it.copy(step = UpdateStep.ABANDONING, failure = null, failureCode = null) }
            when (val r = withStart { s -> ops.unlock(pin, approve = null, abandon = true, onStarting = s) }) {
                UpdateStepResult.Abandoned, UpdateStepResult.Opened ->
                    set { it.copy(step = UpdateStep.ABANDONED, vaultOpen = true, canAbandon = false) }
                else -> newReleaseFailed(r)
            }
        }
    }

    override fun finish() {
        if (running()) return // never in the middle of an unlock: its answer decides what the vault is
        job = null
        val done = progressFlow.value?.step == UpdateStep.DONE
        progressFlow.value = null
        if (done) offerFlow.value = null
    }

    private fun running(): Boolean = job?.isActive == true

    private fun launch(block: suspend () -> Unit) {
        job = scope.launch { block() }
    }

    private fun set(f: (UpdateProgress) -> UpdateProgress) = progressFlow.update { it?.let(f) }

    /** The unlock with the approval of [UpdateProgress.to]; nothing else sends one. */
    private suspend fun approve(pin: String) {
        val to = progressFlow.value?.to ?: return
        set { it.copy(step = UpdateStep.APPROVING, failure = null, failureCode = null, waitSeconds = 0, refusal = null) }
        when (val r = withStart { s -> ops.unlock(pin, approve = to, abandon = false, onStarting = s) }) {
            UpdateStepResult.Moved -> reopen(pin)
            // The update was refused; that same unlock opened the vault on its release.
            is UpdateStepResult.Refused -> set { it.copy(step = UpdateStep.REFUSED, refusal = r.code, vaultOpen = true) }
            // Opened without an `update` member (a vault that ignored it): nothing moved.
            UpdateStepResult.Opened, UpdateStepResult.Abandoned ->
                set { it.copy(step = UpdateStep.REFUSED, refusal = CODE_NOT_MOVED, vaultOpen = true) }
            is UpdateStepResult.BadPin ->
                set { it.copy(step = UpdateStep.BAD_PIN, waitSeconds = r.retryAfterSeconds, failure = FailureKind.BAD_PIN) }
            is UpdateStepResult.Backoff ->
                set { it.copy(step = UpdateStep.BAD_PIN, waitSeconds = r.retryAfterSeconds, failure = FailureKind.BACKOFF) }
            is UpdateStepResult.Failed ->
                set { it.copy(step = UpdateStep.FAILED, failure = r.kind, failureCode = r.code, vaultOpen = false) }
        }
    }

    /** After `moved`: the unlock that reaches the new release, with the same PIN, and no approval. */
    private suspend fun reopen(pin: String) {
        set { it.copy(step = UpdateStep.REOPENING, failure = null, failureCode = null, waitSeconds = 0) }
        var r = withStart { s -> ops.unlock(pin, approve = null, abandon = false, onStarting = s) }
        // Routed to the old release still (the API learns the move from its lifecycle event): it completes the
        // recorded move again (§11.10.4 "Failures") and reports `moved`; once more reaches the new release.
        var again = 0
        while (r == UpdateStepResult.Moved && again++ < MAX_MOVED_AGAIN) {
            r = withStart { s -> ops.unlock(pin, approve = null, abandon = false, onStarting = s) }
        }
        when (r) {
            UpdateStepResult.Opened, is UpdateStepResult.Refused -> {
                set { it.copy(step = UpdateStep.DONE, vaultOpen = true, canAbandon = false) }
                offerFlow.value = null
            }
            else -> newReleaseFailed(r)
        }
    }

    private fun newReleaseFailed(r: UpdateStepResult) {
        val (kind, code, wait) = when (r) {
            is UpdateStepResult.Failed -> Triple(r.kind, r.code, 0L)
            is UpdateStepResult.BadPin -> Triple(FailureKind.BAD_PIN, null, r.retryAfterSeconds)
            is UpdateStepResult.Backoff -> Triple(FailureKind.BACKOFF, null, r.retryAfterSeconds)
            else -> Triple(FailureKind.OTHER, null, 0L)
        }
        set {
            it.copy(
                step = UpdateStep.NEW_RELEASE_FAILED, failure = kind, failureCode = code, waitSeconds = wait, vaultOpen = false,
                canAbandon = ops.canAbandon(),
            )
        }
    }

    /** Runs [block], waiting and trying again while the routed release starts (`503 release_starting`). */
    private suspend fun withStart(block: suspend ((Long) -> Unit) -> UpdateStepResult): UpdateStepResult {
        var tries = 0
        while (true) {
            val r = block { _ -> set { it.copy(starting = true) } }
            if (r is UpdateStepResult.Failed && r.code == CODE_RELEASE_STARTING && tries++ < MAX_START_RETRIES) {
                set { it.copy(starting = true) }
                delay(r.retryAfterSeconds.coerceIn(1, MAX_START_WAIT_S) * MS_PER_S)
                continue
            }
            set { it.copy(starting = false) }
            return r
        }
    }

    companion object {
        /** The member API's `503 release_starting` (§11.10.5). */
        const val CODE_RELEASE_STARTING = "release_starting"

        /** [UpdateProgress.refusal] when the vault opened without saying anything of the update. */
        const val CODE_NOT_MOVED = "not_moved"

        /** Further waits after the API client's own (10 minutes): a release that cannot start is a failure. */
        const val MAX_START_RETRIES = 5
        private const val MAX_START_WAIT_S = 60L
        private const val MS_PER_S = 1000L
        private const val MAX_MOVED_AGAIN = 2
    }
}

/**
 * A tap on the "Vault updates" notification: the shell opens the update screen once the vault is open (after the
 * unlock, and after the owner check if it is due).
 */
class ReleaseUpdateInbox {
    private val flow = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = flow.asStateFlow()

    fun offer() {
        flow.value = true
    }

    fun consume() {
        flow.value = false
    }
}
