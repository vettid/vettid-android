package com.vettid.core.data.vault

import java.time.Instant

/** Where the app is (the root of the UI follows it). */
sealed interface AppPhase {
    /** Reading local state at start. */
    data object Starting : AppPhase

    /** The member API could not be reached at start ([failure] says why). */
    data class Unreachable(val failure: FailureKind) : AppPhase

    /**
     * Nothing set up on this phone: the welcome screen (a setup code, a recovery or a transfer). The app never
     * signs in (VAULT-MESSAGING 0.15.0 §11.12); the name is kept from when it did.
     */
    data object SignedOut : AppPhase

    /** A setup code was redeemed (or a recovery claimed): setting the vault up on this device. */
    data class Setup(val stage: SetupStage) : AppPhase

    /** Enrolled; the vault is locked: unlock with the PIN. */
    data object Locked : AppPhase

    /** The vault is open and this app is its holder. */
    data object Unlocked : AppPhase

    // A phone that a transfer or a recovery replaced has no phase of its own: it erases itself and is
    // SignedOut, as freshly installed (owner decision, 2026-10-05; VaultManager, HolderWatch).
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

    /** This phone is recovering the vault (§11.11): registered with the code, until the credential is recovered or reset. */
    RECOVERING,
}

/**
 * The member's account as the vault reports it (VAULT-MESSAGING §11.13, 0.15.0): membership, terms and
 * subscription, read-only and display only (changes are made on the account portal). Before the first snapshot
 * only [emailHint] is known (from the setup code's redeem or the recovery's claim).
 */
data class AccountInfo(
    /** The masked address, `m***@example.com`. */
    val emailHint: String,
    /** `member` or another account state; null before the vault sent a snapshot. */
    val state: String? = null,
    /** `active` or `canceled`. */
    val accountStatus: String? = null,
    /** When a cancelled account's vault is deleted. */
    val deletesAt: Instant? = null,
    val termsNeedAcceptance: Boolean = false,
    val subscription: SubscriptionInfo? = null,
    val votingRights: Boolean = false,
    val asOf: Instant? = null,
) {
    val hasSnapshot: Boolean get() = state != null
    val canceled: Boolean get() = accountStatus == ACCOUNT_CANCELED

    companion object {
        const val ACCOUNT_CANCELED = "canceled"
    }
}

/** The member's subscription (§11.13): `trial`, `active`, `expired` or `canceled`. */
data class SubscriptionInfo(val typeName: String?, val status: String?, val paid: Boolean, val expiresAt: Instant?) {
    /** The status to show at [now]: a trial (or any subscription) whose `expires_at` has passed reads `expired`. */
    fun statusAt(now: Instant): String? = if (expiresAt != null && !now.isBefore(expiresAt) && status != STATUS_CANCELED) {
        STATUS_EXPIRED
    } else {
        status
    }

    companion object {
        const val STATUS_TRIAL = "trial"
        const val STATUS_ACTIVE = "active"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_CANCELED = "canceled"
    }
}

/** A setup code as the member gave it (VAULT-MESSAGING §11.12.1). */
sealed interface SetupCodeInput {
    /** The QR's (or the App Link's) 128-bit secret. */
    data class Secret(val secret: String) : SetupCodeInput {
        override fun toString(): String = "Secret(…)"
    }

    /** The typed code (canonical, 8 symbols) with the member's email. */
    data class Typed(val email: String, val code: String) : SetupCodeInput {
        override fun toString(): String = "Typed(…)"
    }
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

    /** [retryAfterSeconds]: when the service said when to try again (`Retry-After`, e.g. [FailureKind.SERVICE_PAUSED]). */
    data class Failed(val kind: FailureKind, val code: String?, val retryAfterSeconds: Long = 0) : UnlockAttempt
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

/** A recovery in progress, as `GET /api/vault/status` reports it (`recovery: {state, available_at}`). */
data class RecoveryView(val state: String, val availableAt: String)

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

