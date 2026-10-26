package com.vettid.core.vault

import com.vettid.core.crypto.Base64s
import kotlinx.serialization.json.put

// Release R (VAULT-MESSAGING 0.33.0 §11.14.9, §11.14.10): the changes of PIN and phone secret, holder credential
// operations, and the transfer approval with its answer.

/**
 * `device.transfer.approve` with the blob and, sealed to a UTK, the PIN and the
 * password. `{}` (before release R) means the transfer is complete: this app has been removed; release R answers
 * `{"unlink": "after_new_unlock"}` ([TransferApproved]).
 * [signer] (0.27.0, §6.7.1 step 3): the new app's signer class, the holder's explicit confirmation, sent when
 * the pending said `signer_changed`; null sends no member. Without it the vault answers `signer_unconfirmed`
 * before it tries the PIN, and the transfer stays open; (0.52.0) the UTK is spent all the same, and the next approval
 * takes a fresh one.
 */
suspend fun VaultApi.transferApprove(
    transferId: String,
    pin: String,
    password: String,
    signer: String? = null,
    /**
     * (§11.14.10; owner decision 2026-10-23) Run right before each copy of the approval is deposited (also the one sent
     * again after a `stale_credential` fetch): the old phone's token renewal ([VaultDevice.renewIssuedToken]). A throw
     * stops the approval, nothing sent.
     */
    beforeSend: (suspend () -> Unit)? = null,
): TransferApproved =
    TransferApproved.of(
        credentialOps.credOp(
            "device.transfer.approve",
            {
                put("password", password)
                put("pin", pin)
            },
            extra = TransferPending.approval(transferId, signer),
            onSending = beforeSend?.let { f -> { _ -> f() } },
        ).first,
    )

/**
 * `pin.change`: the current PIN, the new one, the password and the phone's next epoch's secret, sealed to one UTK.
 * The app moves to the next epoch only with the answer (`epoch_advanced`), or when the change is decided as applied.
 * (0.44.0, §11.14.9) [onMarker] stores the marker before the send; no answer throws [ChangeUnansweredException], and a
 * `stale_credential` is never retried with the typed secrets ([CredentialOps.change]).
 */
suspend fun VaultApi.pinChange(
    pin: String,
    newPin: String,
    password: String,
    newUnlockSecret: ByteArray,
    /** [credentialGeneration] as read before the phone's epoch: refused if a change or a renew went out since. */
    since: Long? = null,
    onMarker: suspend (ChangeMarker) -> Unit = {},
) {
    credentialOps.change(ChangeTypes.PIN_CHANGE, {
        put("pin", pin)
        put("new_pin", newPin)
        put("password", password)
        put("new_unlock_secret", Base64s.encodeStd(newUnlockSecret))
    }, onMarker, since)
}

/**
 * `vault.secret.renew`: a new phone secret with the PIN unchanged; the DEK is replaced. (0.45.0 §11.14.9) [onSending]
 * stores the renew before it is sent, so that a lost answer can be decided ([SentRequest]). (0.47.0) Sent only once every
 * earlier credential request is settled and with the vault's current blob, both checked before [onSending]
 * ([CredentialOps.renew]); a `stale_credential` is not retried.
 */
suspend fun VaultApi.secretRenew(
    pin: String,
    password: String,
    newUnlockSecret: ByteArray,
    onSending: suspend (SentRequest) -> Unit = {},
    /** (Q1) The renew's own answer was `ok` (before its blob is kept and acknowledged). */
    onAnswered: (suspend (innerId: String) -> Unit)? = null,
    /** (R1) The renew's own answer was a refusal (`stale_credential` included): it did not apply and never will. */
    onRefused: (suspend (innerId: String, code: String) -> Unit)? = null,
    /** [credentialGeneration] as read before the phone's epoch: refused if a change or a renew went out since. */
    since: Long? = null,
) {
    credentialOps.renew("vault.secret.renew", {
        put("pin", pin)
        put("password", password)
        put("new_unlock_secret", Base64s.encodeStd(newUnlockSecret))
    }, onSending = onSending, onAnswered = onAnswered, onRefused = onRefused, since = since)
}

/**
 * `credential.password.change` (§10.6): the current password and the new one, and (0.33.0, release R) the PIN, which the
 * vault verifies; the change closes the recovery generation and arms the next. (0.44.0, §11.14.9) Decided rather than
 * repeated when its answer is lost: [onMarker] stores the marker before the send; no answer throws
 * [ChangeUnansweredException] ([CredentialOps.change]).
 */
suspend fun VaultApi.credentialChangePassword(
    password: String,
    newPassword: String,
    pin: String? = null,
    /** [credentialGeneration] as read when the change began: refused if a change or a renew went out since. */
    since: Long? = null,
    onMarker: suspend (ChangeMarker) -> Unit = {},
) {
    credentialOps.change(ChangeTypes.PASSWORD_CHANGE, {
        pin?.let { put("pin", it) }
        put("password", password)
        put("new_password", newPassword)
    }, onMarker, since)
}
