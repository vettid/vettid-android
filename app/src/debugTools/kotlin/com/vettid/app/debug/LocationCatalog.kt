// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.runtime.Composable
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.location.LocationAvailability
import com.vettid.core.data.location.OutgoingShare
import com.vettid.core.data.location.SendIssue
import com.vettid.core.data.location.SharePrecision
import com.vettid.core.data.map.MapServer
import com.vettid.core.data.map.MapSetting
import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.DistanceUnits
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.location.IncomingShare
import com.vettid.core.data.location.PositionOffset
import com.vettid.core.data.location.ReceivedPosition
import com.vettid.core.data.social.Approval
import com.vettid.core.ui.components.ShellChrome
import com.vettid.feature.approvals.ApprovalDetailScreen
import com.vettid.feature.approvals.ApprovalDetailUiState
import com.vettid.feature.approvals.DecisionActions
import com.vettid.feature.connections.AskLocationDialog
import com.vettid.feature.connections.DetailIncoming
import com.vettid.feature.connections.IncomingRow
import com.vettid.feature.connections.LocationActions
import com.vettid.feature.connections.LocationAsk
import com.vettid.feature.connections.LocationScreen
import com.vettid.feature.connections.LocationUiState
import com.vettid.feature.connections.OutgoingRow
import com.vettid.feature.connections.PositionScreen
import com.vettid.feature.connections.PositionUiState
import com.vettid.feature.connections.RequestRow
import com.vettid.feature.connections.ConnectionDetailScreen
import com.vettid.feature.connections.ConnectionDetailUiState
import com.vettid.feature.connections.DetailActions
import com.vettid.feature.connections.DetailLocation
import com.vettid.feature.connections.LocationPermission
import com.vettid.feature.connections.ShareLocationActions
import com.vettid.feature.connections.ShareLocationSheet
import com.vettid.feature.connections.ShareLocationUiState
import com.vettid.feature.settings.MapServerContent
import com.vettid.feature.settings.MapServerUiState
import java.time.Instant

/** Sharing the member's location (ANDROID-PLAN §4 "Location", L1a): the share sheet in its states, and the connection page while sharing. */
internal object LocationCatalog {
    private val sam = ConnectionInfo(
        "c1", "Sam", ConnectionState.ACTIVE, favorite = true, firstName = "Samira", lastName = "Rivera",
    )
    private val first = AccountNames.isolate("Samira")

    /** The sheet over the connection's page it is opened from. */
    @Composable
    private fun Sheet(state: ShareLocationUiState) {
        ConnectionDetailScreen(ConnectionDetailUiState("c1", sam, null, loading = false), DetailActions())
        ShareLocationSheet(state, first, ShareLocationActions())
    }

