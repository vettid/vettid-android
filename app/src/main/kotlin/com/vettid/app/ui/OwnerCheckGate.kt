package com.vettid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.R
import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.NoCallAccess
import com.vettid.core.data.vault.OwnerCheckNotice
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.TimeScaleSource
import com.vettid.core.data.vault.TimeScales
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.ui.components.InfoBanner
import com.vettid.core.ui.components.InputGuard
import com.vettid.core.ui.components.UrgentBanner
import com.vettid.core.ui.format.LocalTimeScale
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.format.durationText
import com.vettid.core.ui.format.stagingNoteSuffix
import com.vettid.feature.onboarding.OwnerCheckMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** What the shell shows of the daily owner check (VAULT-MESSAGING §3.6.5) at one moment. */
data class OwnerCheckGateState(
    /** Past the deadline: the check comes before any other use of the app (never over an action in progress). */
    val gated: Boolean = false,
    /** Within the hour before the deadline (divided by the time scale): the early warning, with "check now". */
    val warning: Boolean = false,
    /** How long before the deadline the warning starts, in seconds (1 h, divided by the vault's time scale). */
    val warningSeconds: Long = OwnerCheckView.WARNING.seconds,
    val deadline: Instant? = null,
    /** The hold is off (§3.6.7): the persistent indicator, with its end date if any. */
    val holdOff: Boolean = false,
    val holdOffUntil: Instant? = null,
    val notices: List<OwnerCheckNotice> = emptyList(),
    /** (Release R, §11.14.5) The vault could not record that the member was here: the presence notice. */
    val presenceUnrecorded: Boolean = false,
    /**
     * Gated, and [OWNER_CHECK_DEFERRAL_S] (divided by the time scale) have passed since the deadline: the check no longer
     * waits for input in progress. Derived from the vault's deadline, so a new composition, an app lock, a stay in the
     * background or a new process do not start the wait again.
     */
    val overdue: Boolean = false,
    /** A call is live (`CallAvailability.IN_CALL`): the check waits for it to end, without a time limit (§3.6.5). */
    val inCall: Boolean = false,
) {
    companion object {
        fun of(
            v: OwnerCheckView?,
            notices: List<OwnerCheckNotice>,
            now: Instant,
            presenceUnrecorded: Boolean = false,
            timeScale: Long = 1,
        ): OwnerCheckGateState = OwnerCheckGateState(
            gated = v?.gated(now) == true,
            warning = v?.warning(now, timeScale) == true,
            warningSeconds = OwnerCheckView.warningFor(timeScale).seconds,
            deadline = v?.deadline,
            holdOff = v?.holdOff(now) == true,
            holdOffUntil = v?.holdOffUntil,
            notices = notices,
            presenceUnrecorded = presenceUnrecorded,
            overdue = v?.overdue(now, timeScale) == true,
        )

        /** Past the deadline by the deferral; due for a reason other than the clock (no moment to count from): at once. */
        private fun OwnerCheckView.overdue(now: Instant, timeScale: Long): Boolean {
            val d = deadline
            return when {
                !gated(now) -> false
                d == null || now.isBefore(d) -> true
                else -> !now.isBefore(d.plusSeconds(TimeScales.divide(OWNER_CHECK_DEFERRAL_S, timeScale)))
            }
        }
    }
}

/** The shell's view of the owner check: re-evaluated as time passes, so that the deadline gates on its own. */
@HiltViewModel
class OwnerCheckGateViewModel @Inject constructor(
    private val repo: OwnerCheckRepository,
    private val timeScale: TimeScaleSource = TimeScaleSource.RealTime,
    private val calls: CallAccess = NoCallAccess,
) : ViewModel() {
    /** Now, again at the next moment that changes the state (a warning, the deadline, the end of the deferral), else every tick. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val clock: Flow<Instant> = combine(repo.ownerCheck, timeScale.timeScale) { v, t -> v to t.scale }
        .transformLatest { (v, scale) ->
            while (true) {
                val now = Instant.now()
                emit(now)
                delay(nextWakeMillis(v, now, scale, TICK_MS))
            }
        }

    val gate: StateFlow<OwnerCheckGateState> =
        combine(
            combine(repo.ownerCheck, repo.notices, clock, repo.presenceUnrecorded, timeScale.timeScale) { v, n, now, p, t ->
                OwnerCheckGateState.of(v, n, now, p, t.scale)
            },
            calls.availability,
        ) { g, a -> g.copy(inCall = a == CallAvailability.IN_CALL) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_MS),
                OwnerCheckGateState.of(
                    repo.ownerCheck.value,
                    repo.notices.value,
                    Instant.now(),
                    repo.presenceUnrecorded.value,
                    timeScale.timeScale.value.scale,
                ).copy(inCall = calls.availability.value == CallAvailability.IN_CALL),
            )

    /** (Release R) The owner check right after an unlock that cancelled a recovery did not pass: show the check screen. */
    val checkAfterCancel: StateFlow<Boolean> = repo.checkAfterCancel

    fun consumeCheckAfterCancel() = repo.consumeCheckAfterCancel()

    /** The app came to the foreground: the vault may have passed its deadline meanwhile. */
    fun onForeground() {
        viewModelScope.launch { runCatching { repo.refreshOwnerCheck() } }
    }

    fun turnHoldOn() {
        viewModelScope.launch {
            try {
                repo.turnHoldOn()
            } catch (_: VaultFailure) {
                // the indicator stays; Settings shows the failure when the member tries there
            }
        }
    }

    fun dismissNotices() {
        viewModelScope.launch { runCatching { repo.dismissNotices() } }
    }

    private companion object {
        const val TICK_MS = 15_000L
        const val STOP_MS = 5_000L
    }
}

