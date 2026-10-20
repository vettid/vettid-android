package com.vettid.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.TimeScaleSource
import com.vettid.core.data.vault.TimeScaleView
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.titleRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.components.SettingsSwitchRow
import com.vettid.core.ui.format.StagingTimeNote
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.format.scaledSettingText
import com.vettid.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** The owner-check rows of Settings (VAULT-MESSAGING §3.6.2, §3.6.7). */
data class OwnerCheckSettingsUiState(
    /** Null for a vault that has not reported its owner check (older than 0.13.0, or not read yet). */
    val view: OwnerCheckView? = null,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
    /** The interval was shortened: offer a check with the change (§3.6.2). */
    val offerCheck: Boolean = false,
    /** (STAGING-TIME-SCALE) The vault's time scale: the interval also as it runs, and the staging note. */
    val timeScale: TimeScaleView = TimeScaleView(),
)

/** What the owner-check rows can ask for. */
data class OwnerCheckSettingsActions(
    val setIntervalHours: (Int) -> Unit = {},
    /** Off opens the check with the hold change (§3.6.7); on is a plain setting. */
    val setHold: (Boolean) -> Unit = {},
    val checkNow: () -> Unit = {},
    val dismissOffer: () -> Unit = {},
    val dismissError: () -> Unit = {},
)

/** The interval (1–24 h) and the hold switch (§3.6.2, §3.6.7). */
@HiltViewModel
class OwnerCheckSettingsViewModel @Inject constructor(
    private val repo: OwnerCheckRepository,
    timeScale: TimeScaleSource = TimeScaleSource.RealTime,
) : ViewModel() {
    private val local = MutableStateFlow(OwnerCheckSettingsUiState())
    val uiState: StateFlow<OwnerCheckSettingsUiState> =
        combine(local, repo.ownerCheck, timeScale.timeScale) { s, v, t -> s.copy(view = v, timeScale = t) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                OwnerCheckSettingsUiState(view = repo.ownerCheck.value, timeScale = timeScale.timeScale.value),
            )

    init {
        viewModelScope.launch { runCatching { repo.refreshOwnerCheck() } }
    }

    fun setIntervalHours(hours: Int) {
        val seconds = hours * SECONDS_PER_HOUR
        val before = repo.ownerCheck.value?.intervalSeconds ?: OwnerCheckView.MAX_INTERVAL_S
        if (seconds == before) return
        act {
            repo.setCheckInterval(seconds)
            if (seconds < before) local.update { it.copy(offerCheck = true) }
        }
    }

    fun turnHoldOn() = act { repo.turnHoldOn() }

    fun dismissOffer() = local.update { it.copy(offerCheck = false) }

    fun dismissError() = local.update { it.copy(error = null) }

    private fun act(block: suspend () -> Unit) {
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                local.update { it.copy(busy = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    companion object {
        const val SECONDS_PER_HOUR = 3_600L

        /** The interval choices (§3.6.2: 1–24 h). */
        val INTERVAL_HOURS = listOf(1, 2, 4, 8, 12, 24)
    }
}

/** The owner-check rows for the Security group of Settings. */
@Composable
fun OwnerCheckSettingsRows(state: OwnerCheckSettingsUiState, actions: OwnerCheckSettingsActions) {
    val v = state.view ?: return
    var picker by rememberSaveable { mutableStateOf(false) }
    val hours = (v.intervalSeconds / OwnerCheckSettingsViewModel.SECONDS_PER_HOUR).toInt().coerceAtLeast(1)
    val next = v.deadline?.let { stringResource(R.string.settings_owner_check_next, Times.full(it)) }
    val t = state.timeScale
    // STAGING-TIME-SCALE §3 rule 3: the setting in its real units, and as it runs on a scaled vault.
    val every = scaledSettingText(
        pluralStringResource(R.plurals.settings_owner_check_every, hours, hours),
        t.scaled,
        t.scaledSeconds(v.intervalSeconds),
    )
    SettingsDivider()
    SettingsRow(
        stringResource(R.string.settings_owner_check),
        { picker = true },
        icon = Icons.Outlined.Schedule,
        supporting = listOfNotNull(every, next).joinToString(" · "),
        modifier = Modifier.testTag("owner_check_interval"),
    )
    if (t.stagingNote) StagingTimeNote(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs))
    SettingsDivider()
    SettingsRow(
        stringResource(R.string.settings_owner_check_now),
        actions.checkNow,
        icon = Icons.Outlined.VerifiedUser,
        supporting = stringResource(R.string.settings_owner_check_now_body),
        showChevron = false,
        modifier = Modifier.testTag("owner_check_now"),
    )
    SettingsDivider()
    val off = v.holdOff(Instant.now())
    val until = v.holdOffUntil
    SettingsSwitchRow(
        label = stringResource(R.string.settings_owner_check_hold),
        supporting = when {
            !off -> stringResource(R.string.settings_owner_check_hold_on_body)
            until != null -> stringResource(R.string.settings_owner_check_hold_off_until, Times.full(until))
            else -> stringResource(R.string.settings_owner_check_hold_off_body)
        },
        checked = !off,
        onCheckedChange = { on -> if (!state.busy) actions.setHold(on) },
        icon = Icons.Outlined.PauseCircleOutline,
        modifier = Modifier.testTag("owner_check_hold"),
    )
    if (picker) {
        ChoiceDialog(
            title = stringResource(R.string.settings_owner_check),
            options = OwnerCheckSettingsViewModel.INTERVAL_HOURS,
            selected = hours,
            label = {
                scaledSettingText(
                    pluralStringResource(R.plurals.settings_owner_check_every, it, it),
                    t.scaled,
                    t.scaledSeconds(it * OwnerCheckSettingsViewModel.SECONDS_PER_HOUR),
                )
            },
            onPick = {
                picker = false
                actions.setIntervalHours(it)
            },
            onDismiss = { picker = false },
        )
    }
    if (state.offerCheck) {
        ConfirmDialog(
            title = stringResource(R.string.settings_owner_check_offer_title),
            text = stringResource(R.string.settings_owner_check_offer_body),
            confirmLabel = stringResource(R.string.settings_owner_check_now),
            onConfirm = {
                actions.dismissOffer()
                actions.checkNow()
            },
            onDismiss = actions.dismissOffer,
        )
    }
    state.error?.let {
        ConfirmDialog(
            title = stringResource(it.titleRes(R.string.settings_owner_check)),
            text = failureText(it.messageRes(), it.vaultCode(state.errorCode)),
            confirmLabel = stringResource(R.string.settings_done),
            onConfirm = actions.dismissError,
            onDismiss = actions.dismissError,
        )
    }
}
