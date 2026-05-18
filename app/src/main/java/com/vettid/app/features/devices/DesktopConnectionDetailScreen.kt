package com.vettid.app.features.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.nats.AuditEntry
import com.vettid.app.core.nats.DeviceConnectionMetadata
import com.vettid.app.core.nats.DeviceConnectionSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Desktop-client connection detail. Shows hostname/platform/fingerprint/
 * OS the user can verify against what they remember authorizing, plus
 * the current session state (status, expires, key rotations), and a
 * Remove button that revokes the desktop's session vault-side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopConnectionDetailScreen(
    connectionId: String,
    onNavigateBack: () -> Unit,
    viewModel: DesktopConnectionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val isWorking by viewModel.isWorking.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val activity by viewModel.activity.collectAsState()
    var confirmingRemove by remember { mutableStateOf(false) }
    var confirmingEnd by remember { mutableStateOf(false) }

    LaunchedEffect(connectionId) {
        viewModel.load(connectionId)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Desktop") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is DesktopDetailState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is DesktopDetailState.Error -> {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                is DesktopDetailState.Loaded -> {
                    DesktopDetailContent(
                        loaded = s,
                        isWorking = isWorking,
                        activity = activity,
                        onEndSessionClicked = { confirmingEnd = true },
                        onRemoveClicked = { confirmingRemove = true },
                    )
                }
            }
        }
    }

    if (confirmingEnd) {
        val loaded = state as? DesktopDetailState.Loaded
        AlertDialog(
            onDismissRequest = { confirmingEnd = false },
            title = { Text("End session now?") },
            text = {
                Text(
                    "This ends the current session — the desktop will be locked out " +
                        "until a new session is authorized. The pairing stays in place, " +
                        "so you won't need to re-pair: the desktop can ask for a new " +
                        "session and you scan its QR to approve."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingEnd = false
                        loaded?.connectionId?.let { id -> viewModel.endSession(id) { } }
                    }
                ) { Text("End session") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingEnd = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmingRemove) {
        val loaded = state as? DesktopDetailState.Loaded
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove desktop?") },
            text = {
                Text(
                    "This will end the session and erase the desktop's stored " +
                        "credentials on its next sync. The desktop will need to " +
                        "re-pair to reconnect."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRemove = false
                        loaded?.connectionId?.let { id ->
                            viewModel.remove(id) { onNavigateBack() }
                        }
                    }
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DesktopDetailContent(
    loaded: DesktopDetailState.Loaded,
    isWorking: Boolean,
    activity: ActivityState,
    onEndSessionClicked: () -> Unit,
    onRemoveClicked: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header — large avatar + name + status pill
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.DesktopWindows,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            loaded.deviceName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        StatusPill(loaded)
        Spacer(Modifier.height(20.dp))

        // Device info
        SectionCard(title = "Device") {
            DetailRow("Hostname", loaded.metadata?.hostname.orDash())
            DetailRow("Platform", loaded.metadata?.platform.orDash())
            DetailRow("OS", listOfNotNull(
                loaded.metadata?.osName,
                loaded.metadata?.osVersion,
            ).joinToString(" ").ifBlank { "—" })
            DetailRow("App version", loaded.metadata?.appVersion.orDash())
            DetailRow("Client IP", loaded.metadata?.clientIp.orDash())
            DetailRow(
                label = "Binary fingerprint",
                value = loaded.metadata?.binaryFingerprint.orDash(),
                mono = true,
            )
            DetailRow(
                label = "Machine fingerprint",
                value = loaded.metadata?.machineFingerprint.orDash(),
                mono = true,
            )
            DetailRow("Paired", formatIsoDate(loaded.metadata?.firstSeenAt))
        }

        Spacer(Modifier.height(12.dp))

        // Session info
        SectionCard(title = "Session") {
            val sess = loaded.session
            if (sess == null) {
                Text(
                    "No active session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DetailRow("Status", sess.status)
                DetailRow("Started", formatUnixDate(sess.createdAt))
                DetailRow("Expires", formatUnixDate(sess.expiresAt))
                DetailRow("Last active", formatUnixDate(sess.lastActiveAt))
                val rem = sess.expiresAt - System.currentTimeMillis() / 1000
                DetailRow("Remaining", if (rem > 0) formatRemainingLong(rem) else "expired")
                DetailRow("Key rotations", sess.keyRotationCount.toString())

                // Force-end the current session without retiring the
                // pairing. The desktop falls back to its Start-New-
                // Session view so the user can re-authorize without
                // a full re-pair — much lighter than Remove desktop.
                if (sess.status == "active" && rem > 0) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onEndSessionClicked,
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isWorking) "Ending…" else "End session now")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Recent activity — most recent N audit entries scoped to this
        // connection_id. The full history lives on the dedicated
        // Connection History screen for peers; here we show a short
        // summary so the user can see at-a-glance what this desktop
        // has been doing without leaving the detail page.
        SectionCard(title = "Recent activity") {
            when (val a = activity) {
                is ActivityState.Loading -> Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ActivityState.Error -> Text(
                    a.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is ActivityState.Loaded -> {
                    if (a.entries.isEmpty()) {
                        Text(
                            "No recent activity.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        a.entries.forEach { entry ->
                            ActivityRow(entry)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Danger zone — Remove. Wrap-the-button in an error-tinted Card
        // so the action looks distinct from the read-only info sections
        // above.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Remove desktop",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ends the session, wipes the desktop's session key, and " +
                        "tells the desktop client to clear its stored credentials.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onRemoveClicked,
                    enabled = !isWorking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isWorking) "Removing…" else "Remove desktop")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusPill(loaded: DesktopDetailState.Loaded) {
    val nowSec = System.currentTimeMillis() / 1000
    val rem = (loaded.session?.expiresAt ?: 0L) - nowSec
    val active = loaded.session?.status == "active" && rem > 0
    val (text, color) = when {
        active -> "Session · ${formatRemainingLong(rem)}" to MaterialTheme.colorScheme.primary
        loaded.session?.status == "expired" || (loaded.session?.status == "active" && rem <= 0) ->
            "Session expired" to MaterialTheme.colorScheme.error
        loaded.session?.status == "revoked" || loaded.status == "revoked" ->
            "Revoked" to MaterialTheme.colorScheme.error
        loaded.status == "pending_pairing" -> "Awaiting pairing" to MaterialTheme.colorScheme.tertiary
        else -> loaded.status to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActivityRow(entry: AuditEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.title.ifBlank { entry.event_type },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatUnixDate(entry.created_at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!entry.body.isNullOrBlank()) {
            Text(
                entry.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun String?.orDash(): String = if (isNullOrBlank()) "—" else this

private fun formatUnixDate(unixSec: Long): String {
    if (unixSec <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(unixSec * 1000))
}

private fun formatIsoDate(unixSec: Long?): String {
    if (unixSec == null || unixSec <= 0L) return "—"
    return formatUnixDate(unixSec)
}

private fun formatRemainingLong(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 && h > 0 -> "${d}d ${h}h"
        d > 0 -> "${d}d"
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}
