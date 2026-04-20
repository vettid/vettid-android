package com.vettid.app.features.migration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-scoped bridge between the pre-PIN enclave-update dialog
 * (handled in [PinUnlockViewModel]) and the post-PIN vault-update card
 * (handled in [VaultUpdateViewModel]). The user sees one consent
 * moment for a migration even though the two screens are far apart in
 * the unlock flow.
 *
 * - Pre-PIN Approve  → recordApproval() → VaultUpdateVM auto-applies
 *   the migration with a visible "Updating" state (no silent swap).
 * - Pre-PIN Skip     → recordSkip()     → VaultUpdateVM suppresses the
 *   card and writes a feed entry so the deferred update stays findable.
 * - Neither          → VaultUpdateVM shows the normal UpdateAvailable
 *   card — covers the case where the user's vault was still on the old
 *   enclave so the pre-PIN dialog never fired.
 *
 * State is in-memory only: process restart clears it, which matches
 * "consent expressed in this session applies to this session."
 */
enum class MigrationIntent { UNDECIDED, APPROVED, SKIPPED }

@Singleton
class MigrationConsentTracker @Inject constructor() {
    private val _intent = MutableStateFlow(MigrationIntent.UNDECIDED)
    val intent: StateFlow<MigrationIntent> = _intent.asStateFlow()

    fun recordApproval() {
        _intent.value = MigrationIntent.APPROVED
    }

    fun recordSkip() {
        _intent.value = MigrationIntent.SKIPPED
    }

    /** Consume the pending intent — returns the current value and resets to UNDECIDED. */
    fun consume(): MigrationIntent {
        val current = _intent.value
        _intent.value = MigrationIntent.UNDECIDED
        return current
    }
}