    @Composable
    private fun Detail(location: DetailLocation) =
        ConnectionDetailScreen(ConnectionDetailUiState("c1", sam, null, loading = false, location = location), DetailActions())

    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})

    @Composable
    private fun Incoming(i: DetailIncoming, units: DistanceUnits = DistanceUnits.AUTOMATIC) =
        ConnectionDetailScreen(ConnectionDetailUiState("c1", sam, null, loading = false, incoming = i, units = units), DetailActions())

    @Composable
    private fun Ask(ask: LocationAsk) {
        ConnectionDetailScreen(ConnectionDetailUiState("c1", sam, null, loading = false), DetailActions())
        AskLocationDialog(ask, first, {}, {}, {})
    }

    @Composable
    private fun Position(offset: PositionOffset?, mapSetting: MapSetting? = null, units: DistanceUnits = DistanceUnits.AUTOMATIC) {
        val p = ReceivedPosition(52.5200, 13.4050, 1_000.0, null, null, null, Instant.now().minusSeconds(300), null)
        val share = IncomingShare("in1", "c1", false, SharePrecision.APPROXIMATE, 60, true, null, Instant.now().plusSeconds(3_600), last = p,
            trailPoints = listOf(
                ReceivedPosition(52.5100, 13.3900, 1_000.0, null, null, null, Instant.now().minusSeconds(900), null),
                ReceivedPosition(52.5150, 13.3970, 1_000.0, null, null, null, Instant.now().minusSeconds(600), null),
            ),
        )
        PositionScreen(PositionUiState(loaded = true, name = "Samira", share = share, offset = offset, mapSetting = mapSetting, units = units), {}, {}, {}, {})
    }

    private val defaultServer = MapServer.of(AppPreferences())

    private val open = ShareLocationUiState(
        "c1", open = true, permission = LocationPermission.PRECISE, availability = LocationAvailability.AVAILABLE,
    )

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        // The defaults: For a while, 1 hour, Approximate, every minute, no trail.
        "location.share" to { Sheet(open) },
        // Before the first share: the permission text, asked when Start sharing is tapped.
        "location.share_permission" to { Sheet(open.copy(permission = LocationPermission.NONE)) },
        "location.share_permission_denied" to { Sheet(open.copy(permission = LocationPermission.NONE, permissionDenied = true)) },
        // Once: no duration, no interval, no trail.
        "location.share_once" to { Sheet(open.copy(once = true)) },
        // Approximate access only: Exact is disabled and says why; Approximate and City work.
        "location.share_exact_disabled" to { Sheet(open.copy(permission = LocationPermission.APPROXIMATE)) },
        // Started, waiting for the first position: "Finding your location…".
        "location.share_finding" to {
            val share = OutgoingShare("s1", "c1", false, SharePrecision.APPROXIMATE, 60, false, Instant.now().plusSeconds(3_600), null, true)
            Sheet(open.copy(startedId = "s1", started = share))
        },
        // The connection's page while the member shares with the connection.
        // Sharing, but not sending: the daily check, a stop to wait for, location off for VettID, location off on the phone, and a once
        // share that found no position.
        "connections.detail_location_paused" to { Detail(DetailLocation("s1", Instant.now().plusSeconds(1_800), paused = true)) },
        "connections.detail_location_stop_pending" to {
            Detail(DetailLocation("s1", Instant.now().plusSeconds(1_800), issue = SendIssue.STOP_PENDING))
        },
        "connections.detail_location_no_permission" to {
            Detail(DetailLocation("s1", Instant.now().plusSeconds(1_800), issue = SendIssue.NO_PERMISSION))
        },
        "connections.detail_location_off" to {
            Detail(DetailLocation("s1", Instant.now().plusSeconds(1_800), issue = SendIssue.LOCATION_OFF))
        },
        // A6 N21: no position has reached the person for 25 minutes (cause unknown), and with a known cause (the fix-it action).
        "connections.detail_location_stalled" to {
            Detail(DetailLocation("s1", Instant.now().plusSeconds(1_800), stalledMinutes = 25))
        },
        "connections.detail_location_stalled_off" to {
            Detail(DetailLocation("s1", Instant.now().plusSeconds(1_800), issue = SendIssue.LOCATION_OFF, stalledMinutes = 25))
        },
        "connections.detail_location_missed" to {
            ConnectionDetailScreen(ConnectionDetailUiState("c1", sam, null, loading = false, locationMissed = true), DetailActions())
        },
        // Receiving (L1b): the incoming card, Ask for location, the Location screen, the position as text, a request in Approvals.
        "location.incoming" to { Incoming(DetailIncoming("in1", Instant.now().plusSeconds(3_600), Instant.now().minusSeconds(300), 1_000.0, com.vettid.core.data.location.SharePrecision.APPROXIMATE)) },
        // Miles (Settings → Units, owner decision 2026-11-05): the incoming card's "within …" and the share sheet's precision notes.
        "location.incoming_miles" to {
            Incoming(DetailIncoming("in1", Instant.now().plusSeconds(3_600), Instant.now().minusSeconds(300), 1_000.0, SharePrecision.APPROXIMATE), DistanceUnits.MILES)
        },
        "location.share_miles" to { Sheet(open.copy(units = DistanceUnits.MILES)) },
        "location.incoming_waiting" to { Incoming(DetailIncoming("in1", Instant.now().plusSeconds(3_600), null, null, com.vettid.core.data.location.SharePrecision.APPROXIMATE)) },
        "location.ask" to { Ask(LocationAsk(open = true, note = "Are you near the station?")) },
        "location.ask_limit" to { Ask(LocationAsk(open = true, limitedMinutes = 7)) },
        "location.ask_sent" to { Ask(LocationAsk(open = true, sent = true)) },
        "location.screen" to {
            LocationScreen(
                LocationUiState(
                    outgoing = listOf(OutgoingRow("s1", "c1", "Samira", SharePrecision.APPROXIMATE, Instant.now().plusSeconds(3_600), Instant.now().minusSeconds(120), false)),
                    incoming = listOf(IncomingRow("in1", "c2", "Kim", Instant.now().minusSeconds(300), Instant.now().plusSeconds(7_200))),
                    requests = listOf(RequestRow("location:r1", "c3", "Lee")),
                    availability = LocationAvailability.AVAILABLE,
                ),
                chrome, LocationActions(),
            )
        },
        "location.screen_empty" to { LocationScreen(LocationUiState(availability = LocationAvailability.AVAILABLE), chrome, LocationActions()) },
        "location.position" to { Position(PositionOffset(3_200.0, 45.0)) },
        // Distances in miles and feet (Settings → Units, owner decision 2026-11-05).
        "location.position_miles" to { Position(PositionOffset(3_200.0, 45.0), units = DistanceUnits.MILES) },
        "location.position_no_offset" to { Position(null) },
        // The map (L2): the privacy note before it is answered (nothing is loaded), then the map itself on the default server.
        "location.map_note" to { Position(PositionOffset(3_200.0, 45.0), defaultServer) },
        "location.map" to {
            Position(PositionOffset(3_200.0, 45.0), MapServer.of(AppPreferences(mapNoteHost = defaultServer.host)))
        },
        // Settings → Privacy → Map server.
        "settings.map_server" to {
            MapServerContent(MapServerUiState(true, MapServer.DEFAULT_STYLE, MapServer.DEFAULT_STYLE, defaultServer.host), {}, {}, {}, {})
        },
        "settings.map_server_problem" to {
            MapServerContent(
                MapServerUiState(true, "http://maps.example.org/style.json", MapServer.DEFAULT_STYLE, defaultServer.host, isDefault = true, problem = MapServer.Problem.NOT_HTTPS),
                {}, {}, {}, {},
            )
        },
        "location.request" to {
            ApprovalDetailScreen(
                ApprovalDetailUiState(
                    "location:r1",
                    approval = Approval.LocationRequest("r1", "c1", "Are you near the station?", Instant.now(), Instant.now().plusSeconds(86_400), connectionName = "Samira Rivera", firstName = "Samira"),
                ),
                DecisionActions(),
            )
        },
        "connections.detail_location" to {
            ConnectionDetailScreen(
                ConnectionDetailUiState("c1", sam, null, loading = false, location = DetailLocation("s1", Instant.now().plusSeconds(1_800))),
                DetailActions(),
            )
        },
    )
}
