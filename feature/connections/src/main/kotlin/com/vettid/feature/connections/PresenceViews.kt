package com.vettid.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.presence.PresenceReading
import com.vettid.core.data.presence.PresenceText
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.theme.Spacing
import java.time.Instant

/**
 * The line under a connection's name (ANDROID-PLAN §4 "Presence"): "Available", "Busy" or "Away", each with "· active {age}"
 * when the connection said when, "Checking…" while waiting, "Status unknown" otherwise; with a refresh icon (48 dp, described)
 * and, once unknown, why. [first] is the connection's first name for the explanation (it is left out without one).
 */
@Composable
internal fun PresenceLine(reading: PresenceReading, first: String?, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val text = PresenceText.line(res, reading, Instant.now())
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("detail_presence"),
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp).testTag("detail_presence_refresh")) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.connections_presence_refresh))
            }
        }
        val who = first?.let { AccountNames.isolate(it) }
        if (reading == PresenceReading.Unknown && who != null) {
            Text(
                stringResource(R.string.connections_presence_unknown_detail, who),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.xl).testTag("detail_presence_unknown"),
            )
        }
    }
}

/** The switch "Share my status with <First>" and, when off, what <First> sees instead. [first] is already isolated. */
@Composable
internal fun PresenceShareCard(shared: Boolean, first: String, busy: Boolean, onChange: (Boolean) -> Unit) {
    DetailCard(Modifier.testTag("detail_presence_share")) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = shared, enabled = !busy, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.connections_presence_share, first),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = shared, onCheckedChange = null, enabled = !busy)
        }
        if (!shared) {
            Text(
                stringResource(R.string.connections_presence_share_off, first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs).testTag("detail_presence_share_off"),
            )
        }
    }
}

/**
 * The one-time card on Connections (ANDROID-PLAN §4 "Presence", question 23): what presence shows, **Share with everyone**
 * and **Not now**, and where to change it.
 */
@Composable
internal fun PresenceCard(onShare: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    DetailCard(modifier.testTag("presence_card")) {
        Text(stringResource(R.string.connections_presence_card_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.connections_presence_card_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Row(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp).testTag("presence_card_not_now")) {
                Text(stringResource(R.string.connections_presence_card_not_now))
            }
            TextButton(onClick = onShare, modifier = Modifier.heightIn(min = 48.dp).testTag("presence_card_share")) {
                Text(stringResource(R.string.connections_presence_card_share))
            }
        }
        Text(
            stringResource(R.string.connections_presence_card_settings),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
