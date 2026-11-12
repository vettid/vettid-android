package com.vettid.feature.connections

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.theme.Spacing

/**
 * (C2, VAULT-MESSAGING §10.10 "Permission and limits") The switch "Calls with <First>": off stops calls both ways and
 * the other member is not told; and, while the vault limits the connection, why its calls do not ring. [first] is
 * already isolated.
 */
@Composable
internal fun CallsCard(allowed: Boolean, limited: Boolean, first: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    DetailCard(Modifier.testTag("detail_calls")) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = allowed, enabled = enabled, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.connections_calls_switch, first),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = allowed, onCheckedChange = null, enabled = enabled)
        }
        val note = when {
            !allowed -> stringResource(R.string.connections_calls_off, first)
            limited -> stringResource(R.string.connections_calls_limited, first)
            else -> null
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs).testTag("detail_calls_note"),
            )
        }
    }
}
