package com.vettid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.R
import com.vettid.core.data.vault.OwnerCheckNotice
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.ui.components.InfoBanner
import com.vettid.core.ui.components.UrgentBanner
import com.vettid.core.ui.format.Times
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** What the shell shows of the daily owner check (VAULT-MESSAGING §3.6.5) at one moment. */
data class OwnerCheckGateState(
    /** Past the deadline: the check comes before any other use of the app (never over an action in progress). */
    val gated: Boolean = false,
    /** Within the hour before the deadline: the early warning, with "check now". */
    val warning: Boolean = false,
    val deadline: Instant? = null,
    /** The hold is off (§3.6.7): the persistent indicator, with its end date if any. */
    val holdOff: Boolean = false,
    val holdOffUntil: Instant? = null,
    val notices: List<OwnerCheckNotice> = emptyList(),
) {
    companion object {
        fun of(v: OwnerCheckView?, notices: List<OwnerCheckNotice>, now: Instant): OwnerCheckGateState = OwnerCheckGateState(
            gated = v?.gated(now) == true,
            warning = v?.warning(now) == true,
            deadline = v?.deadline,
            holdOff = v?.holdOff(now) == true,
            holdOffUntil = v?.holdOffUntil,
            notices = notices,
        )
    }
}

/** The shell's view of the owner check: re-evaluated as time passes, so that the deadline gates on its own. */
@HiltViewModel
class OwnerCheckGateViewModel @Inject constructor(private val repo: OwnerCheckRepository) : ViewModel() {
    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(TICK_MS)
        }
    }

    val gate: StateFlow<OwnerCheckGateState> =
        combine(repo.ownerCheck, repo.notices, clock) { v, n, now -> OwnerCheckGateState.of(v, n, now) }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_MS),
            OwnerCheckGateState.of(repo.ownerCheck.value, repo.notices.value, Instant.now()),
        )

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
        val text = gate.deadline?.let { stringResource(R.string.owner_check_warning, Times.time(it)) }
            ?: stringResource(R.string.owner_check_warning_soon)
        InfoBanner(text, Modifier.testTag("owner_check_warning"), stringResource(R.string.owner_check_now), onCheckNow, top)
        top = false
    }
    if (gate.holdOff) {
        val text = gate.holdOffUntil?.let { stringResource(R.string.owner_check_hold_off_until, Times.full(it)) }
            ?: stringResource(R.string.owner_check_hold_off)
        InfoBanner(text, Modifier.testTag("owner_check_hold_off"), stringResource(R.string.owner_check_hold_on), onHoldOn, top)
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
fun OwnerCheckGateState.bannerShown(): Boolean = notices.isNotEmpty() || (warning && !gated) || holdOff

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
