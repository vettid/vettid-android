package com.vettid.core.data.vault

import java.time.Instant

/** Where the app is (the root of the UI follows it). */
sealed interface AppPhase {
    /** Reading local state at start. */
    data object Starting : AppPhase

    /** The member API could not be reached at start ([failure] says why). */
    data class Unreachable(val failure: FailureKind) : AppPhase

    /** No member session on this device: sign in. */
    data object SignedOut : AppPhase

    /**
     * Signed in, but the account is `registered` or the current terms are not
     * accepted (MEMBER-API: the vault routes answer `terms_required`).
     * [updated]: a member whose accepted terms are out of date.
     */
    data class TermsRequired(val updated: Boolean) : AppPhase

    /** Signed in and allowed a vault; setting it up on this device. */
    data class Setup(val stage: SetupStage) : AppPhase

    /** Enrolled; the vault is locked: unlock with the PIN. */
    data object Locked : AppPhase

    /** The vault is open and this app is its holder. */
    data object Unlocked : AppPhase
}

enum class SetupStage {
    /** No vault yet: enroll one (PIN, password, backup). */
    NEW_VAULT,

    /** The account has a vault this device does not hold (one app per vault, §3.5.9). */
    VAULT_ELSEWHERE,

    /** Enrolled and paired, but the Protean Credential is not created yet (§3.5.7). */
    NEEDS_CREDENTIAL,

    /** Everything done; the member has not left the final onboarding screen yet. */
    FINISHING,
}

/** The signed-in member, for display. */
data class AccountInfo(val email: String, val firstName: String, val lastName: String) {
    val displayName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { email }
}

/** The enrollment steps the progress screen shows (§11.3, §3.5.7). */
enum class EnrollStep { ENROLL, WAIT_FOR_VAULT, HANDSHAKE, CREATE_CREDENTIAL, BACKUP, CONFIRM }

/** A release as the app shows it (§11.10.3: number, notes, PCR0 fingerprint). */
data class ReleaseView(
    val number: Long,
    val pcr0: String,
    val status: String,
    val endsAt: Instant?,
    val notes: String,
) {
    /** The first 16 hex digits of PCR0 in groups of four, for display. */
    val fingerprint: String get() = pcr0.take(FINGERPRINT_HEX).chunked(GROUP).joinToString(" ")

    private companion object {
        const val FINGERPRINT_HEX = 16
        const val GROUP = 4
    }
}

/** The release check before an unlock (§11.10.6). */
data class PreflightInfo(
    val routed: ReleaseView,
    val lastNumber: Long,
    /** Newer than the release this device last unlocked into: "vault software was updated". */
    val softwareUpdated: Boolean,
    /** Older: never send the PIN. */
    val rollback: Boolean,
    /** The newest active release, newer than the routed one: the member may approve the move. */
    val offer: ReleaseView?,
)

/** The outcome of an unlock (§11.4). */
sealed interface UnlockAttempt {
    data object Success : UnlockAttempt

    data class BadPin(val retryAfterSeconds: Long) : UnlockAttempt

    data class Backoff(val retryAfterSeconds: Long) : UnlockAttempt

    /** A recovery is in progress and the request did not cancel it (§11.11.4). */
    data object RecoveryPending : UnlockAttempt

    /** The vault's stored state is older than state this device has seen (§13.2). */
    data object StateRollback : UnlockAttempt

    /** The release update was refused ([code]); the vault stayed where it was. */
    data class UpdateRefused(val code: String) : UnlockAttempt

    data class Failed(val kind: FailureKind, val code: String?) : UnlockAttempt
}

/** A clone alarm on the Protean Credential (§3.5.9). */
data class CredentialAlarm(
    val alarmId: String,
    /** `frozen` or `rotation_required`. */
    val state: String,
    val at: String?,
    /** `holder` or `other`, when the alert said. */
    val presenter: String? = null,
) {
    val frozen: Boolean get() = state == STATE_FROZEN
    val rotationRequired: Boolean get() = state == STATE_ROTATION_REQUIRED

    companion object {
        const val STATE_FROZEN = "frozen"
        const val STATE_ROTATION_REQUIRED = "rotation_required"
        const val STATE_RESOLVED = "resolved"
    }
}

/** The credential screen's data (§3.5, §10.6, §10.8). */
data class CredentialStatus(
    val exists: Boolean,
    val version: Long?,
    val keyFingerprint: String?,
    val updatedAt: String?,
    val alarm: CredentialAlarm?,
    /** `credential.backup` (§3.5.6). */
    val backup: Boolean,
    /** `credential.unlock_ttl_seconds` (30–3,600). */
    val unlockTtlSeconds: Int,
    val criticalItems: Int?,
)

/** Vault status for Settings: the member API's advisory status plus the vault's own. */
data class VaultOverview(
    val vaultId: String?,
    /** `enrolling`, `locked`, `unlocked` (advisory). */
    val state: String?,
    val release: ReleaseInfoView?,
    /** The release this device last unlocked into. */
    val lastRelease: Long,
    val recoveryState: String?,
    val provisional: Boolean?,
    val devices: Int?,
    val connections: Int?,
)

/** MEMBER-API `ReleaseInfo` (advisory; the manifest decides). */
data class ReleaseInfoView(
    val number: Long?,
    val status: String,
    val endsAt: String?,
    val newestActive: Long?,
    val notice: String?,
)

/** A recovery in progress (MEMBER-API "Vault recovery"). */
data class RecoveryView(val recoveryId: String, val state: String, val availableAt: String, val expiresAt: String)

/** What Settings shows about attestation: this device's key and the enclave last verified. */
data class AttestationInfo(
    val environment: String,
    val keyPresent: Boolean,
    /** `STRONG_BOX`, `TEE`, `SOFTWARE` or `UNKNOWN`. */
    val keyLevel: String?,
    val strongBoxAvailable: Boolean,
    val attestationVersion: Int?,
    /** `Verified`, `SelfSigned`, `Unverified`, `Failed`. */
    val verifiedBoot: String?,
    val deviceLocked: Boolean?,
    val bootKeyFingerprint: String?,
    val lastRelease: Long,
    val lastReleaseFingerprint: String?,
)

/** Why an action failed, for the UI. */
enum class FailureKind {
    NETWORK,
    UNAUTHORIZED,
    TERMS_REQUIRED,
    RATE_LIMITED,
    VAULT_UNAVAILABLE,
    RELEASE_ENDED,
    BAD_PIN,
    BAD_PASSWORD,
    BACKOFF,
    CREDENTIAL_FROZEN,
    ROTATION_REQUIRED,
    NOT_SUPPORTED,
    NO_RESPONSE,
    VAULT_EXISTS,
    ATTESTATION,
    ROLLBACK,
    MANIFEST,
    NOT_FOUND,
    OTHER,
}

/** A failed action: [kind] for the UI, [code] the spec's or API's code. */
class VaultFailure(val kind: FailureKind, val code: String? = null, val retryAfterSeconds: Long = 0, cause: Throwable? = null) :
    Exception("$kind${code?.let { " ($it)" } ?: ""}", cause)
