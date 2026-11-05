package com.vettid.feature.connections

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.location.IncomingShare
import com.vettid.core.data.location.LocationAvailability
import com.vettid.core.data.location.LengthSystem
import com.vettid.core.data.location.LengthSystems
import com.vettid.core.data.prefs.DistanceUnits
import com.vettid.core.data.location.LocationRepository
import com.vettid.core.data.location.NoLocation
import com.vettid.core.data.location.PositionOffset
import com.vettid.core.data.location.PositionText
import com.vettid.core.data.location.ReceivedPosition
import com.vettid.core.data.location.SharePrecision
import com.vettid.core.data.location.ShareTime
import com.vettid.core.data.map.MapServer
import com.vettid.core.data.map.MapSetting
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalsRepository
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.map.MapCircle
import com.vettid.core.map.MapContent
import com.vettid.core.map.MapLibreSurface
import com.vettid.core.map.MapPoint
import com.vettid.core.map.MapSurface
import com.vettid.core.ui.components.AlertDialog
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.HoldsInput
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

/*
 * Receiving a connection's location (ANDROID-PLAN §4 "Location", L1b): the incoming card and Ask for location on the connection
 * page, the glance on the Connections list, the Location screen of the drawer and the position as text. Positions are read
 * from the repository (memory only) and shown; they are in no saved state, no log, and the distance and direction are computed
 * by the repository, never here.
 */

/** The clock for ages, ticking every 30 seconds while composed. */
@Composable
internal fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = Instant.now()
        }
    }
    return now
}

private const val TICK_MS = 30_000L

// --- the connection page ---

/**
 * "{First}'s location · {age} · within {accuracy}" / "Until {time}", with **Stop receiving**; the card opens the position. Before
 * the first position the line says only "Until {time}".
 */
@Composable
internal fun IncomingLocationCard(
    incoming: DetailIncoming,
    name: String,
    units: DistanceUnits,
    busy: Boolean,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val now = rememberNow()
    val until = remember(incoming.until) { ShareTime.format(context, incoming.until) }
    DetailCard(Modifier.testTag("detail_incoming_location")) {
        val at = incoming.at
        if (at != null) {
            val accuracy = PositionText.accuracy(context.resources, incoming.accuracyM, rememberLengthSystem(units))
            val age = PositionText.age(context.resources, at, now)
            Text(
                if (accuracy != null) {
                    stringResource(R.string.connections_location_incoming, name, age, accuracy)
                } else {
                    stringResource(R.string.connections_location_incoming_no_accuracy, name, age)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("detail_incoming_line"),
            )
        }
        Text(
            stringResource(R.string.connections_location_until, until),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("detail_incoming_until"),
        )
        Text(
            stringResource(precisionLabel(incoming.precision)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("detail_incoming_precision"),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (at != null) {
                TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp).testTag("detail_incoming_open")) {
                    Text(stringResource(R.string.connections_location_position_open))
                }
            }
            TextButton(
                onClick = onStop,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("detail_incoming_stop"),
            ) {
                Text(stringResource(R.string.connections_location_stop_receiving))
            }
        }
    }
}

/** **Ask for location** (never on a service connection: the caller leaves it out). */
@Composable
internal fun AskLocationCard(onAsk: () -> Unit, enabled: Boolean) {
    DetailCard(Modifier.testTag("detail_ask_location")) {
        SecondaryButton(
            stringResource(R.string.connections_location_ask),
            onAsk,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("detail_ask_location_button"),
        )
    }
}

