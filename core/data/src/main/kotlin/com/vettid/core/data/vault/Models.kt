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
    /** The account's names (VAULT-MESSAGING 0.18.0 §11.13): what every connection sees; null before a snapshot had them. */
    val firstName: String? = null,
    val lastName: String? = null,
    /** `name_change.allowed_after`: the next name change is applied only after it; null: now. */
    val nameAllowedAfter: Instant? = null,
    /** The latest name change this app asked for (`account.get`'s `name_request`, §10.8). */
    val nameRequest: NameRequestView? = null,
    /** The member's full address from the snapshot (VAULT-MESSAGING 0.20.0 `email`); null from an older vault. */
    val email: String? = null,
) {
    val hasSnapshot: Boolean get() = state != null

    /** The address to show: the full [email] when the snapshot carries it, else the masked [emailHint] (S4 vaults). */
    val displayEmail: String? get() = email?.takeIf { it.isNotBlank() } ?: emailHint.takeIf { it.isNotEmpty() }

    /** "First Last" (§10.8); null before the snapshot carried the names. */
    val fullName: String? get() = com.vettid.core.data.account.AccountNames.full(firstName, lastName)

    /** Whether a name change would be refused `too_soon` at [now] (the snapshot's `allowed_after` lies ahead). */
    fun nameChangeTooSoon(now: Instant): Boolean = nameAllowedAfter?.isAfter(now) == true
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
    /** The manifest's releases between the routed one and [offer] (What's new, ANDROID-PLAN 0.1.31). */
    val between: List<ReleaseView> = emptyList(),
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

    /**
     * `signer_refused` (VAULT-MESSAGING 0.27.0 §11.7.1): the running release does not pin the signer recorded
     * for this installation's key (or recorded none and got no valid chain). Decided before the PIN and the same
     * whatever PIN was sent: not a PIN failure, no backoff, nothing to wait for.
     */
    data object SignerRefused : UnlockAttempt

    /** [retryAfterSeconds]: when the service said when to try again (`Retry-After`, e.g. [FailureKind.SERVICE_PAUSED]). */
    data class Failed(val kind: FailureKind, val code: String?, val retryAfterSeconds: Long = 0) : UnlockAttempt

    companion object {
        /**
         * [Failed]'s code when this phone has no `user_guid` to unlock with: it was set up by a direct transfer from
         * a vault release before VAULT-MESSAGING 0.17.0, whose `device.paired` did not carry one (§6.7.1).
         */
        const val CODE_NO_USER_GUID = "no_user_guid"
    }
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
    /**
     * The vault's signer binding from this app's own `device.list` entry (VAULT-MESSAGING 0.27.0 §10.3), for the
     * "Installed from" line; null when the vault reports none or is not open.
     */
    val signer: SignerView? = null,
    /** (Release R, VAULT-MESSAGING 0.33.0 §11.14.11) `vault.status`'s `recovery`; null from an earlier release. */
    val recovery: RecoveryStatusView? = null,
    /**
     * (0.34.0, §11.10.9) Whether the vault's release offers recovery: false for a release with `recovery_r` and without
     * `recovery_offered` (recovery is hidden: "This vault's release does not offer recovery").
     */
    val recoveryOffered: Boolean = true,
)

