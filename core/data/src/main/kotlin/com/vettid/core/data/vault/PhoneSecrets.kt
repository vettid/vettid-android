package com.vettid.core.data.vault

import com.vettid.core.crypto.altchan.UnlockSecret
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.keystore.PhoneKeyHandle

/**
 * The phone's unlock secret for a vault of release R (VAULT-MESSAGING 0.33.0 §11.14.1, ANDROID-PLAN 0.1.40): which
 * key ([keyVault], the vault id it was made for, "" for a transfer's new phone) and which [epoch] (0: this vault
 * has no phone secret, a vault from before release R). The secret itself is computed by the phone key each time
 * and never kept.
 */
data class PhoneSecretState(val keyVault: String = "", val epoch: Long = 0) {
    val inUse: Boolean get() = epoch > 0
}

/** Computes secrets from [keys] (the Keystore's [com.vettid.core.keystore.PhoneKey]; a software key in tests). */
class PhoneSecrets(private val keys: (String) -> PhoneKeyHandle) {
    /**
     * Makes a new phone key for [keyVault] (enrollment, a transfer to this phone, a recovery) and returns the state
     * at epoch 1. A key the Keystore keeps in software is refused ([FailureKind.HARDWARE_KEYS]).
     */
    fun create(keyVault: String): PhoneSecretState {
        try {
            keys(keyVault).create()
        } catch (e: KeystoreException) {
            throw VaultFailure(FailureKind.HARDWARE_KEYS, "phone_key", cause = e)
        }
        return PhoneSecretState(keyVault, 1)
    }

    /**
     * The key for [keyVault] at epoch 1: [current] when it is exactly that and its key still exists (a retry of an
     * enrollment or a transfer whose request may have reached the vault already, which then holds its secret), a new
     * key otherwise ([create]).
     */
    fun reuseOrCreate(current: PhoneSecretState, keyVault: String): PhoneSecretState =
        if (current.epoch == 1L && current.keyVault == keyVault && keys(keyVault).exists()) current else create(keyVault)

    /** The secret of [s]'s epoch plus [ahead]; [FailureKind.DEVICE_SECRET] when the key is gone (data cleared). */
    @Suppress("ThrowsCount")
    fun secret(s: PhoneSecretState, ahead: Long = 0): ByteArray {
        val k = keys(s.keyVault)
        if (!s.inUse || !k.exists()) throw VaultFailure(FailureKind.DEVICE_SECRET, "no_phone_key")
        return try {
            UnlockSecret.compute(s.keyVault, s.epoch + ahead, k::hmac)
        } catch (e: KeystoreException) {
            throw VaultFailure(FailureKind.DEVICE_SECRET, "no_phone_key", cause = e)
        } catch (e: java.security.GeneralSecurityException) {
            throw VaultFailure(FailureKind.DEVICE_SECRET, "no_phone_key", cause = e)
        }
    }

    /** Removes the key (a wipe, the end of a transfer's keep period). */
    fun delete(s: PhoneSecretState) {
        runCatching { keys(s.keyVault).delete() }
    }
}

/** Which unlock answers prove the phone's next epoch (release R, §11.14.1, §11.14.2). */
object UnlockEpoch {
    /**
     * After `device_secret` at the stored epoch, the next epoch's try: it is the vault's secret when the answer is one
     * the vault gives only after the secret matched (`ok`, `bad_pin`, `transfer_incomplete`). Answers decided before
     * the secret check (`backoff`, `manifest`, `retry`, …) prove nothing and leave the epoch as it is; a second
     * `device_secret` means neither epoch is the vault's.
     */
    fun confirmsNext(ok: Boolean, code: String?): Boolean = ok || code in AFTER_SECRET

    private val AFTER_SECRET = setOf("bad_pin", "transfer_incomplete")

    /** What an unlock does after the answer to the current record ([afterCurrent]). */
    enum class Next {
        /** The answer stands. */
        DONE,

        /** `device_secret`: the same PIN is tried once more with the next epoch (an earlier change's answer may be lost). */
        TRY_NEXT,

        /**
         * (VAULT-MESSAGING 0.44.0 §11.14.9 step 5) `device_secret` while a PIN change is unresolved: the vault holds the
         * candidate, decided before any PIN was tested. The member is asked for the NEW PIN; the previous one is not
         * reused for the candidate.
         */
        ASK_NEW_PIN,
    }

    /**
     * Whether the PIN typed goes with the next epoch only: [pinChangeUnresolved] and the member typed it after the
     * previous PIN got `device_secret` ([newPinAsked]). Otherwise the unlock starts with the current record.
     */
    fun nextOnly(pinChangeUnresolved: Boolean, newPinAsked: Boolean): Boolean = pinChangeUnresolved && newPinAsked

