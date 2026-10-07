package com.vettid.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.NameRequestState
import com.vettid.core.data.vault.NameRequestView
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import kotlinx.serialization.Serializable
import java.time.Instant

/** Settings → Shared profile (ANDROID-PLAN 0.1.10). */
@Serializable
data object SharedProfileRoute

/** The account's name change (ANDROID-PLAN 0.1.10, VAULT-MESSAGING 0.18.0 §10.8). */
@Serializable
data object ChangeNameRoute

/** The hero glyph above a [FormScaffold] title (centred by the scaffold), as on the owner-check screen. */
@Composable
private fun HeaderGlyph(icon: ImageVector) {
    Spacer(Modifier.height(Spacing.l))
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(40.dp))
}

/** The date a refused-too-soon change may be made again (§10.8: "on <allowed_after>"). */
private fun dayOf(at: Instant): String = Times.dayLabel(Times.day(at))

/**
 * What to say about the account's names (VAULT-MESSAGING 0.18.0 §10.8 "What the app shows"): a pending request,
 * a refusal (with its reason), or that the next change must wait (the snapshot's `name_change.allowed_after`);
 * null when there is nothing to say (no request, or the last one was applied and nothing waits).
 */
@Composable
fun nameStatusText(account: AccountInfo?, now: Instant = Instant.now()): String? {
    val r = account?.nameRequest
    val tooSoon = account?.nameAllowedAfter?.takeIf { it.isAfter(now) }
    return when {
        r?.state == NameRequestState.PENDING ->
            stringResource(R.string.settings_name_status_pending, AccountNames.isolate("${r.firstName} ${r.lastName}"))
        r?.state == NameRequestState.REFUSED -> refusalText(r, account.nameAllowedAfter)
        tooSoon != null -> stringResource(R.string.settings_name_too_soon, dayOf(tooSoon))
        else -> null
    }
}

@Composable
private fun refusalText(r: NameRequestView, allowedAfter: Instant?): String = when (r.reason) {
    NameRequestView.REASON_INVALID -> stringResource(R.string.settings_name_refused_invalid)
    NameRequestView.REASON_ACCOUNT -> stringResource(R.string.settings_name_refused_account)
    // too_soon, or a reason this app does not know: the 30-day rule is the usual one.
    else -> tooSoonText(allowedAfter)
}

@Composable
private fun tooSoonText(allowedAfter: Instant?): String = allowedAfter
    ?.let { stringResource(R.string.settings_name_too_soon, dayOf(it)) }
    ?: stringResource(R.string.settings_name_too_soon_undated)

