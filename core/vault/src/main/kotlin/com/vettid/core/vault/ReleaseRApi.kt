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
 * The app moves to the next epoch only with the answer (`epoch_advanced`).
 */
suspend fun VaultApi.pinChange(pin: String, newPin: String, password: String, newUnlockSecret: ByteArray) {
    credentialOps.credOp("pin.change", {
        put("pin", pin)
        put("new_pin", newPin)
        put("password", password)
        put("new_unlock_secret", Base64s.encodeStd(newUnlockSecret))
    })
}

/** `vault.secret.renew`: a new phone secret with the PIN unchanged; the DEK is replaced. */
suspend fun VaultApi.secretRenew(pin: String, password: String, newUnlockSecret: ByteArray) {
    credentialOps.credOp("vault.secret.renew", {
        put("pin", pin)
        put("password", password)
        put("new_unlock_secret", Base64s.encodeStd(newUnlockSecret))
    })
}