    /** After the current record's answer (release R only: [releaseR]). */
    fun afterCurrent(releaseR: Boolean, ok: Boolean, code: String?, pinChangeUnresolved: Boolean): Next = when {
        !releaseR || ok || code != CODE_DEVICE_SECRET -> Next.DONE
        pinChangeUnresolved -> Next.ASK_NEW_PIN
        else -> Next.TRY_NEXT
    }

    private const val CODE_DEVICE_SECRET = "device_secret"

    /**
     * (§11.14.9 step 5, H1) Whether an unlock may be sent with the PIN typed at all: while a PIN change is unresolved only
     * the unlock screen's two steps ([twoStepScreen]) know which PIN the member typed. Any other unlock (the release
     * update's) sends nothing.
     */
    fun allowed(pinChangeUnresolved: Boolean, twoStepScreen: Boolean): Boolean = !pinChangeUnresolved || twoStepScreen

    /** One sealed unlock's answer, as [run] needs it: [releaseR] the request carried a phone secret. */
    data class Answer(val ok: Boolean, val code: String?, val releaseR: Boolean)

    /**
     * The records one unlock tries with the PIN typed (§11.14.1 rule 4, VAULT-MESSAGING 0.45.0 §11.14.9 step 5): [send]
     * sends it with the record [send]'s argument epochs ahead (0 the current one) and returns its outcome and [Answer].
     *
     * - Without an unresolved change: the current record, then the candidate with the same PIN.
     * - With one (or an uncertain PIN), the PIN typed first is the previous one and goes with the current record; its
     *   `ok` settles an uncertain PIN ([currentOk]) but not the change. Its `device_secret` first tries the other
     *   candidates that go with the previous PIN ([previousPinCandidates], an unanswered renew's), each once; an answer
     *   given after the secret matched confirms that one ([confirmNext]) and ends the unlock; one decided before the
     *   secret check ends it too. Only when every one answered `device_secret` is the new PIN asked for ([setNewPinAsked]).
     * - The PIN typed after that ([newPinAsked]) goes with the change's candidate only.
     *
     * A failure that never reached the vault (thrown by [send]) changes nothing.
     */
    @Suppress("LongParameterList", "ReturnCount")
    suspend fun <T> run(
        pinChangeUnresolved: Boolean,
        newPinAsked: Boolean,
        send: suspend (ahead: Int) -> Pair<T, Answer>,
        setNewPinAsked: (Boolean) -> Unit,
        confirmNext: suspend (ahead: Int) -> Unit,
        previousPinCandidates: List<Int> = emptyList(),
        currentOk: suspend () -> Unit = {},
    ): T {
        if (nextOnly(pinChangeUnresolved, newPinAsked)) {
            val (o, a) = send(1)
            setNewPinAsked(false) // answered: the candidate was tried once
            if (confirmsNext(a.ok, a.code)) confirmNext(1)
            return o
        }
        val (o, a) = send(0)
        if (a.ok) currentOk()
        return when (afterCurrent(a.releaseR, a.ok, a.code, pinChangeUnresolved)) {
            Next.DONE -> o
            Next.ASK_NEW_PIN -> {
                var last = o
                for (ahead in previousPinCandidates) {
                    val (oc, ac) = send(ahead)
                    if (confirmsNext(ac.ok, ac.code)) {
                        confirmNext(ahead)
                        return oc
                    }
                    if (ac.code != CODE_DEVICE_SECRET) return oc // decided before the secret check: proves nothing
                    last = oc
                }
                setNewPinAsked(true)
                last
            }
            Next.TRY_NEXT -> {
                val (o2, a2) = send(1)
                if (confirmsNext(a2.ok, a2.code)) confirmNext(1)
                o2
            }
        }
    }
}

/** The old phone's keys after a transfer it approved (release R, §11.14.10; owner decision 2026-10-15). */
object KeptTransfer {
    /** At most this long after the approval. */
    const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    sealed interface Decision {
        /** The 7 days are over: this phone's keys go. */
        data object Erase : Decision

        /** Still kept, for [remainingMs]. */
        data class Keep(val remainingMs: Long) : Decision
    }

    /**
     * When the 7 days count from: the approval, or now when the clock is behind it (stored, so that a clock kept behind
     * cannot extend them at every check).
     */
    fun start(approvedAtMs: Long, nowMs: Long): Long = minOf(approvedAtMs, nowMs)