/** What the shared-profile screen can ask for. */
data class SharedProfileActions(
    val onBack: () -> Unit = {},
    val onDisplayName: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onChangeName: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * Settings → Shared profile (ANDROID-PLAN 0.1.10, VAULT-MESSAGING 0.18.0 §10.8): the name on the VettID account,
 * read-only (every connection sees it; never called verified) with "Change name"; the optional display name, edited
 * here; the vault key fingerprint connections see.
 */
@Composable
fun SharedProfileContent(state: SharedProfileUiState, actions: SharedProfileActions) {
    FormScaffold(
        title = stringResource(R.string.settings_profile_title),
        body = stringResource(R.string.settings_profile_body),
        primaryLabel = stringResource(R.string.settings_profile_save),
        onPrimary = actions.onSave,
        primaryEnabled = state.saveAllowed,
        busy = state.busy,
        onBack = actions.onBack,
        modifier = Modifier.testTag("shared_profile"),
        header = { HeaderGlyph(Icons.Outlined.Person) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            DetailCard(Modifier.testTag("profile_names")) {
                Text(stringResource(R.string.settings_profile_names), style = MaterialTheme.typography.labelLarge)
                Text(
                    state.fullName ?: stringResource(R.string.settings_profile_names_none),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("profile_full_name"),
                )
                Text(
                    stringResource(R.string.settings_profile_names_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                nameStatusText(state.account)?.let {
                    Spacer(Modifier.height(Spacing.s))
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("name_status"))
                }
                TextButton(
                    onClick = actions.onChangeName,
                    modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("change_name"),
                ) { Text(stringResource(R.string.settings_profile_change_name)) }
            }
            OutlinedTextField(
                value = state.displayName,
                onValueChange = actions.onDisplayName,
                label = { Text(stringResource(R.string.settings_profile_display_name)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (state.tooLong) {
                                R.string.settings_profile_display_name_too_long
                            } else {
                                R.string.settings_profile_display_name_hint
                            },
                        ),
                    )
                },
                isError = state.tooLong,
                singleLine = true,
                enabled = state.profile != null && !state.busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("display_name"),
            )
            if (state.saved) {
                NoticeCard(
                    NoticeKind.SUCCESS,
                    stringResource(R.string.settings_profile_title),
                    stringResource(R.string.settings_profile_saved),
                    modifier = Modifier.testTag("display_name_saved"),
                    actions = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.settings_done)) } },
                )
            }
            state.error?.let {
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.settings_profile_error),
                    stringResource(it.messageRes()),
                    actions = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.settings_done)) } },
                )
            }
            state.profile?.fingerprint?.let { fp ->
                DetailCard(Modifier.testTag("profile_fingerprint")) {
                    Text(stringResource(R.string.settings_profile_key), style = MaterialTheme.typography.labelLarge)
                    Text(fp, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                    Text(
                        stringResource(R.string.settings_profile_key_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(R.string.settings_profile_items),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the change-name flow can ask for. */
data class ChangeNameActions(
    val onFirst: (String) -> Unit = {},
    val onLast: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onPin: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onBackToNames: () -> Unit = {},
    /** Leaves the flow. */
    val onClose: () -> Unit = {},
)

/** The change-name flow for [state] (stateless): the names, then the PIN and password, then the request's state. */
@Composable
fun ChangeNameContent(state: ChangeNameUiState, actions: ChangeNameActions, now: Instant = Instant.now()) {
    when (state.step) {
        ChangeNameStep.NAMES -> NamesStep(state, actions, now)
        ChangeNameStep.CONFIRM -> ConfirmStep(state, actions)
        ChangeNameStep.SENT -> SentStep(state, actions)
    }
}

@Composable
private fun nameError(check: AccountNames.Check): String? = when (check) {
    AccountNames.Check.OK -> null
    AccountNames.Check.EMPTY -> stringResource(R.string.settings_name_empty)
    AccountNames.Check.TOO_LONG -> stringResource(R.string.settings_name_too_long)
    AccountNames.Check.INVALID -> stringResource(R.string.settings_name_invalid)
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun NamesStep(state: ChangeNameUiState, actions: ChangeNameActions, now: Instant) {
    // too_soon from the vault, or the snapshot's allowed_after still ahead (§10.8): no change can be applied yet.
    val snapshotTooSoon = state.account?.nameChangeTooSoon(now) == true
    val tooSoon = state.tooSoon || snapshotTooSoon
    val allowedAfter = state.allowedAfter ?: state.account?.nameAllowedAfter?.takeIf { it.isAfter(now) }
    FormScaffold(
        title = stringResource(R.string.settings_name_title),
        body = stringResource(R.string.settings_name_body),
        primaryLabel = stringResource(R.string.settings_name_continue),
        onPrimary = actions.onNext,
        primaryEnabled = !tooSoon,
        onBack = actions.onClose,
        modifier = Modifier.testTag("change_name_names"),
        header = { HeaderGlyph(Icons.Outlined.Badge) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            if (tooSoon) {
                NoticeCard(
                    NoticeKind.INFO,
                    stringResource(R.string.settings_name_title),
                    tooSoonText(allowedAfter),
                    modifier = Modifier.testTag("name_too_soon"),
                )
            }
            state.account?.nameRequest?.takeIf { it.state == NameRequestState.PENDING }?.let { r ->
                Text(
                    stringResource(R.string.settings_name_status_pending, AccountNames.isolate("${r.firstName} ${r.lastName}")),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("name_pending"),
                )
            }
            NameField(
                state.first, actions.onFirst, stringResource(R.string.settings_name_first),
                if (state.checked) nameError(state.firstCheck) else null, ImeAction.Next, {}, "name_first",
            )
            NameField(
                state.last, actions.onLast, stringResource(R.string.settings_name_last),
                if (state.checked) nameError(state.lastCheck) else null, ImeAction.Done, actions.onNext, "name_last",
            )
            val problem = when {
                state.message == ChangeNameMessage.Invalid -> stringResource(R.string.settings_name_refused_invalid)
                state.checked && state.same -> stringResource(R.string.settings_name_same)
                else -> null
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("name_problem")) }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun NameField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: String?,
    ime: ImeAction,
    onIme: () -> Unit,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { Text(error ?: stringResource(R.string.settings_name_rule)) },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ime),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { onIme() }),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun ConfirmStep(state: ChangeNameUiState, actions: ChangeNameActions) {
    val m = state.message
    FormScaffold(
        title = stringResource(R.string.settings_name_confirm_title),
        body = stringResource(R.string.settings_name_confirm_body, AccountNames.isolate(state.requestedName)),
        primaryLabel = stringResource(R.string.settings_name_submit),
        onPrimary = actions.onSubmit,
        primaryEnabled = state.submitAllowed,
        busy = state.busy,
        secondaryLabel = stringResource(R.string.settings_name_back),
        onSecondary = actions.onBackToNames,
        onBack = actions.onBackToNames,
        modifier = Modifier.testTag("change_name_confirm"),
        header = { HeaderGlyph(Icons.Outlined.VerifiedUser) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            val enabled = !state.busy && state.waitSeconds == 0L
            SecretField(
                value = state.pin,
                onValueChange = actions.onPin,
                label = stringResource(R.string.settings_name_pin),
                isPin = true,
                enabled = enabled,
                error = if (m is ChangeNameMessage.BadPin) stringResource(R.string.settings_name_bad_pin) else null,
                imeAction = ImeAction.Next,
                modifier = Modifier.testTag("name_pin"),
            )
            SecretField(
                value = state.password,
                onValueChange = actions.onPassword,
                label = stringResource(R.string.settings_name_password),
                enabled = enabled,
                error = if (m is ChangeNameMessage.BadPassword) stringResource(R.string.settings_name_bad_password) else null,
                onImeAction = actions.onSubmit,
                modifier = Modifier.testTag("name_password"),
            )
            val left = (m as? ChangeNameMessage.BadPin)?.checksLeft ?: (m as? ChangeNameMessage.BadPassword)?.checksLeft
            left?.let {
                Text(
                    pluralStringResource(R.plurals.settings_name_checks_left, it, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it <= WARN_LEFT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("name_checks_left"),
                )
            }
            when {
                state.waitSeconds > 0 -> Text(
                    stringResource(R.string.settings_name_wait, waitText(state.waitSeconds)),
                    modifier = Modifier.testTag("name_wait"),
                )
                m == ChangeNameMessage.Backoff -> Text(stringResource(R.string.settings_name_backoff))
                m is ChangeNameMessage.Failed -> NoticeCard(
                    NoticeKind.URGENT,
                    stringResource(R.string.settings_name_title),
                    stringResource(m.kind.messageRes()),
                    modifier = Modifier.testTag("name_failed"),
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun SentStep(state: ChangeNameUiState, actions: ChangeNameActions) {
    val r = state.request
    val name = r?.let { AccountNames.isolate("${it.firstName} ${it.lastName}") } ?: AccountNames.isolate(state.requestedName)
    val (title, body, icon) = when (r?.state) {
        NameRequestState.APPLIED -> Triple(
            stringResource(R.string.settings_name_applied_title),
            stringResource(R.string.settings_name_applied_body, name),
            Icons.Outlined.CheckCircle,
        )
        NameRequestState.REFUSED -> Triple(
            stringResource(R.string.settings_name_refused_title),
            refusalText(r, state.account?.nameAllowedAfter),
            Icons.Outlined.ErrorOutline,
        )
        else -> Triple(
            stringResource(R.string.settings_name_requested_title),
            stringResource(R.string.settings_name_requested_body, name),
            Icons.Outlined.Schedule,
        )
    }
    FormScaffold(
        title = title,
        body = body,
        primaryLabel = stringResource(R.string.settings_name_done),
        onPrimary = actions.onClose,
        modifier = Modifier.testTag("change_name_sent_${(r?.state ?: NameRequestState.PENDING).name.lowercase()}"),
        header = { HeaderGlyph(icon) },
    ) {}
}

private fun waitText(seconds: Long): String = "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

private const val SECONDS_PER_MINUTE = 60L
private const val WARN_LEFT = 3