/** `vault.status`'s `recovery` for the status screen: never shown as "0 of 10" when [state] is `unknown`. */
data class RecoveryStatusView(
    val state: String,
    val used: Int?,
    val counterUnavailable: Boolean,
    /** (§11.14.5) Unavailable at every read for 24 hours: shown with the urgency of a used attempt. */
    val unknownForADay: Boolean = false,
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
    /** The vault refused this PHONE's attestation (the `attestation` result of an enrollment or an unlock). */
    ATTESTATION,
    ROLLBACK,

    /**
     * (0.34.0, §11.10.9) The vault's instance lacks `recovery_r` although this phone has seen release R for the vault:
     * refused like a rollback, nothing sent.
     */
    CAPABILITY_ROLLBACK,

    /** The release manifest did not verify (signature, key, format, serial), or the vault refused the app's. */
    MANIFEST,

    /** The release manifest could not be loaded (the server answered, but not with the document). */
    MANIFEST_UNAVAILABLE,

    /**
     * The vault runs a release that is not in the manifest this app trusts (VAULT-MESSAGING §11.10.1: "absent from
     * the manifest is unknown: apps refuse to send it a PIN"): a stale or blocked manifest fetch, or a canary
     * release whose manifest this app does not have. Every try loads the manifest again.
     */
    RELEASE_UNKNOWN,

    /** The ENCLAVE's attestation document or descriptor did not verify: nothing was sent to it. */
    ENCLAVE,
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

    /** A tag a share rule names cannot be deleted or merged (`in_use`, §10.8). */
    IN_USE,

    /**
     * The vault is past its owner-check deadline (`owner_check_required`, VAULT-MESSAGING §3.6.3): nothing but
     * the check until it passes. What the member entered stays; the check follows when they leave the screen.
     */
    OWNER_CHECK_REQUIRED,

    /** The owner rejected this phone's transfer on their old phone (`device.pair.rejected`, §6.7). */
    REJECTED,

    /**
     * The vault's release does not accept this installation's signer (`signer_refused`, VAULT-MESSAGING 0.27.0
     * §11.7.1): not a wrong PIN, nothing counted; the app is updated, or the vault recovered with a current one.
     */
    SIGNER_REFUSED,

    /**
     * The member API refused this phone's requests (`401`) and its answer shows the phone's clock to be more than a
     * few minutes off: the cause is the date and time, not a key the vault no longer has (A6 audit N4, N5).
     */
    CLOCK,

    /**
     * The member API said, explicitly, that the account has no vault (`"vault": null`; A6 audit N6): after a vault
     * was deleted or an account closed, but also in a fault of the service (MEMBER-API "Dark launch" answers it to
     * every member). Never acted on by itself: the member decides whether this phone starts over.
     */
    NO_VAULT,

    /**
     * What this phone stores for the vault (its keys and device state, the account record) cannot be read: a
     * Keystore or file failure. Nothing is reset by itself (A6 audit P4): the member tries again or erases the phone.
     */
    LOCAL_STATE,

    /**
     * A wipe of this phone did not finish, or could not start (its marker could not be written): the old vault's keys
     * may still be here. Nothing is enrolled and no session opens until a wipe finishes ("Try again" runs it again).
     */
    ERASE_FAILED,

    // --- release R (VAULT-MESSAGING 0.33.0 §11.14; ANDROID-PLAN 0.1.40) ---

    /** This phone no longer holds the vault's keys: `device_secret` at the next epoch too, or the phone key is gone. */
    DEVICE_SECRET,

    /** The move to this phone did not finish (`transfer_incomplete`): the old phone approves it again. */
    TRANSFER_INCOMPLETE,

    /** The service is busy (`busy`, the heavy-derivation permit): nothing was used or changed; try again in a minute. */
    BUSY,

    /** The service was too slow (`retry`): nothing was used or changed; try again. */
    RETRY,

    /** The attempt record cannot be reached (`counter_unavailable`): nothing was used or changed. */
    COUNTER_UNAVAILABLE,

    /** The vault's stored data could not be opened (`stored_mismatch`): contact VettID. */
    STORED_MISMATCH,

    /** The new password breaks a rule (`weak_password`); the reason is [VaultFailure.weakPasswordReason]. */
    WEAK_PASSWORD,

    /** The phone keeps its keys in software only: no enrollment, transfer to it or recovery. */
    HARDWARE_KEYS,
    /**
     * The vault's answer was too large to send to this phone (`response_too_large`, VAULT-MESSAGING 0.32.0 §8.1): an
     * answer is never silence. Nothing changed; a list asked for again in smaller pages may fit.
     */
    RESPONSE_TOO_LARGE,
    OTHER,
}

/**
 * A failed action: [kind] for the UI, [code] the spec's or API's code; [limit] the limit a `limit` error names
 * (VAULT-MESSAGING 0.21.0 §10.1), null from an older vault.
 */
class VaultFailure(
    val kind: FailureKind,
    val code: String? = null,
    val retryAfterSeconds: Long = 0,
    cause: Throwable? = null,
    val limit: VaultLimit? = null,
) : Exception("$kind${code?.let { " ($it)" } ?: ""}${limit?.let { " [${it.name}]" } ?: ""}", cause) {
    /** `weak_password`'s `reason` (§11.14.7): `short`, `repeated`, `common`, `same_as_pin` or `guessable`. */
    val weakPasswordReason: String?
        get() = (cause as? com.vettid.core.vault.VaultOpException)?.body?.let { com.vettid.core.vault.VaultJson.str(it, "reason") }
}

/**
 * The limit a `limit` error names (VAULT-MESSAGING 0.21.0 §10.1: `{limit, max, size?}`): [name] one of the spec's
 * table (a name this app does not know reads as a generic "limit reached"), [max] the bound (a count, or bytes for a
 * `*_size` limit), [size] for a size limit the size the refused request would have reached.
 */
data class VaultLimit(val name: String, val max: Long? = null, val size: Long? = null)

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
     * or the register) or `no_recovery` (the claim's `404`: the QR, or a typed code's locator, does not name a
     * vault's current recovery). [recoveryId] is the recovery a typed code's claim named (0.25.0 §11.11.10), so that
     * wrong codes are counted per recovery however they were read; null when the claim did not answer with one.
     */
    data class Refused(val code: String, val recoveryId: String? = null) : RecoveryRegistration
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

/**
 * `device.transfer.pending` (§6.7.1): the new phone's handshake checked out. (0.27.0, §11.7.1) [signer]: the
 * attested class of the new app's signer as the vault named it (null: an older vault, or not an Android app);
 * [signerChanged]: it differs from the signer the vault is bound to, so the member confirms the change before the
 * PIN and the approval names [signer]; [currentSigner]: this app's own signer, for that confirmation's text (null
 * when it could not be read).
 */
data class TransferPendingView(
    val transferId: String,
    val name: String,
    val sas: String,
    val signer: String? = null,
    val signerChanged: Boolean = false,
    val currentSigner: SignerClass? = null,
)