/**
 * The owner check's banners above the shell's screens: the early warning (§3.6.5, from 1 h before the deadline,
 * "check now"), the persistent "hold is off" indicator (§3.6.7) and the `owner_check.*` feed items (a failed
 * check, the ten-failure lock, a hold change). Returns whether any banner is shown. [first] says whether a
 * banner is the topmost (which pads for the status bar).
 */
@Composable
fun OwnerCheckBanners(
    gate: OwnerCheckGateState,
    first: Boolean,
    onCheckNow: () -> Unit,
    onHoldOn: () -> Unit,
    onDismissNotices: () -> Unit,
) {
    var top = first
    val notice = gate.notices.firstOrNull()
    if (notice != null) {
        val text = noticeText(notice, gate.notices.size)
        if (notice.urgent) {
            val ok = stringResource(R.string.owner_check_notice_ok)
            UrgentBanner(text, ok, onDismissNotices, Modifier.testTag("owner_check_notice"), statusBarPadding = top)
        } else {
            InfoBanner(text, Modifier.testTag("owner_check_notice"), stringResource(R.string.owner_check_notice_ok), onDismissNotices, top)
        }
        top = false
    }
    if (gate.warning && !gate.gated) {
        // Without a deadline (not reached from [OwnerCheckGateState.of]: the warning needs one): in real time the approved
        // "within the hour", on a vault with a time scale the warning's span as it runs there.
        val text = gate.deadline?.let { stringResource(R.string.owner_check_warning, Times.time(it)) }
            ?: if (LocalTimeScale.current.scale <= 1) {
                stringResource(R.string.owner_check_warning_soon)
            } else {
                stringResource(R.string.owner_check_warning_soon_scaled, durationText(gate.warningSeconds))
            }
        InfoBanner(text, Modifier.testTag("owner_check_warning"), stringResource(R.string.owner_check_now), onCheckNow, top)
        top = false
    }
    if (gate.holdOff) {
        val text = gate.holdOffUntil?.let { stringResource(R.string.owner_check_hold_off_until, Times.full(it)) }
            ?: stringResource(R.string.owner_check_hold_off)
        InfoBanner(text, Modifier.testTag("owner_check_hold_off"), stringResource(R.string.owner_check_hold_on), onHoldOn, top)
        top = false
    }
    // Release R (§11.14.5): shown until a later answer or vault.status says the presence was recorded; the member cannot
    // act on it, so it has no action.
    if (gate.presenceUnrecorded) {
        InfoBanner(
            stringResource(com.vettid.feature.onboarding.R.string.ownercheck_presence_title) + " " +
                com.vettid.feature.onboarding.presenceBody() + stagingNoteSuffix(),
            Modifier.testTag("owner_check_presence_banner"),
            statusBarPadding = top,
        )
    }
}

/** `owner_check.hold_changed`'s `ref` (§3.6.7): `on`, `off`, `off_until:<ts>` or `on:expired`. */
@Composable
private fun holdChangedText(ref: String?): String {
    val until = ref?.takeIf { it.startsWith(OFF_UNTIL) }?.let { runCatching { Instant.parse(it.removePrefix(OFF_UNTIL)) }.getOrNull() }
    return when {
        until != null -> stringResource(R.string.owner_check_notice_hold_off_until, Times.full(until))
        ref == "off" || ref?.startsWith(OFF_UNTIL) == true -> stringResource(R.string.owner_check_notice_hold_off)
        ref == "on:expired" -> stringResource(R.string.owner_check_notice_hold_expired)
        else -> stringResource(R.string.owner_check_notice_hold_on)
    }
}

