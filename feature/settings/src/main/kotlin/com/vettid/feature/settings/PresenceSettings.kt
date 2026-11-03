package com.vettid.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.presence.PresenceAvailability
import com.vettid.core.data.presence.PresencePolicy
import com.vettid.core.data.presence.PresenceRepository
import com.vettid.core.data.presence.PresenceShare
import com.vettid.core.data.presence.PresenceState
import com.vettid.core.data.presence.PresenceText
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** Settings → Privacy → Presence (ANDROID-PLAN §4 "Presence"). */
@Serializable
data object PresenceSettingsRoute

/** A connection the member can pick as an exception: a person, active. Services are not offered (a service is not a person). */
data class PresencePerson(val id: String, val name: String?)

/**
 * The Presence screen's state. **Nothing is held** (CLAUDE.md owner check, `HoldsInput`): every choice is sent to the vault
 * at once, so the screen has no unsaved edit and does not report one ([holdsInput] is false by construction).
 */
data class PresenceSettingsUiState(
    val available: Boolean = false,
    val policy: PresencePolicy? = null,
    val people: List<PresencePerson> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val errorCode: String? = null,
) {
    val holdsInput: Boolean get() = false

    /** The 1,024 exceptions the vault keeps are all used: no more can be picked. */
    val atLimit: Boolean get() = (policy?.except?.size ?: 0) >= PresencePolicy.MAX_EXCEPT

    /** "Except…" under all, "Only…" under no one. */
    val exceptionsInvert: PresenceShare? get() = policy?.share
}

@HiltViewModel
class PresenceSettingsViewModel @Inject constructor(
    private val presence: PresenceRepository,
    private val connections: ConnectionsRepository,
    private val prefs: PreferencesRepository,
) : ViewModel() {
    private val local = MutableStateFlow(PresenceSettingsUiState())

    val uiState: StateFlow<PresenceSettingsUiState> = combine(local, presence.availability, presence.policy, connections.connections) {
        s, a, p, cs ->
        s.copy(
            available = a == PresenceAvailability.AVAILABLE,
            policy = p,
            people = cs.filter { it.state == ConnectionState.ACTIVE && !it.service }
                .map { PresencePerson(it.id, AccountNames.cleaned(it.displayName) ?: AccountNames.cleaned(it.name)) }
                .sortedBy { it.name.orEmpty().lowercase() },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PresenceSettingsUiState())

    init {
        refresh()
    }

    /** Reads the policy again (`presence.get`): on opening and on every return to the screen. */
    fun refresh() {
        viewModelScope.launch {
            try {
                presence.refreshPolicy()
                local.update { it.copy(loading = false, error = null) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loading = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    fun setState(state: PresenceState) = change { it.copy(state = state) }

    /** Who can see it. The exceptions start again: they turn the other choice round, so they would mean the opposite. */
    fun setShare(share: PresenceShare) = change { it.withShare(share) }

    /** Adds [id] to the exceptions, or takes it out. At most [PresencePolicy.MAX_EXCEPT]. */
    fun setException(id: String, on: Boolean) = change {
        when {
            on && id !in it.except && it.except.size >= PresencePolicy.MAX_EXCEPT -> it
            on -> it.withExcept(it.except + id)
            else -> it.withExcept(it.except - id)
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    private fun change(f: (PresencePolicy) -> PresencePolicy) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                presence.update(f)
                // The member has chosen: the one-time card on Connections has nothing left to ask.
                prefs.setPresenceCardAnswered()
                local.update { it.copy(busy = false) }
            } catch (e: VaultFailure) {
                local.update { it.copy(busy = false, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }
}

@Composable
fun PresenceSettingsRouteContent(vm: PresenceSettingsViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    PresenceSettingsContent(state, vm::setState, vm::setShare, vm::setException, vm::dismissError, onBack)
}

/**
 * Settings → Privacy → Presence: **Status** (Available, Busy, Away, Invisible with its note), **Who can see it** (all my
 * connections or no one) and **Exceptions** ("Except…" under all, "Only…" under no one), picked from the connection list.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun PresenceSettingsContent(
    state: PresenceSettingsUiState,
    onState: (PresenceState) -> Unit,
    onShare: (PresenceShare) -> Unit,
    onException: (String, Boolean) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    DetailScaffold(onBackClick = onBack, background = VettIdTheme.colors.groupedBackground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl).testTag("presence_settings")) {
            LargeTitle(stringResource(R.string.settings_presence_title))
            state.error?.let { e ->
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = "",
                    body = failureText(e.messageRes(), e.vaultCode(state.errorCode)),
                    modifier = Modifier.padding(horizontal = Spacing.s),
                    actions = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.settings_done)) } },
                )
            }
            val policy = state.policy ?: return@Column
            SettingsSectionHeader(stringResource(R.string.settings_presence_status))
            SettingsGroup {
                Column(Modifier.selectableGroup()) {
                    PresenceState.entries.forEach { s ->
                        ChoiceLine(
                            stringResource(PresenceText.stateName(s)),
                            if (s == PresenceState.INVISIBLE) stringResource(R.string.settings_presence_invisible_option) else null,
                            selected = policy.state == s,
                            enabled = !state.busy,
                            tag = "presence_state_${s.wire}",
                        ) { onState(s) }
                    }
                }
            }
            if (policy.state == PresenceState.INVISIBLE) {
                Text(
                    stringResource(R.string.settings_presence_invisible_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = INDENT, vertical = Spacing.s).testTag("presence_invisible_note"),
                )
            }
            SettingsSectionHeader(stringResource(R.string.settings_presence_who))
            SettingsGroup {
                Column(Modifier.selectableGroup()) {
                    for (share in PresenceShare.entries) {
                        ChoiceLine(
                            stringResource(shareLabel(share)),
                            body = null,
                            selected = policy.share == share,
                            enabled = !state.busy,
                            tag = "presence_share_${share.wire}",
                        ) { onShare(share) }
                    }
                }
            }
            SettingsSectionHeader(stringResource(R.string.settings_presence_exceptions))
            val inverts = policy.share == PresenceShare.ALL
            Text(
                stringResource(if (inverts) R.string.settings_presence_except else R.string.settings_presence_only),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = INDENT, vertical = Spacing.xs).testTag("presence_exceptions_label"),
            )
            SettingsGroup {
                Column {
                    state.people.forEach { p ->
                        val picked = p.id in policy.except
                        PickLine(
                            p.name ?: stringResource(R.string.settings_presence_name_not_shared),
                            picked,
                            // At the limit only the ones already picked can be changed (taken out).
                            enabled = !state.busy && (picked || !state.atLimit),
                            tag = "presence_person_${p.id}",
                        ) { onException(p.id, it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceLine(label: String, body: String?, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun PickLine(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(Spacing.l))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

private val INDENT = Spacing.gutter + Spacing.xs

@androidx.annotation.StringRes
private fun shareLabel(s: PresenceShare): Int =
    if (s == PresenceShare.ALL) R.string.settings_presence_all else R.string.settings_presence_none