/** The dialog of **Ask for location**: an optional note, the outcome. Reports its typed note to the daily owner check. */
@Composable
fun AskLocationDialog(ask: LocationAsk, name: String, onNote: (String) -> Unit, onSend: () -> Unit, onClose: () -> Unit) {
    HoldsInput(ask.open)
    if (!ask.open) return
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("ask_location_dialog"),
        title = { Text(stringResource(R.string.connections_location_ask_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (ask.sent) {
                    Text(stringResource(R.string.connections_location_ask_sent, name), modifier = Modifier.testTag("ask_location_sent"))
                } else {
                    OutlinedTextField(
                        value = ask.note,
                        onValueChange = onNote,
                        label = { Text(stringResource(R.string.connections_location_ask_note)) },
                        enabled = !ask.busy,
                        modifier = Modifier.fillMaxWidth().testTag("ask_location_note"),
                    )
                }
                if (ask.trimmed && !ask.sent) {
                    Text(stringResource(R.string.connections_location_ask_trimmed), modifier = Modifier.testTag("ask_location_trimmed"))
                }
                ask.limitedMinutes?.let { n ->
                    Text(
                        pluralStringResource(R.plurals.connections_location_ask_limit, n, name, n),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("ask_location_limit"),
                    )
                }
                ask.error?.let { e ->
                    Text(
                        failureText(e.messageRes(), e.vaultCode(ask.errorCode)),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("ask_location_error"),
                    )
                }
            }
        },
        confirmButton = {
            if (ask.sent) {
                TextButton(onClick = onClose, modifier = Modifier.testTag("ask_location_done")) {
                    Text(stringResource(R.string.connections_ok))
                }
            } else {
                TextButton(onClick = onSend, enabled = !ask.busy, modifier = Modifier.testTag("ask_location_send")) {
                    Text(stringResource(R.string.connections_location_ask_send))
                }
            }
        },
        dismissButton = if (ask.sent) {
            null
        } else {
            { TextButton(onClick = onClose) { Text(stringResource(com.vettid.core.ui.R.string.core_ui_cancel)) } }
        },
    )
}

// --- the Connections list ---