    /** A setup code the member API refused (`404 invalid_code`: wrong, expired, used, or another email's). */
    SETUP_CODE_INVALID,
    TERMS_REQUIRED,
    RATE_LIMITED,
    VAULT_UNAVAILABLE,

    /**
     * The operator paused the vault service (MEMBER-API 1.2.0, `503 vault_unavailable` with `service: "paused"`):
     * temporary, try again after the failure's `retryAfterSeconds`. Members see only the generic text.
     */
    SERVICE_PAUSED,
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
    CONNECTION_UNAVAILABLE,
    INVITE_INVALID,
    INVITE_EXPIRED,
    INVITE_NOT_CONNECTION,
    INVITE_UNAVAILABLE,
    BLOCKED,
    CREDENTIAL_LOCKED,
    CONFLICT,
    LIMIT,

    /**
     * The vault is past its owner-check deadline (`owner_check_required`, VAULT-MESSAGING §3.6.3): nothing but
     * the check until it passes. What the member entered stays; the check follows when they leave the screen.
     */
    OWNER_CHECK_REQUIRED,

    /** The owner rejected this phone's transfer on their old phone (`device.pair.rejected`, §6.7). */
    REJECTED,
    OTHER,
}

/** A failed action: [kind] for the UI, [code] the spec's or API's code. */
class VaultFailure(val kind: FailureKind, val code: String? = null, val retryAfterSeconds: Long = 0, cause: Throwable? = null) :
    Exception("$kind${code?.let { " ($it)" } ?: ""}", cause)

// --- recovery on a new phone (§11.11) and direct transfer (§6.7.1) ---

/** Where a recovery on this phone stands (it survives restarts). */
enum class RecoveryStage {
    /** Not registered yet: scan or type the code. */
    CODE,

    /** Registered (§11.11.3): unlock with the PIN. */
    PIN,

    /** Unlocked and paired as a restricted app: the credential password (§11.11.5). */
    PASSWORD,
}

/** `vault.recovery.register`'s answer (§11.11.3), or the member API's gate before it. */
sealed interface RecoveryRegistration {
    /** Claimed and registered: [emailHint] names the account the vault belongs to (§11.11.7). */
    data class Registered(val emailHint: String) : RecoveryRegistration

    /**
     * Refused: [code] is the enclave's (`no_recovery`, `used`, `expired`, `too_early`, `bad_code`,
     * `attestation`, `bad_request`, `retry`), `not_available` (the API's `409 recovery_not_available`, at the claim
     * or the register) or `no_recovery` (the claim's `404`: the QR does not name the vault's current recovery).
     */
    data class Refused(val code: String) : RecoveryRegistration
}

/** `credential.recover`'s outcome (§11.11.5). */
enum class RecoverOutcome {
    /** The credential was handed over: this phone holds the vault; the old app is removed. */
    RECOVERED,

    /**
     * The vault keeps no backup copy of its credential (`no_backup`, or an older vault's `credential_lost`): it cannot
     * be recovered (VAULT-MESSAGING 0.16.0); it can only be deleted on the account site and replaced.
     */
    NO_BACKUP,

    /** `credential_required`: the vault has no credential (should not happen past enrollment). */
    CREDENTIAL_REQUIRED,
}

/**
 * A pending start-over (VAULT-MESSAGING 0.16.0 §11.11.9): the vault is deleted at [deletesAt] unless cancelled.
 * [executing]: too late to cancel. [deletionId]: the API's id when the status names it (needed to cancel in the app).
 */
data class DeletionView(val deletesAt: Instant, val executing: Boolean, val deletionId: String?) {
    val cancellable: Boolean get() = !executing && deletionId != null
}

/** A direct transfer the old phone opened (`device.transfer.create`, §6.7.1). */
data class TransferOfferView(
    val transferId: String,
    /** The QR content: the compact JSON pairing payload (§6.4). */
    val qrPayload: String,
    /** The same code as a link, for pasting. */
    val link: String,
    val expiresAt: Instant,
)

/** `device.transfer.pending` (§6.7.1): the new phone's handshake checked out. */
data class TransferPendingView(val transferId: String, val name: String, val sas: String)
