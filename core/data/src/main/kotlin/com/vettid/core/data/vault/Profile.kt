package com.vettid.core.data.vault

import com.vettid.core.crypto.IkFingerprint
import com.vettid.core.data.account.AccountNames
import com.vettid.core.vault.NameRequest
import com.vettid.core.vault.Profile
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.format.DateTimeParseException

/** Where a name change stands (VAULT-MESSAGING 0.18.0 §10.8). */
enum class NameRequestState {
    /** Sent to VettID; the account's names change once the member API applies it. */
    PENDING,
    APPLIED,
    REFUSED,
    ;

    companion object {
        fun of(wire: String): NameRequestState? = when (wire) {
            "pending" -> PENDING
            "applied" -> APPLIED
            "refused" -> REFUSED
            else -> null
        }
    }
}

/** The latest `account.name.set` request (§10.8): [reason] (`too_soon`, `invalid`, `account`) only when refused. */
data class NameRequestView(
    val seq: Long,
    val firstName: String,
    val lastName: String,
    val requestedAt: Instant?,
    val state: NameRequestState,
    val reason: String? = null,
) {
    companion object {
        const val REASON_TOO_SOON = "too_soon"
        const val REASON_INVALID = "invalid"
        const val REASON_ACCOUNT = "account"

        /** The vault's request; null for one this app cannot read (an unknown state). */
        fun of(r: NameRequest): NameRequestView? {
            val st = NameRequestState.of(r.state) ?: return null
            return NameRequestView(
                seq = r.seq,
                firstName = r.firstName,
                lastName = r.lastName,
                requestedAt = parseInstant(r.requestedAt),
                state = st,
                reason = r.reason?.takeIf { st == NameRequestState.REFUSED },
            )
        }
    }
}

/**
 * The member's own shared profile (§10.8): the read-only core ([firstName], [lastName] from the account snapshot,
 * the vault's identity key as [fingerprint]) and the editable display name ([displayName], "" for none).
 */
data class OwnProfile(
    val version: Long,
    val displayName: String,
    val firstName: String?,
    val lastName: String?,
    val fingerprint: String?,
    /** The profile photo (§10.8): base64 of a JPEG or PNG of at most 65,536 bytes; null for none. */
    val photo: String? = null,
) {
    val fullName: String? get() = AccountNames.full(firstName, lastName)
}

/** What `account.name.set` came to (§10.8). */
sealed interface NameChangeOutcome {
    /** Stored by the vault and handed to VettID: the request (normally `pending`). */
    data class Requested(val request: NameRequestView) : NameChangeOutcome

    /** At most one applied change per 30 days: [allowedAfter] (null when the vault did not say). */
    data class TooSoon(val allowedAfter: Instant?) : NameChangeOutcome

    /** The names break the registration rule, or equal the current ones (`bad_request`). */
    data object Invalid : NameChangeOutcome

    /** The PIN was wrong: a failed owner check (§3.6.4); [checksLeft] before the vault locks, when known. */
    data class BadPin(val checksLeft: Int?) : NameChangeOutcome

    data class BadPassword(val checksLeft: Int?) : NameChangeOutcome

    /** A PIN or password backoff runs ([retryAfterSeconds] 0 when the vault did not say how long). */
    data class Backoff(val retryAfterSeconds: Long) : NameChangeOutcome

    data class Failed(val kind: FailureKind, val code: String?) : NameChangeOutcome
}

/** The member's shared profile and the account's names (§10.8). Failures other than [NameChangeOutcome] are [VaultFailure]s. */
interface ProfileRepository {
    /** Null until read ([refreshProfile]). */
    val profile: StateFlow<OwnProfile?>

    /** Re-reads `profile.get`. */
    suspend fun refreshProfile()

    /** `profile.set{name}`: the display name ("" removes it). Never the core, which is read-only. */
    suspend fun setDisplayName(name: String)

    /** `profile.set{photo}`: [photo] the base64 JPEG or PNG (at most 65,536 bytes of image); "" removes it. */
    suspend fun setPhoto(photo: String)

    /**
     * `account.name.set` with the PIN, the credential password and the two names, which the caller has checked
     * with [AccountNames.check] (they are sent normalised). The account's names change only once VettID applied the
     * request; the outcome arrives in the account (`AccountInfo.nameRequest`).
     */
    suspend fun changeName(pin: String, password: String, firstName: String, lastName: String): NameChangeOutcome
}

