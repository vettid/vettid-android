package com.vettid.feature.approvals

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.location.SendIssue
import com.vettid.core.data.location.settingsIntent
import com.vettid.core.data.location.textRes
import com.vettid.core.data.social.Approval
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing

/*
 * A location request (ANDROID-PLAN §4 "Location", L1b; VAULT-MESSAGING §10.16): "{First} asks where you are" with the note,
 * **Share once**, **Share for a while…** (the connection's share sheet, with this request's id) and **Not now** (nothing is
 * sent: the vault has no decline, and the request ends by itself after 24 hours). A request from a service is never answered
 * by sharing (a service is never sent a location): only **Not now** is offered.
 */

/** "{First} asks where you are": the first name, else the display name, else "A connection". */
@Composable
internal fun locationTitle(a: Approval.LocationRequest): String {
    val who = AccountNames.isolateOrNull(a.firstName) ?: AccountNames.isolateOrNull(a.connectionName)
        ?: stringResource(R.string.approvals_a_connection)
    return stringResource(R.string.approvals_location_title, who)
}

@Composable
internal fun LocationRequestScreen(
    a: Approval.LocationRequest,
    state: ApprovalDetailUiState,
    actions: DecisionActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Granted: the share starts, once. Refused: the request stays and says why, with Open settings.
        actions.onPermissionAnswered(granted.values.any { it })
    }
    val hasPermission = permissionsGranted(context)
    val canShare = !a.connectionService
    FormScaffold(
        title = locationTitle(a),
        body = null,
        primaryLabel = if (canShare) stringResource(R.string.approvals_location_once) else null,
        onPrimary = {
            if (state.busy) {
                Unit
            } else if (permissionsGranted(context)) {
                actions.onShareOnce()
            } else {
                // Busy before the system's prompt is up, so that a second tap cannot start a second request.
                actions.onPermissionAsked()
                ask.launch(PERMISSIONS)
            }
        },
        busy = state.busy,
        secondaryLabel = stringResource(if (canShare) R.string.approvals_location_while else R.string.approvals_location_not_now),
        onSecondary = if (canShare) actions.onShareWhile else actions.onNotNow,
        tertiaryLabel = if (canShare) stringResource(R.string.approvals_location_not_now) else null,
        onTertiary = actions.onNotNow,
        onBack = actions.onBack,
        modifier = modifier.testTag("approval_detail"),
    ) {
        a.note?.let {
            Text(
                AccountNames.isolate(it),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("location_request_note"),
            )
        }
        a.exp?.let {
            Spacer(Modifier.height(Spacing.s))
            Text(stringResource(R.string.approvals_expires, Times.full(it)), style = MaterialTheme.typography.bodySmall)
        }
        PermissionNotes(canShare && !hasPermission, state.locationIssue)
        state.error?.let {
            Spacer(Modifier.height(Spacing.m))
            Text(
                failureText(it.messageRes(), it.vaultCode(state.errorCode)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("failure"),
            )
        }
    }
}

private val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Either location permission is already granted (it is never asked for before **Share once** is tapped). */
private fun permissionsGranted(context: android.content.Context): Boolean =
    PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** The plan's permission text before the system's prompt, and after a refusal the reason with **Open settings**. */
@Composable
private fun PermissionNotes(explain: Boolean, issue: SendIssue?) {
    val context = LocalContext.current
    if (explain) {
        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(R.string.approvals_location_permission),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("location_request_permission"),
        )
    }
    if (issue != null) {
        Spacer(Modifier.height(Spacing.m))
        val text = if (issue == SendIssue.NO_PERMISSION) R.string.approvals_location_permission_denied else issue.textRes()
        text?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("location_request_issue"))
        }
        issue.settingsIntent(context)?.let { intent ->
            TextButton(
                onClick = { runCatching { context.startActivity(intent) } },
                modifier = Modifier.heightIn(min = 48.dp).testTag("location_request_settings"),
            ) { Text(stringResource(R.string.approvals_location_open_settings)) }
        }
    }
}