/** The glance: a small location icon and the age ("5 min") under the connection's line. */
@Composable
internal fun LocationGlanceLine(glance: LocationGlance) {
    val context = LocalContext.current
    val now = rememberNow()
    val age = glance.at?.let { PositionText.age(context.resources, it, now) }
    val description = stringResource(R.string.connections_location_glance)
    Row(
        Modifier.padding(top = Spacing.xs).testTag("connection_glance"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.LocationOn,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        if (age != null) {
            Spacer(Modifier.size(Spacing.xs))
            Text(age, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- the Location screen (drawer) ---

/** One of the member's outgoing shares, as the Location screen lists it. */
data class OutgoingRow(
    val shareId: String,
    val connectionId: String,
    val name: String?,
    val precision: SharePrecision,
    val until: Instant,
    val lastSentAt: Instant?,
    val stopping: Boolean,
    /** A stop was just tapped and is out: the button waits. */
    val busy: Boolean = false,
)

/** A connection's share with the member. */
data class IncomingRow(
    val shareId: String,
    val connectionId: String,
    val name: String?,
    val at: Instant?,
    val until: Instant,
    val busy: Boolean = false,
)

/** A pending request: "{First} asks where you are". */
data class RequestRow(val key: String, val connectionId: String, val name: String?)

data class LocationUiState(
    val outgoing: List<OutgoingRow> = emptyList(),
    val incoming: List<IncomingRow> = emptyList(),
    val requests: List<RequestRow> = emptyList(),
    val availability: LocationAvailability = LocationAvailability.UNKNOWN,
    val error: FailureKind? = null,
    val errorCode: String? = null,
    /** Share ids whose stop is out (a second tap waits). */
    val stoppingIds: Set<String> = emptySet(),
) {
    val empty: Boolean get() = outgoing.isEmpty() && incoming.isEmpty() && requests.isEmpty()
}

/** The Location screen: who the member shares with, who shares with them, and the requests. All from memory. */
@HiltViewModel
class LocationViewModel @Inject constructor(
    private val location: LocationRepository = NoLocation,
    private val connections: ConnectionsRepository,
    private val approvals: ApprovalsRepository,
) : ViewModel() {
    private val local = MutableStateFlow(LocationUiState())

    val uiState: StateFlow<LocationUiState> = combine(
        local,
        location.outgoing,
        location.incoming,
        combine(connections.connections, approvals.approvals, location.serviceState) { c, a, s -> Triple(c, a, s) },
        location.availability,
    ) { s, out, inc, (cs, asks, service), availability ->
        val names = cs.associate { it.id to firstNameOf(it) }
        s.copy(
            outgoing = out.filter { it.expiresAt.isAfter(Instant.now()) }.map {
                OutgoingRow(
                    it.shareId, it.connectionId, names[it.connectionId], it.precision, it.expiresAt, it.lastSentAt,
                    it.connectionId in service.stopping, it.shareId in s.stoppingIds,
                )
            },
            incoming = inc.filter { it.expiresAt.isAfter(Instant.now()) }.map {
                IncomingRow(it.shareId, it.connectionId, names[it.connectionId], it.last?.at, it.expiresAt, it.shareId in s.stoppingIds)
            },
            requests = asks.filterIsInstance<Approval.LocationRequest>()
                .map { RequestRow(it.key, it.connectionId, names[it.connectionId]) },
            availability = availability,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocationUiState())

    /** **Stop** on an outgoing share. */
    fun stopSharing(shareId: String) = run(shareId) { location.stop(shareId) }

    /** **Stop receiving** on an incoming share. */
    fun stopReceiving(shareId: String) = run(shareId) { location.stopReceiving(shareId) }

    fun dismissError() = local.update { it.copy(error = null, errorCode = null) }

    private fun run(shareId: String, block: suspend () -> Unit) {
        if (shareId in local.value.stoppingIds) return
        local.update { it.copy(stoppingIds = it.stoppingIds + shareId) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: VaultFailure) {
                local.update { it.copy(error = e.kind, errorCode = e.shownCode) }
            } finally {
                local.update { it.copy(stoppingIds = it.stoppingIds - shareId) }
            }
        }
    }

    private fun firstNameOf(c: ConnectionInfo): String? = sharingName(c)
}

/** What the Location screen asks for. */
data class LocationActions(
    val onStopSharing: (String) -> Unit = {},
    val onStopReceiving: (String) -> Unit = {},
    val onOpenConnection: (String) -> Unit = {},
    val onOpenPosition: (String) -> Unit = {},
    val onOpenRequest: (String) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

@Composable
fun LocationScreen(state: LocationUiState, chrome: ShellChrome, actions: LocationActions, modifier: Modifier = Modifier) {
    TopLevelScaffold(title = stringResource(R.string.connections_location_screen_title), chrome = chrome, modifier = modifier) {
        if (state.empty && state.error == null) {
            EmptyState(
                icon = Icons.Outlined.LocationOn,
                title = stringResource(R.string.connections_location_empty_title),
                body = stringResource(R.string.connections_location_empty_body),
                modifier = Modifier.testTag("location_empty"),
            )
        } else {
            LocationLists(state, actions)
        }
    }
}

@Composable
private fun LocationLists(state: LocationUiState, actions: LocationActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        state.error?.let { e ->
            NoticeCard(
                kind = NoticeKind.WARNING,
                title = stringResource(R.string.connections_error_title),
                body = failureText(e.messageRes(), e.vaultCode(state.errorCode)),
                actions = { TextButton(onClick = actions.onDismissError) { Text(stringResource(R.string.connections_ok)) } },
                modifier = Modifier.testTag("location_error"),
            )
        }
        if (state.outgoing.isNotEmpty()) OutgoingSection(state.outgoing, actions)
        if (state.incoming.isNotEmpty()) IncomingSection(state.incoming, actions)
        if (state.requests.isNotEmpty()) RequestSection(state.requests, actions)
        Spacer(Modifier.size(Spacing.xl))
    }
}

@Composable
private fun OutgoingSection(rows: List<OutgoingRow>, actions: LocationActions) {
    val context = LocalContext.current
    val now = rememberNow()
    Header(R.string.connections_location_sharing_with)
    rows.forEach { r ->
        val who = r.name ?: stringResource(R.string.connections_name_not_shared)
        val until = ShareTime.format(context, r.until)
        DetailCard(Modifier.testTag("location_out_${r.shareId}")) {
            Text(AccountNames.isolate(who), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(precisionLabel(r.precision)) + " · " + stringResource(R.string.connections_location_until, until),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            r.lastSentAt?.let { sent ->
                val fresh = java.time.Duration.between(sent, now).seconds < MINUTE
                Text(
                    if (fresh) {
                        stringResource(R.string.connections_location_last_sent_now)
                    } else {
                        stringResource(R.string.connections_location_last_sent, PositionText.age(context.resources, sent, now))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { actions.onStopSharing(r.shareId) },
                enabled = !r.stopping && !r.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("location_out_stop_${r.shareId}"),
            ) {
                Text(
                    stringResource(
                        if (r.stopping) com.vettid.core.data.R.string.data_location_stopping else R.string.connections_location_stop,
                    ),
                )
            }
        }
    }
}

@Composable
private fun IncomingSection(rows: List<IncomingRow>, actions: LocationActions) {
    val context = LocalContext.current
    val now = rememberNow()
    Header(R.string.connections_location_shared_with_you)
    rows.forEach { r ->
        val who = r.name ?: stringResource(R.string.connections_name_not_shared)
        val until = ShareTime.format(context, r.until)
        DetailCard(Modifier.testTag("location_in_${r.shareId}")) {
            Text(AccountNames.isolate(who), style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    r.at?.let { PositionText.age(context.resources, it, now) },
                    stringResource(R.string.connections_location_until, until),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow {
                if (r.at != null) {
                    TextButton(onClick = { actions.onOpenPosition(r.connectionId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.connections_location_position_open))
                    }
                }
                TextButton(onClick = { actions.onOpenConnection(r.connectionId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.connections_location_open_connection))
                }
                TextButton(
                    onClick = { actions.onStopReceiving(r.shareId) },
                    enabled = !r.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("location_in_stop_${r.shareId}"),
                ) { Text(stringResource(R.string.connections_location_stop_receiving)) }
            }
        }
    }
}

@Composable
private fun RequestSection(rows: List<RequestRow>, actions: LocationActions) {
    Header(R.string.connections_location_requests)
    rows.forEach { r ->
        val who = AccountNames.isolate(r.name ?: stringResource(R.string.connections_name_not_shared))
        DetailCard(Modifier.testTag("location_request_${r.key}")) {
            Text(stringResource(R.string.connections_location_request, who), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { actions.onOpenRequest(r.key) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.connections_location_request_open))
            }
        }
    }
}

private const val MINUTE = 60L

@Composable
private fun Header(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = Spacing.m).semantics { heading() },
    )
}

private fun precisionLabel(p: SharePrecision): Int = when (p) {
    SharePrecision.APPROXIMATE -> R.string.connections_location_approximate
    SharePrecision.EXACT -> R.string.connections_location_exact
    SharePrecision.CITY -> R.string.connections_location_city
}

// --- the position (the map is L2) ---

/** What the position screen shows. The coordinates are text here, in memory, and in no saved state. */
data class PositionUiState(
    val loaded: Boolean = false,
    val name: String? = null,
    val share: IncomingShare? = null,
    val offset: PositionOffset? = null,
    val confirmMaps: Boolean = false,
    val error: FailureKind? = null,
    val errorCode: String? = null,
    /** The daily owner check is due or held, or overdue locally while the state is still OK: nothing of the share is shown or said. */
    val held: Boolean = false,
    /** The map server in force and whether its privacy note was answered; null until the settings are read. Settings, not location data. */
    val mapSetting: MapSetting? = null,
    /** "Not now" was tapped on the note: no map for this visit (memory only; the note comes again next time). */
    val mapDeclined: Boolean = false,
    /** Miles or kilometers (Settings → Units). */
    val units: DistanceUnits = DistanceUnits.AUTOMATIC,
) {
    /** The "hands the position to another app" question is open: the daily check waits for the decision. */
    val holdsInput: Boolean get() = confirmMaps

    override fun toString(): String = "PositionUiState"
}

@HiltViewModel
class PositionViewModel @Inject constructor(
    savedState: androidx.lifecycle.SavedStateHandle,
    private val location: LocationRepository = NoLocation,
    private val connections: ConnectionsRepository,
    private val ownerCheck: com.vettid.core.data.vault.OwnerCheckRepository,
    private val prefs: PreferencesRepository,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[PositionRoute.ARG]) { "no connection" }
    private val local = MutableStateFlow(PositionUiState())
    private val offset = MutableStateFlow<PositionOffset?>(null)

    /** The screen is at least STARTED: the phone's own place is asked for only then. */
    private val shown = MutableStateFlow(true)

    /** Bumped by the screen's clock ([look]): the check can come due by time alone, so the gate is judged again then. */
    private val looks = MutableStateFlow(0)
    private val held = combine(ownerCheck.ownerCheck, looks) { v, _ -> v?.gated(Instant.now()) == true }.distinctUntilChanged()

    /** The screen's clock ticked (every 30 s while shown): the daily check is looked at again. */
    fun look() = looks.update { it + 1 }

    val uiState: StateFlow<PositionUiState> = combine(
        local,
        combine(location.incoming, held) { shares, h ->
            shares.firstOrNull { it.connectionId == id }?.takeIf { it.expiresAt.isAfter(Instant.now()) } to h
        },
        connections.connections,
        offset,
        combine(
            prefs.preferences.map { MapServer.of(it) }
                .distinctUntilChanged { a, b -> a.styleUrl == b.styleUrl && a.noteAnswered == b.noteAnswered },
            prefs.preferences.map { it.distanceUnits }.distinctUntilChanged(),
        ) { m, u -> m to u },
    ) { s, (share, h), cs, o, (m, u) ->
        PositionUiState(
            loaded = true,
            name = cs.firstOrNull { it.id == id }?.let { sharingName(it) },
            share = share.takeIf { !h },
            offset = o.takeIf { !h },
            confirmMaps = s.confirmMaps,
            error = s.error,
            errorCode = s.errorCode,
            held = h,
            mapSetting = m,
            mapDeclined = s.mapDeclined,
            units = u,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PositionUiState())

    init {
        viewModelScope.launch { read() }
        // The offset is worked out again only when THIS connection's latest position changes, and only while the screen is shown;
        // the permission is never asked for here.
        viewModelScope.launch {
            val mine = location.incoming.map { l -> l.firstOrNull { it.connectionId == id }?.last }.distinctUntilChanged()
            combine(mine, shown) { last, v -> last to v }
                .collectLatest { (last, visible) ->
                    // An old distance is never shown beside a new position: cleared before each recompute (and when none is left).
                    offset.value = null
                    if (last != null && visible) {
                        offset.value = try {
                            location.offsetFromHere(id)
                        } catch (_: VaultFailure) {
                            null
                        }
                    }
                }
        }
    }

    /** The screen is at least STARTED or stopped. */
    fun setVisible(visible: Boolean) {
        shown.value = visible
    }

    private suspend fun read() {
        try {
            location.open(id)
        } catch (e: VaultFailure) {
            local.update { it.copy(error = e.kind, errorCode = e.shownCode) }
        }
    }

    fun refresh() {
        local.update { it.copy(error = null) }
        viewModelScope.launch { read() }
    }

    fun askMaps() = local.update { it.copy(confirmMaps = true) }

    /** "Show map": the note is answered for [host] (the one it named), and only then is anything fetched. */
    fun showMap(host: String) {
        viewModelScope.launch { prefs.setMapNoteHost(host) }
    }

    /** "Not now": no map on this visit, nothing fetched. */
    fun mapNotNow() = local.update { it.copy(mapDeclined = true) }

    fun dismissMaps() = local.update { it.copy(confirmMaps = false) }
}

/** The `geo:` URI that hands a position to a maps app, with a pin (the coordinates in a fixed locale). */
internal fun geoUri(lat: Double, lon: Double): Uri {
    val point = String.format(Locale.US, "%.6f,%.6f", lat, lon)
    return Uri.parse("geo:$point?q=$point")
}

/** The latest position as text: coordinates (the same in every locale), accuracy, age and, when known, the offset from this phone. */
@Composable
private fun PositionCard(
    p: ReceivedPosition,
    offset: PositionOffset?,
    now: Instant,
    precision: SharePrecision,
    distanceUnits: DistanceUnits,
) {
    val context = LocalContext.current
    val units = rememberLengthSystem(distanceUnits)
    DetailCard(Modifier.testTag("position_card")) {
        val coordinates = String.format(Locale.ROOT, "%.5f, %.5f", p.lat, p.lon)
        Text(coordinates, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("position_coordinates"))
        Text(stringResource(precisionLabel(precision)), modifier = Modifier.testTag("position_precision"))
        if (precision != SharePrecision.EXACT) {
            // The point stands for an area (about 1 km, or 10 km for City), not for the place itself.
            Text(
                stringResource(R.string.connections_location_position_area),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("position_area"),
            )
        }
        PositionText.accuracy(context.resources, p.accuracyM, units)?.let {
            Text(stringResource(R.string.connections_location_position_within, it), modifier = Modifier.testTag("position_accuracy"))
        }
        val justNow = java.time.Duration.between(p.at, now).seconds < MINUTE
        Text(
            if (justNow) {
                stringResource(R.string.connections_location_position_age_now)
            } else {
                stringResource(R.string.connections_location_position_age, PositionText.age(context.resources, p.at, now))
            },
            modifier = Modifier.testTag("position_age"),
        )
        offset?.let {
            Text(PositionText.offset(context.resources, it, p.accuracyM, precision, units), modifier = Modifier.testTag("position_offset"))
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun PositionScreen(
    state: PositionUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAskMaps: () -> Unit,
    onDismissMaps: () -> Unit,
    onShowMap: (String) -> Unit = {},
    onMapNotNow: () -> Unit = {},
    map: MapSurface = MapLibreSurface,
) {
    HoldsInput(state.holdsInput)
    val context = LocalContext.current
    val now = rememberNow()
    var noMaps by remember { mutableStateOf(false) }
    val name = AccountNames.isolate(state.name ?: stringResource(R.string.connections_name_not_shared))
    DetailScaffold(onBackClick = onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(
                stringResource(R.string.connections_location_position_title, name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }.testTag("position_title"),
            )
            state.error?.let { e ->
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.connections_error_title),
                    body = failureText(e.messageRes(), e.vaultCode(state.errorCode)),
                    actions = { TextButton(onClick = onRefresh) { Text(stringResource(R.string.connections_retry)) } },
                    modifier = Modifier.testTag("position_error"),
                )
            }
            val share = state.share
            val p = share?.last
            if (share != null && p != null) PositionMap(state, share, p, name, onShowMap, onMapNotNow, map)
            when {
                share == null && state.loaded && state.error == null && !state.held -> Text(
                    stringResource(R.string.connections_location_position_none, name),
                    modifier = Modifier.testTag("position_none"),
                )
                p == null && share != null -> Text(
                    stringResource(R.string.connections_location_position_waiting),
                    modifier = Modifier.testTag("position_waiting"),
                )
                p != null -> {
                    PositionCard(p, state.offset, now, share?.precision ?: SharePrecision.APPROXIMATE, state.units)
                }
            }
            if (noMaps) {
                Text(stringResource(R.string.connections_location_no_maps), modifier = Modifier.testTag("position_no_maps"))
            }
            if (p != null) {
                PrimaryButton(
                    stringResource(R.string.connections_location_open_maps),
                    onAskMaps,
                    modifier = Modifier.fillMaxWidth().testTag("position_open_maps"),
                )
            }
        }
    }
    val p = state.share?.last
    if (state.confirmMaps && p != null) {
        ConfirmDialog(
            title = stringResource(R.string.connections_location_open_maps),
            text = stringResource(R.string.connections_location_open_maps_confirm),
            confirmLabel = stringResource(R.string.connections_location_open_maps_go),
            onConfirm = {
                onDismissMaps()
                noMaps = try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, geoUri(p.lat, p.lon)))
                    false
                } catch (_: RuntimeException) { // no activity for it, or the system refused the hand-off
                    true
                }
            },
            onDismiss = onDismissMaps,
        )
    }
}

// --- the map (Location L2) ---

/** What the map is asked to draw for [share]'s latest position [p]: the circle, and the trail when the share allows it and it is held. */
internal fun mapContentOf(share: IncomingShare, p: ReceivedPosition, styleUrl: String): MapContent {
    val trail = if (share.trail) {
        (share.snapshot + share.trailPoints + p).distinctBy { it.at }.sortedBy { it.at }.map { MapPoint(it.lat, it.lon) }
    } else {
        emptyList()
    }
    return MapContent(
        styleUrl = styleUrl,
        position = MapPoint(p.lat, p.lon),
        radiusM = MapCircle.radiusM(p.accuracyM, PositionText.nominalRadius(share.precision)),
        trail = MapCircle.trailOf(trail),
        shareKey = share.shareId,
        showPoint = share.precision == SharePrecision.EXACT,
    )
}

private val MAP_HEIGHT = 280.dp

/**
 * Above the text of the position: the privacy note with **Show map** and **Not now** until the note is answered for the map
 * server's host, then the map. Nothing is fetched before Show map (the map is not composed). Only called with a position, so
 * it is never composed while the share is hidden (held, ended, locked).
 */
@Composable
private fun PositionMap(
    state: PositionUiState,
    share: IncomingShare,
    p: ReceivedPosition,
    name: String,
    onShowMap: (String) -> Unit,
    onNotNow: () -> Unit,
    map: MapSurface,
) {
    val setting = state.mapSetting
    when {
        setting == null || state.mapDeclined -> Unit
        !setting.noteAnswered -> MapNote(setting.host, { onShowMap(setting.host) }, onNotNow)
        else -> {
            val description = stringResource(R.string.connections_location_map_cd, name)
            Box(
                Modifier.fillMaxWidth().height(MAP_HEIGHT).clip(MaterialTheme.shapes.medium)
                    .semantics { contentDescription = description }.testTag("position_map_box"),
            ) {
                map.Show(mapContentOf(share, p, setting.styleUrl), Modifier.fillMaxSize())
            }
        }
    }
}

/** The owner-approved note about the tile server (2026-11-05), the server's host, and the two answers. */
@Composable
private fun MapNote(host: String, onShow: () -> Unit, onNotNow: () -> Unit) {
    DetailCard(Modifier.testTag("position_map_note")) {
        Text(stringResource(R.string.connections_location_map_note), modifier = Modifier.testTag("position_map_note_text"))
        Text(
            stringResource(R.string.connections_location_map_server, host),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("position_map_host"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PrimaryButton(
                stringResource(R.string.connections_location_map_show),
                onShow,
                modifier = Modifier.weight(1f).testTag("position_map_show"),
            )
            SecondaryButton(
                stringResource(R.string.connections_location_map_not_now),
                onNotNow,
                modifier = Modifier.weight(1f).testTag("position_map_not_now"),
            )
        }
    }
}

/**
 * The length system for [units]: Automatic reads the region of the configuration's first locale, so a language or region change
 * while the screen is open switches miles and kilometers at once (a view model outlives the change).
 */
@Composable
internal fun rememberLengthSystem(units: DistanceUnits): LengthSystem {
    val region = LocalConfiguration.current.locales[0]?.country
    return remember(units, region) { LengthSystems.resolve(units) { region } }
}