/** The vault calls behind [ProfileManager]. */
interface ProfileOps {
    suspend fun profileGet(): Profile

    suspend fun profileSet(version: Long, name: String): Long

    suspend fun profileSetPhoto(version: Long, photo: String): Long

    suspend fun accountNameSet(pin: String, password: String, firstName: String, lastName: String): NameRequest
}

/**
 * [ProfileRepository] over [ProfileOps]. [onRequest] keeps a new name request with the account; [checksLeft]
 * re-reads the owner check's count after a failed PIN or password (§3.6.4: a name change counts as a check).
 */
class ProfileManager(
    private val ops: suspend () -> ProfileOps,
    private val onRequest: suspend (NameRequestView) -> Unit,
    private val checksLeft: suspend () -> Int?,
) : ProfileRepository {
    private val state = MutableStateFlow<OwnProfile?>(null)
    override val profile: StateFlow<OwnProfile?> = state.asStateFlow()

    /** Forgets the profile read (a wipe of this phone). */
    fun clear() {
        state.value = null
    }

    override suspend fun refreshProfile() {
        val p = vaultGuard { ops().profileGet() }
        state.value = OwnProfile(
            version = p.version,
            displayName = p.name,
            firstName = p.firstName?.takeIf { AccountNames.isValidCore(it) },
            lastName = p.lastName?.takeIf { AccountNames.isValidCore(it) },
            fingerprint = p.ik?.let { IkFingerprint.formatB64(it) },
            photo = p.photo?.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun setPhoto(photo: String) {
        val version = vaultGuard {
            val o = ops()
            val current = state.value?.version ?: o.profileGet().version
            o.profileSetPhoto(current, photo)
        }
        state.value = state.value?.copy(version = version, photo = photo.takeIf { it.isNotEmpty() })
        runCatching { refreshProfile() }
    }

    override suspend fun setDisplayName(name: String) {
        val trimmed = name.trim()
        val version = vaultGuard {
            val o = ops()
            val current = state.value?.version ?: o.profileGet().version
            o.profileSet(current, trimmed)
        }
        state.value = state.value?.copy(version = version, displayName = trimmed)
        runCatching { refreshProfile() }
    }

    @Suppress("ReturnCount")
    override suspend fun changeName(pin: String, password: String, firstName: String, lastName: String): NameChangeOutcome {
        val first = AccountNames.normalize(firstName) ?: return NameChangeOutcome.Invalid
        val last = AccountNames.normalize(lastName) ?: return NameChangeOutcome.Invalid
        return try {
            val r = vaultGuard { ops().accountNameSet(pin, password, first, last) }
            val view = NameRequestView.of(r) ?: NameRequestView(r.seq, first, last, null, NameRequestState.PENDING)
            onRequest(view)
            NameChangeOutcome.Requested(view)
        } catch (e: VaultFailure) {
            failed(e)
        }
    }

    private suspend fun failed(e: VaultFailure): NameChangeOutcome = when (e.code) {
        CODE_TOO_SOON -> NameChangeOutcome.TooSoon(
            parseInstant((e.cause as? VaultOpException)?.body?.let { com.vettid.core.vault.VaultJson.str(it, "allowed_after") }),
        )
        CODE_BAD_REQUEST -> NameChangeOutcome.Invalid
        CODE_BAD_PIN -> NameChangeOutcome.BadPin(runCatching { checksLeft() }.getOrNull())
        CODE_BAD_PASSWORD -> NameChangeOutcome.BadPassword(runCatching { checksLeft() }.getOrNull())
        CODE_BACKOFF -> NameChangeOutcome.Backoff(e.retryAfterSeconds)
        else -> NameChangeOutcome.Failed(e.kind, e.code)
    }

    private companion object {
        const val CODE_TOO_SOON = "too_soon"
        const val CODE_BAD_REQUEST = "bad_request"
        const val CODE_BAD_PIN = "bad_pin"
        const val CODE_BAD_PASSWORD = "bad_password"
        const val CODE_BACKOFF = "backoff"
    }
}

internal fun parseInstant(s: String?): Instant? = s?.let {
    try {
        Instant.parse(it)
    } catch (_: DateTimeParseException) {
        null
    }
}
