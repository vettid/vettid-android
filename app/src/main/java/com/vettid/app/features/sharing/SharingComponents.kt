package com.vettid.app.features.sharing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SectionCard(
    title: String,
    subtitle: String,
    count: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (count != null && count > 0) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun StatusPill(label: String, container: Color) {
    Surface(color = container, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun SharedItemRow(item: SharedItem, onRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (item.kind) {
                SharedItem.Kind.DATA -> Icons.Default.PersonOutline
                SharedItem.Kind.SECRET -> Icons.Default.Lock
                SharedItem.Kind.WALLET -> Icons.Default.AccountBalanceWallet
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (item.category.isNotEmpty()) {
                Text(item.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        when (item.status) {
            RequestStatus.AVAILABLE -> {
                FilledTonalButton(onClick = onRequest, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Request", style = MaterialTheme.typography.labelMedium)
                }
            }
            RequestStatus.PENDING -> StatusPill("Pending", MaterialTheme.colorScheme.tertiaryContainer)
            RequestStatus.APPROVED -> StatusPill("Approved", MaterialTheme.colorScheme.primaryContainer)
            RequestStatus.DENIED -> StatusPill("Denied", MaterialTheme.colorScheme.errorContainer)
            RequestStatus.EXPIRED -> StatusPill("Expired", MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
internal fun SharePolicyRow(
    row: SharePolicyRow,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = buildString {
                    append(row.category)
                    append(" · ")
                    append(if (row.allowed) "Allowed" else "Denied")
                    if (row.allowed) {
                        append(" · ")
                        append(row.tier)
                        append(" · ")
                        append(row.retention)
                        if (row.rateLimitPerHour > 0) {
                            append(" · ${row.rateLimitPerHour}/hr")
                        }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (row.allowed) Icons.Default.Check else Icons.Default.Block,
            contentDescription = null,
            tint = if (row.allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PolicyItemEditorSheet(
    row: SharePolicyRow,
    onDismiss: () -> Unit,
    onSave: (SharePolicyRow) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var allowed by remember(row.key) { mutableStateOf(row.allowed) }
    var tier by remember(row.key) { mutableStateOf(row.tier) }
    var retention by remember(row.key) { mutableStateOf(row.retention) }
    var rateLimit by remember(row.key) { mutableStateOf(row.rateLimitPerHour) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(row.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(row.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow this connection to request", modifier = Modifier.weight(1f))
                Switch(checked = allowed, onCheckedChange = { allowed = it })
            }

            if (allowed) {
                Text("Tier", style = MaterialTheme.typography.labelMedium)
                SegmentedRow(
                    options = listOf("on_demand" to "On demand", "consent" to "Consent"),
                    selected = tier,
                    onSelect = { tier = it },
                )
                Text("Retention", style = MaterialTheme.typography.labelMedium)
                SegmentedRow(
                    options = listOf("session" to "Session", "time_limited" to "Time-limited", "until_revoked" to "Until revoked"),
                    selected = retention,
                    onSelect = { retention = it },
                )
                Text(
                    text = "Rate limit · ${if (rateLimit == 0) "unlimited" else "$rateLimit/hr"}",
                    style = MaterialTheme.typography.labelMedium,
                )
                SegmentedRow(
                    options = listOf("0" to "Unlimited", "5" to "5/hr", "30" to "30/hr", "100" to "100/hr"),
                    selected = rateLimit.toString(),
                    onSelect = { rateLimit = it.toIntOrNull() ?: 0 },
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(row.copy(
                            allowed = allowed,
                            tier = tier,
                            retention = retention,
                            rateLimitPerHour = rateLimit,
                        ))
                    },
                ) { Text("Save") }
            }
        }
    }
}

@Composable
internal fun SegmentedRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