    /**
     * The end-of-keep check's verdict (owner decision 2026-10-16): [deviceList] is the vault's `device.list` answer over
     * this phone's session (null: no answer, a refusal, any failure). This phone is still the vault's app only when the
     * list names it ([deviceId]) as an `app`; anything else erases.
     */
    fun stillTheApp(deviceList: kotlinx.serialization.json.JsonObject?, deviceId: String?): Boolean {
        val devices = deviceList?.get("devices") as? kotlinx.serialization.json.JsonArray
        return deviceId != null && devices != null && devices.any { e ->
            val d = e as? kotlinx.serialization.json.JsonObject
            d != null && com.vettid.core.vault.VaultJson.str(d, "id") == deviceId && com.vettid.core.vault.VaultJson.str(d, "kind") == "app"
        }
    }

    /**
     * Whether an approval that failed with [kind] may still have reached the vault: no answer came. Any other failure (a
     * refusal, the network before sending) means the transfer was not approved.
     */
    fun mayHaveReached(kind: FailureKind): Boolean = kind == FailureKind.NO_RESPONSE

    /**
     * [approvedAtMs] and [nowMs] are wall-clock times. A clock set before the approval counts from now, so that it never
     * lengthens the keep beyond 7 days from the moment it is noticed.
     */
    fun decide(approvedAtMs: Long, nowMs: Long): Decision {
        val elapsed = nowMs - approvedAtMs
        return when {
            elapsed >= KEEP_MS -> Decision.Erase
            elapsed < 0 -> Decision.Keep(KEEP_MS)
            else -> Decision.Keep(KEEP_MS - elapsed)
        }
    }
}

/** What ends "Finish the move" on the new phone (release R, §11.14.10). */
object FinishMoveProof {
    /**
     * An unlock the vault answered `ok` (every `ok` comes from reading the stored header and state with this phone's
     * own secret) whose result this phone took: not [overtaken] by a lock or a wipe meanwhile.
     */
    fun provedBy(resultOk: Boolean, overtaken: Boolean): Boolean = resultOk && !overtaken
}

/** `credential.create` on a vault of release R (§11.14.9). */
object CredentialCreate {
    /**
     * `exists`: the vault already holds this phone's credential (a create whose answer was lost, a recovered vault), so
     * the phone fetches it instead of asking the member for a new one. Before release R the answer stays an error.
     */
    fun fetchInstead(releaseR: Boolean, code: String?): Boolean = releaseR && code == "exists"
}

/** Which requests a vault gets, from its release's capabilities (VAULT-MESSAGING 0.34.0 §11.10.9). */
object ReleasePath {
    enum class Path {
        /** Release R's requests (§11.14): the phone's unlock secret, the recovery open. */
        R,

        /** The earlier release's requests, unchanged, with its earlier recovery. */
        EARLIER,
    }

    /** The refusal of an instance without `recovery_r` for a vault known to have it. */
    const val CODE_CAPABILITY_ROLLBACK = "capability_rollback"

    /**
     * [capabilities] of the instance (or transfer bundle) just verified; [releaseRKnown]: this phone has seen
     * `recovery_r` for the vault, or holds a phone secret for it. With `recovery_r`: [Path.R]. Without it: [Path.EARLIER],
     * unless release R is known, which is refused like a rollback ([FailureKind.CAPABILITY_ROLLBACK]) before anything is
     * sent. Never one path and then the other after a refusal.
     */
    fun choose(capabilities: List<String>, releaseRKnown: Boolean): Path = when {
        com.vettid.core.crypto.altchan.Capabilities.RECOVERY_R in capabilities -> Path.R
        releaseRKnown -> throw VaultFailure(FailureKind.CAPABILITY_ROLLBACK, CODE_CAPABILITY_ROLLBACK)
        else -> Path.EARLIER
    }

    /**
     * Whether this phone now marks the vault as release R ([wasMarked] before): only an R path on a phone that holds a
     * phone secret, or one that makes the phone key now ([makesPhoneKey]: enrollment, a transfer to this phone, a
     * recovery's register). An unlock of a phone without a phone secret that meets an R instance never marks it, so one
     * misrouting cannot leave the phone refused for its own (earlier) vault for good.
     */
    fun marks(wasMarked: Boolean, path: Path, makesPhoneKey: Boolean, holdsPhoneSecret: Boolean): Boolean =
        wasMarked || path == Path.R && (makesPhoneKey || holdsPhoneSecret)

    /** Whether recovery is offered: always for a release before R (its earlier recovery), else only with `recovery_offered`. */
    fun recoveryOffered(capabilities: List<String>): Boolean =
        com.vettid.core.crypto.altchan.Capabilities.RECOVERY_R !in capabilities ||
            com.vettid.core.crypto.altchan.Capabilities.RECOVERY_OFFERED in capabilities
}
