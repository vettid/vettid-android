package com.vettid.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TileStyle
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.components.VettIdListRow
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.CategoryHue
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import com.vettid.core.ui.theme.categoryColor
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Devices & agents (ANDROID-PLAN 0.1.53 AA1): this phone first, then the member's agents, then desktops, each newest
 * first. A row is the name, the role, the access ("Access until 15:40" / "No access") and the last activity; an agent
 * the vault paused carries a "Paused" chip. Pairing an agent (AA2) adds its button later.
 */
@Composable
fun DevicesScreen(
    state: DevicesUiState,
    chrome: ShellChrome,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
    onOpen: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onRemovedShown: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val removed = state.removedName?.let { n ->
        if (n.isEmpty()) stringResource(R.string.devices_removed_unnamed) else stringResource(R.string.devices_removed, n)
    }
    LaunchedEffect(removed) {
        val text = removed ?: return@LaunchedEffect
        onRemovedShown()
        snackbar.showSnackbar(text)
    }
    TopLevelScaffold(
        title = stringResource(R.string.devices_title),
        chrome = chrome,
        modifier = modifier,
        overlay = {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Spacing.l))
        },
    ) {
        val error = state.error
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            state.devices.isEmpty() && error != null -> Column(
                Modifier.fillMaxSize().padding(Spacing.xl),
                verticalArrangement = Arrangement.Center,
            ) {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.devices_error_title),
                    body = failureText(error.messageRes(), error.vaultCode(state.errorCode)),
                    actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.devices_retry)) } },
                )
            }
            else -> DeviceList(state, now, onOpen, onRetry)
        }
    }
}

@Composable
private fun DeviceList(state: DevicesUiState, now: Instant, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    val (phone, others) = state.devices.partition { it.thisPhone }
    if (state.onlyThisPhone) {
        // This phone only: "No agents yet".
        Column(Modifier.fillMaxSize().testTag("devices")) {
            phone.forEach { ThisPhoneRow(it) }
            Box(Modifier.weight(1f)) {
                EmptyState(
                    icon = Icons.Outlined.Devices,
                    title = stringResource(R.string.devices_empty_title),
                    body = stringResource(R.string.devices_empty_body),
                )
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("devices"), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
        state.error?.let { e ->
            // The list on screen is what was read before; a refresh that failed says so above it.
            item(key = "error") {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.devices_error_title),
                    body = failureText(e.messageRes(), e.vaultCode(state.errorCode)),
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                    actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.devices_retry)) } },
                )
            }
        }
        items(phone, key = { "phone-${it.id}" }) { ThisPhoneRow(it) }
        items(others, key = { it.id }) { d -> DeviceRow(d, now, onClick = { onOpen(d.id) }) }
    }
}

/** This phone's own entry: "This phone", no actions (it leaves only by a transfer or a recovery, §6.7). */
@Composable
private fun ThisPhoneRow(d: DeviceInfo) {
    VettIdListRow(
        title = stringResource(R.string.devices_this_phone),
        tileIcon = Icons.Outlined.PhoneAndroid,
        tileStyle = TileStyle.Self,
        modifier = Modifier.testTag("device_${d.id}"),
    )
}

/** One agent or desktop: the initial tile (agents orange, desktops purple), name, role, access and last activity. */
@Composable
internal fun DeviceRow(d: DeviceInfo, now: Instant, onClick: () -> Unit) {
    val name = DeviceText.name(d)
    VettIdListRow(
        title = name,
        meta = DeviceText.role(d),
        supporting = DeviceText.access(d, now),
        tileName = name,
        tileColors = categoryColor(if (d.isAgent) CategoryHue.ORANGE else CategoryHue.PURPLE),
        onClick = onClick,
        modifier = Modifier.testTag("device_${d.id}"),
        below = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    DeviceText.lastActive(d, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (d.paused) PausedChip()
            }
        },
    )
}

/** "Paused": the vault paused this agent (§10.11); in the error colour. */
@Composable
private fun PausedChip() {
    Surface(shape = VettIdShape.pill, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.testTag("paused_chip")) {
        Text(
            stringResource(R.string.devices_paused),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** The minute clock the rows' "Access until" and "Last active" follow while the screen is shown. */
@Composable
internal fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_MS)
            now = Instant.now()
        }
    }
    return now
}

private const val CLOCK_MS = 30_000L
