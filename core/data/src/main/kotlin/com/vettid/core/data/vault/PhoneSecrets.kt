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
