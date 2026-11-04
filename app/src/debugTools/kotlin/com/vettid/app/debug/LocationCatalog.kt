// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.runtime.Composable
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.location.OutgoingShare
import com.vettid.core.data.location.SharePrecision
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.feature.connections.ConnectionDetailScreen
import com.vettid.feature.connections.ConnectionDetailUiState
import com.vettid.feature.connections.DetailActions
import com.vettid.feature.connections.DetailLocation
import com.vettid.feature.connections.LocationPermission
import com.vettid.feature.connections.ShareLocationActions
import com.vettid.feature.connections.ShareLocationSheet
import com.vettid.feature.connections.ShareLocationUiState
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

    private val open = ShareLocationUiState("c1", open = true, permission = LocationPermission.PRECISE)

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
        "connections.detail_location" to {
            ConnectionDetailScreen(
                ConnectionDetailUiState("c1", sam, null, loading = false, location = DetailLocation("s1", Instant.now().plusSeconds(1_800))),
                DetailActions(),
            )
        },
    )
}
