package com.vettid.feature.approvals

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.vettid.core.data.account.AccountNames
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
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Refused or not, the share is tried: it says why nothing can be sent (and the request stays).
        actions.onShareOnce()
    }
    val canShare = !a.connectionService
    FormScaffold(
        title = locationTitle(a),
        body = null,
        primaryLabel = if (canShare) stringResource(R.string.approvals_location_once) else null,
        onPrimary = {
            val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            val granted = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            if (granted) actions.onShareOnce() else ask.launch(permissions)
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
        state.locationIssue?.textRes()?.let {
            Spacer(Modifier.height(Spacing.m))
            Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("location_request_issue"))
        }
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
