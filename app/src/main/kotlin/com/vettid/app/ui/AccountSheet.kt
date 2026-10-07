package com.vettid.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CardMembership
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vettid.app.R
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.SubscriptionInfo
import com.vettid.core.ui.components.AvatarSheet
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsInfoRow
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import com.vettid.feature.settings.nameStatusText
import java.time.Instant

/**
 * Avatar sheet (ANDROID-PLAN §4, 0.1.10): the name on the member's VettID account (read-only, from the vault's
 * account snapshot: every connection sees it) with "Change name" (the only place it changes, VAULT-MESSAGING 0.18.0
 * §10.8) and the shared profile; vault status, lock vault, the membership and subscription (read-only, §11.13), and
 * the account portal in the browser, where the other changes are made. The app never signs in: no sign-out.
 */
@Suppress("LongParameterList")
@Composable
fun AccountSheet(
    account: AccountInfo?,
    portalUrl: String,
    onDismiss: () -> Unit,
    onLockVault: () -> Unit,
    now: Instant = Instant.now(),
    onChangeName: () -> Unit = {},
    onSharedProfile: () -> Unit = {},
    photo: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    val uri = LocalUriHandler.current
    AvatarSheet(
        photo = photo,
        // First and last name and the full address (owner feedback 2026-10-07; `email` from VAULT-MESSAGING 0.20.0,
        // the masked `email_hint` from an older vault).
        name = account?.fullName ?: account?.displayEmail ?: stringResource(R.string.account_placeholder_name),
        detail = account?.fullName?.let { account.displayEmail } ?: membershipLine(account),
        onDismiss = onDismiss,
        optionsHeader = stringResource(R.string.account_options),
    ) {
        AccountNamesGroup(account, now, onChangeName, onSharedProfile)
        AccountEmailGroup(account)
        AccountDetails(account, now)
        SettingsGroup {
            SettingsRow(
                label = stringResource(R.string.account_vault_status),
                supporting = stringResource(R.string.account_vault_status_value),
                onClick = {},
                icon = Icons.Outlined.Storage,
                showChevron = false,
            )
            SettingsDivider()
            SettingsRow(stringResource(R.string.account_lock_vault), onLockVault, icon = Icons.Outlined.Lock, showChevron = false)
            SettingsDivider()
            SettingsRow(
                stringResource(R.string.account_portal),
                { uri.openUri(portalUrl) },
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                showChevron = false,
                supporting = stringResource(R.string.account_portal_body),
            )
        }
    }
}

/**
 * The account's names (VAULT-MESSAGING 0.18.0 §10.8 "What the app shows"): read-only, "Your connections see this
 * name", the state of a requested change, and "Change name".
 */
@Composable
fun AccountNamesGroup(account: AccountInfo?, now: Instant, onChangeName: () -> Unit, onSharedProfile: () -> Unit) {
    SettingsGroup(Modifier.testTag("account_names")) {
        SettingsInfoRow(
            stringResource(R.string.account_names),
            account?.fullName ?: stringResource(R.string.account_names_none),
            icon = Icons.Outlined.Person,
        )
        Text(
            listOfNotNull(stringResource(R.string.account_names_note), nameStatusText(account, now)).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
                .testTag("account_names_note"),
        )
        SettingsDivider()
        SettingsRow(
            stringResource(R.string.account_change_name),
            onChangeName,
            icon = Icons.Outlined.Edit,
            supporting = stringResource(R.string.account_change_name_body),
            modifier = Modifier.testTag("account_change_name"),
        )
        SettingsDivider()
        SettingsRow(
            stringResource(R.string.account_shared_profile),
            onSharedProfile,
            icon = Icons.Outlined.Share,
            supporting = stringResource(R.string.account_shared_profile_body),
            modifier = Modifier.testTag("account_shared_profile"),
        )
    }
}

/**
 * The account's address (VAULT-MESSAGING 0.20.0 §11.13: the snapshot's full `email`, shown to the member only; the
 * masked `email_hint` from an older vault), labelled "Only you see this address" (ANDROID-PLAN 0.1.11).
 */
@Composable
fun AccountEmailGroup(account: AccountInfo?) {
    val email = account?.displayEmail ?: return
    SettingsGroup(Modifier.testTag("account_email")) {
        SettingsInfoRow(stringResource(R.string.account_email), email, icon = Icons.Outlined.Email)
        Text(
            stringResource(R.string.account_email_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
        )
    }
}

/** The membership in one line, under the address when there are no names to show. */
@Composable
private fun membershipLine(account: AccountInfo?): String = when {
    account == null || !account.hasSnapshot -> stringResource(R.string.account_membership_unknown)
    account.canceled -> stringResource(R.string.account_membership_canceled)
    account.state == STATE_MEMBER -> stringResource(R.string.account_membership_member)
    else -> stringResource(R.string.account_membership_registered)
}

/** Membership, terms, subscription and voting rights, read-only (§11.13). */
@Composable
fun AccountDetails(account: AccountInfo?, now: Instant) {
    if (account == null || !account.hasSnapshot) {
        SettingsGroup(Modifier.testTag("account_details")) {
            SettingsInfoRow(
                stringResource(R.string.account_membership),
                stringResource(R.string.account_membership_waiting),
                icon = Icons.Outlined.Badge,
            )
        }
        return
    }
    SettingsGroup(Modifier.testTag("account_details")) {
        SettingsInfoRow(
            stringResource(R.string.account_membership),
            when {
                account.canceled -> account.deletesAt?.let { stringResource(R.string.account_membership_canceled_until, Times.full(it)) }
                    ?: stringResource(R.string.account_membership_canceled)
                account.state == STATE_MEMBER -> stringResource(R.string.account_membership_member)
                else -> stringResource(R.string.account_membership_registered)
            },
            icon = Icons.Outlined.Badge,
        )
        SettingsDivider()
        SettingsInfoRow(
            stringResource(R.string.account_subscription),
            subscriptionText(account.subscription, now),
            icon = Icons.Outlined.CardMembership,
        )
        if (account.termsNeedAcceptance) {
            SettingsDivider()
            SettingsInfoRow(
                stringResource(R.string.account_terms),
                stringResource(R.string.account_terms_needed),
                icon = Icons.Outlined.Gavel,
                modifier = Modifier.testTag("account_terms_needed"),
            )
        }
        SettingsDivider()
        SettingsInfoRow(
            stringResource(R.string.account_voting),
            stringResource(if (account.votingRights) R.string.account_voting_yes else R.string.account_voting_no),
            icon = Icons.Outlined.HowToVote,
        )
    }
}

@Composable
private fun subscriptionText(s: SubscriptionInfo?, now: Instant): String {
    if (s == null) return stringResource(R.string.account_subscription_none)
    val name = s.typeName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_subscription_default_name)
    val until = s.expiresAt?.let { Times.full(it) }
    return when (s.statusAt(now)) {
        SubscriptionInfo.STATUS_TRIAL -> until?.let { stringResource(R.string.account_subscription_trial_until, name, it) }
            ?: stringResource(R.string.account_subscription_trial, name)
        SubscriptionInfo.STATUS_ACTIVE -> until?.let { stringResource(R.string.account_subscription_active_until, name, it) }
            ?: stringResource(R.string.account_subscription_active, name)
        SubscriptionInfo.STATUS_EXPIRED -> stringResource(R.string.account_subscription_expired, name)
        SubscriptionInfo.STATUS_CANCELED -> stringResource(R.string.account_subscription_canceled, name)
        else -> name
    }
}

private const val STATE_MEMBER = "member"
