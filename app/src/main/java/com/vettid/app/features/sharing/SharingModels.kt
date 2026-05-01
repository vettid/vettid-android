package com.vettid.app.features.sharing

import com.vettid.app.core.nats.PeerDataCatalogEntry
import com.vettid.app.core.nats.PeerPublicSecretMetadata

/**
 * Top-level state for ConnectionSharingScreen. Backed by
 * ConnectionSharingViewModel; mirrors the four-section layout the
 * sharing & contracts plan describes.
 *
 * Phase 1 only fully populates "Shared with me" — the other three
 * sections render placeholders that explain what's coming.
 */
sealed class SharingState {
    object Loading : SharingState()
    data class Loaded(
        val peerName: String,
        val connectionType: String,           // "peer" | "service" | "system" | "agent"
        val sharedWithMe: List<SharedItem>,
        // Phase 2 will populate this; for now an empty placeholder list.
        val sharedWithConnection: List<SharePolicyRow> = emptyList(),
    ) : SharingState()

    data class Error(val message: String) : SharingState()
}

/**
 * One row in the "Shared with me" list — sourced from the peer's
 * published data_catalog + secret_catalog. The status field is
 * derived from the capability-request log so the row knows whether
 * we've already asked.
 */
data class SharedItem(
    val key: String,            // "data:contact.email" / "secret:9d3f-..." / "wallet:bc1q..."
    val displayName: String,
    val category: String,
    val kind: Kind,
    val status: RequestStatus,
    val requestId: String?,     // last request, used for retries / view detail
) {
    enum class Kind { DATA, SECRET, WALLET }
}

enum class RequestStatus {
    AVAILABLE,    // never asked
    PENDING,      // asked; awaiting peer response
    APPROVED,     // peer approved (Phase 1: marks the row, value delivery is Phase 3)
    DENIED,       // peer denied
    EXPIRED,      // capability expired
}

/**
 * Phase 2 placeholder shape — keeping it here so the Loaded state's
 * type stays stable when Phase 2 lands.
 */
data class SharePolicyRow(
    val key: String,
    val displayName: String,
    val category: String,
    val allowed: Boolean,
)

sealed class SharingEvent {
    data class RequestItem(val key: String) : SharingEvent()
    data class CancelRequest(val requestId: String) : SharingEvent()
    object Refresh : SharingEvent()
}
