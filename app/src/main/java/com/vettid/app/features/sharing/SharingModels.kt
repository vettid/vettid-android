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
 * One row in the "Shared with this connection" editor — represents
 * one decision the user has made about what THIS peer can request.
 *
 * Phase 2 implements the handler kind end-to-end and seeds
 * placeholders for data/secret/wallet/action so the editor is
 * structurally complete; Phase 3 unifies the rest.
 */
data class SharePolicyRow(
    val key: String,             // "<kind>:<id>" matching the vault store
    val displayName: String,
    val category: String,
    val allowed: Boolean,
    val tier: String,            // "required" | "optional" | "on_demand" | "consent"
    val retention: String,       // "session" | "time_limited" | "until_revoked"
    val rateLimitPerHour: Int,   // 0 = unlimited
    val expiresAt: Long,         // unix; 0 = never
)

sealed class SharingEvent {
    data class RequestItem(val key: String) : SharingEvent()
    data class CancelRequest(val requestId: String) : SharingEvent()
    /**
     * Persist a per-item policy change. The vault merges into the
     * existing policy; only the keys present in [items] are changed.
     */
    data class UpdatePolicy(val items: Map<String, SharePolicyRow>) : SharingEvent()
    object Refresh : SharingEvent()
}