private const val OFF_UNTIL = "off_until:"

/** Whether [OwnerCheckBanners] shows anything for [gate]. */
fun OwnerCheckGateState.bannerShown(): Boolean = notices.isNotEmpty() || (warning && !gated) || holdOff || presenceUnrecorded

@Composable
private fun noticeText(n: OwnerCheckNotice, count: Int): String {
    val main = when (n.kind) {
        "owner_check.locked" -> stringResource(R.string.owner_check_notice_locked)
        "owner_check.failed" -> stringResource(
            if (n.ref == "password") R.string.owner_check_notice_failed_password else R.string.owner_check_notice_failed_pin,
        )
        else -> holdChangedText(n.ref)
    }
    val at = n.at?.let { Times.short(it) }
    val withTime = at?.let { stringResource(R.string.owner_check_notice_at, main, it) } ?: main
    return if (count > 1) stringResource(R.string.owner_check_notice_more, withTime, count - 1) else withTime
}

/**
 * How long the daily owner check waits for input in progress once it came due (VAULT-MESSAGING §3.6.5, owner decision
 * 2026-11-03): 5 minutes (divided by the vault's time scale on staging), counted from the vault's deadline.
 */
internal const val OWNER_CHECK_DEFERRAL_S = 5 * 60L

/**
 * What covers the app now. The check that is due ([gated]) comes at once, except while it must wait: a call is live
 * ([inCall], no time limit), or the screen in front holds input or an operation in progress ([inProgress]) and it is not a
 * drawer screen ([onList]) and the deferral has not run out ([overdue]). A check the member asked for ([asked]) shows as asked.
 */
internal fun ownerCheckMode(
    gated: Boolean,
    onList: Boolean,
    inProgress: Boolean,
    overdue: Boolean,
    inCall: Boolean,
    asked: OwnerCheckMode?,
): OwnerCheckMode? {
    val waits = inCall || (!onList && inProgress && !overdue)
    return if (gated && !waits) OwnerCheckMode.GATED else asked
}

/**
 * [ownerCheckMode] for the shell's [gate], and once the check covers the app it stays GATED until it is passed (`gate.gated`
 * turns false): input that appears under it (a focused field, a keyboard still open) must not take it away, which would also
 * dispose the check's own entry. Saved, so a new composition keeps it. The check is drawn over the screen, which stays
 * composed, so what was typed is there when it passes.
 */
@Composable
internal fun rememberOwnerCheckMode(
    gate: OwnerCheckGateState,
    onList: Boolean,
    guard: InputGuard,
    asked: OwnerCheckMode?,
): OwnerCheckMode? {
    var latched by rememberSaveable { mutableStateOf(false) }
    // A new composition (rotation, an unlock of the app lock, a restored process) starts with the guard at 0: the screens
    // report their input in effects that run after the first frame. Until one frame has passed, a check that may still wait
    // is not shown (and so not latched); one that is overdue or already latched does not wait.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        settled = true
    }
    val mode = when {
        gate.gated && latched -> OwnerCheckMode.GATED
        !settled && !gate.overdue -> asked
        else -> ownerCheckMode(gate.gated, onList, guard.active, gate.overdue, gate.inCall, asked)
    }
    SideEffect { latched = gate.gated && mode == OwnerCheckMode.GATED }
    return mode
}

/**
 * How long the gate's clock sleeps from [now]: until the next moment that changes what it shows (the early warning, the
 * deadline, the end of the deferral, each divided by the time scale as on the vault), at most [tickMs]. The state is then
 * re-read on time, not up to a tick late.
 */
internal fun nextWakeMillis(v: OwnerCheckView?, now: Instant, timeScale: Long, tickMs: Long): Long {
    val d = v?.deadline
    val next = d?.let {
        listOf(
            it.minus(OwnerCheckView.warningFor(timeScale)),
            it,
            it.plusSeconds(TimeScales.divide(OWNER_CHECK_DEFERRAL_S, timeScale)),
        ).filter { m -> m.isAfter(now) }.minOrNull()
    }
    // One millisecond past the mark, so that the state read then is on its far side.
    return next?.let { Duration.between(now, it).toMillis() + 1 }?.coerceIn(1, tickMs) ?: tickMs
}
