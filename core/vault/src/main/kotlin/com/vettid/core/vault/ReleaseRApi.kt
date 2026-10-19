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
 * before it spends the UTK or tries the PIN, and the transfer stays open.
 */
suspend fun VaultApi.transferApprove(transferId: String, pin: String, password: String, signer: String? = null): TransferApproved =
    TransferApproved.of(
        credentialOps.credOp(
            "device.transfer.approve",
            {
                put("password", password)
                put("pin", pin)
            },
            extra = TransferPending.approval(transferId, signer),
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
    onMarker: suspend (ChangeMarker) -> Unit = {},
) {
    credentialOps.change(ChangeTypes.PIN_CHANGE, {
        put("pin", pin)
        put("new_pin", newPin)
        put("password", password)
        put("new_unlock_secret", Base64s.encodeStd(newUnlockSecret))
    }, onMarker)
}

/**
 * `vault.secret.renew`: a new phone secret with the PIN unchanged; the DEK is replaced. (0.45.0 §11.14.9) [onSending]
 * stores the renew before it is sent, so that a lost answer can be decided ([SentRequest]).
 */
suspend fun VaultApi.secretRenew(
    pin: String,
    password: String,
    newUnlockSecret: ByteArray,
    onSending: (suspend (SentRequest) -> Unit)? = null,
    /** (Q1) The renew's own answer was `ok` (before its blob is kept and acknowledged). */
    onAnswered: (suspend (innerId: String) -> Unit)? = null,
) {
    credentialOps.credOp("vault.secret.renew", {
        put("pin", pin)
        put("password", password)
        put("new_unlock_secret", Base64s.encodeStd(newUnlockSecret))
    }, onSending = onSending, onAnswered = onAnswered)
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
    onMarker: suspend (ChangeMarker) -> Unit = {},
) {
    credentialOps.change(ChangeTypes.PASSWORD_CHANGE, {
        pin?.let { put("pin", it) }
        put("password", password)
        put("new_password", newPassword)
    }, onMarker)
}
