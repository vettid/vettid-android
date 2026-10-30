package com.vettid.feature.approvals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.AuthPurpose
import com.vettid.core.ui.R as UiR
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.theme.Spacing

/**
 * The match code to show for [a] (VAULT-MESSAGING 0.79.0 §10.4): only a sign-in's, only 2 or 3 ASCII digits as the vault
 * sent them. Checked again here, so that a challenge built any other way never shows a malformed code. Never read from
 * the note.
 */
internal fun shownMatchCode(a: Approval.Authentication): String? = AuthPurpose.matchCode(a.purpose, a.matchCode)

/**
 * A sign-in's match code (ANDROID-PLAN 0.1.100): the label and the code in large digits as one element that reads the
 * digits one by one ("Match code 4 2", not "forty-two"), then the instruction as its own text. Plain colours and no icon:
 * a match proves only that the prompt and the sign-in screen belong to the same sign-in, so nothing here looks verified.
 */
@Composable
internal fun MatchCodeBlock(code: String, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.approvals_match_code_a11y, code.toList().joinToString(" "))
    Column(modifier.fillMaxWidth().testTag("match_code")) {
        Column(Modifier.clearAndSetSemantics { contentDescription = spoken }.testTag("match_code_spoken")) {
            Text(
                stringResource(R.string.approvals_match_code_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                code,
                style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum", letterSpacing = 0.2.em),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("match_code_digits"),
            )
        }
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(R.string.approvals_match_code_instruction, code),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("match_code_instruction"),
        )
        Spacer(Modifier.height(Spacing.m))
    }
}

/**
 * The challenger withdrew the ask (ANDROID-PLAN 0.1.101): the notice in plain text (no error colour, never "declined"),
 * and "Mute requests from <name>" as a secondary text action when the connection can be muted.
 */
@Composable
internal fun WithdrawnNotice(a: Approval, canMute: Boolean, busy: Boolean, onAskMute: () -> Unit) {
    val who = AccountNames.isolateOrNull(a.connectionName) ?: stringResource(R.string.approvals_a_connection)
    Spacer(Modifier.height(Spacing.l))
    Text(
        stringResource(R.string.approvals_withdrawn_notice, who),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag("withdrawn_notice"),
    )
    if (canMute) {
        Spacer(Modifier.height(Spacing.s))
        TextButton(
            onClick = onAskMute,
            enabled = !busy,
            modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("withdrawn_mute"),
        ) { Text(stringResource(UiR.string.core_ui_authn_withdrawn_mute, who)) }
    }
}

/** The confirmation of muting a service connection from the withdrawn notice. */
@Composable
internal fun MuteConfirm(a: Approval, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val who = AccountNames.isolateOrNull(a.connectionName) ?: stringResource(R.string.approvals_a_connection)
    ConfirmDialog(
        title = stringResource(UiR.string.core_ui_authn_withdrawn_mute, who),
        text = stringResource(UiR.string.core_ui_authn_withdrawn_mute_confirm_service, who),
        confirmLabel = stringResource(UiR.string.core_ui_authn_withdrawn_mute_confirm_button),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        dismissLabel = stringResource(UiR.string.core_ui_authn_withdrawn_mute_cancel_button),
        modifier = Modifier.testTag("mute_confirm"),
    )
}
