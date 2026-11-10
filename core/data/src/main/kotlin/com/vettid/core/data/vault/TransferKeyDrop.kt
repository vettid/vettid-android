package com.vettid.core.data.vault

import com.vettid.core.vault.DroppedTransfer

/**
 * The app's side of a dropped transfer to this phone (VAULT-MESSAGING §6.7.1, §11.14.10 rule 5, and the failure table: the
 * new phone "drops its handshake, attested key and epoch-1 secret and cannot use them"). The device calls [dropped]
 * ([com.vettid.core.vault.DeviceConfig.transferDropped]) after every device-level drop of an UNPAIRED transfer, under its
 * lock and after the drop is saved: a confirmed cancel, the 60-second wait for `hs.resp`, the DROP at start, the
 * settlement's drop, the dead mailbox's drop, a rejection, and any later drop path that goes through the device's drop.
 *
 * Why: a transfer's epoch-1 secret is `HMAC(phone key "vault-unlock-", … epoch 1)` (form 1, [PhoneSecrets]), a pure
 * function of that key. Before this fix the next scan reused the key ([PhoneSecrets.reuseOrCreate] while the record said
 * `("", 1)`), so the next scan of ANY transfer QR (another vault's included) sent the same secret: a first QR that was an
 * attacker's taught that vault the secret this phone later presents to the member's real vault. Now two things hold,
 * each enough on its own: every transfer scan makes a NEW key (`VaultManager.newTransferPhoneKey`), and a drop deletes
 * the key, as the spec says it must ("cannot use them").
 *
 * What it does, in this order:
 * 1. **The record**: `("", 1)` becomes `("", 0)` (a conditional write under the record's lock). From then on
 *    [PhoneSecrets.reuseOrCreate] never matches, so the next scan makes a new key ([PhoneSecrets.create] replaces the
 *    alias), whatever the Keystore does below.
 * 2. **The Keystore key** `vault-unlock-` goes. A process killed between 1 and 2 leaves a key that no record names; the
 *    next scan replaces it. A delete that fails is logged (its type and alias only) and the drop goes on: step 1 already
 *    keeps the secret from being sent again.
 * 3. **The attested keys** the transfer's `hs.init` carried go from the identity slot ([removeAttestKeys]); one the
 *    Keystore could not delete stays listed and is logged; nothing of a later scan depends on it (every attestation is a
 *    new key).
 *
 * Only a transfer's key: the record must name the transfer key vault "" at epoch 1 (or 0, a reset whose Keystore delete
 * did not happen yet). A key made for a vault id (an enrollment, a recovery), or at a later epoch, is never touched, and
 * the device never calls this for a device with a device id (a paired transfer keeps its key for life).
 *
 * Also the start-up sweep's delete ([com.vettid.core.vault.VaultDevice.settleTransferKeysAtStart]): with no device id and
 * no transfer record, a `("", 1)` key belongs to no transfer and goes.
 *
 * Returns true once the record no longer says `("", 1)` or the key is gone. False only when both failed: the device keeps
 * the drop recorded and calls again (at the next message, the next start, and before the next scan makes its new key).
 */
internal class TransferKeyDrop(
    private val phoneSecrets: PhoneSecrets,
    /** The account record's phone key state ([LocalAccount.phone]). */
    private val phone: () -> PhoneSecretState,
    /**
     * Writes `("", 0)` only if the record still says `("", 1)`, under the record's lock; true when written. May throw
     * (the write failed).
     */
    private val resetPhone: () -> Boolean,
    /**
     * Deletes these attestation key aliases from the identity slot in use ([AttestationKeys.remove]) and returns those
     * still there (the Keystore could not delete them: they stay listed).
     */
    private val removeAttestKeys: (List<String>) -> List<String>,
    private val log: (String) -> Unit,
) {
    @Suppress("TooGenericExceptionCaught") // any failure of a step: logged, the next step still runs
    fun dropped(drop: DroppedTransfer): Boolean {
        val before = phone()
        val transferKey = before.keyVault == TRANSFER_KEY_VAULT && before.epoch <= 1L
        var reset = false
        if (transferKey && before.epoch == 1L) {
            reset = try {
                resetPhone()
            } catch (e: Exception) {
                log("the phone key's record could not be reset: ${e.javaClass.simpleName}")
                false
            }
        }
        var deleted = false
        if (transferKey) {
            deleted = try {
                phoneSecrets.deleteKey(TRANSFER_KEY_VAULT)
                true
            } catch (e: Exception) {
                log("the phone key ${PhoneSecrets.alias(TRANSFER_KEY_VAULT)} could not be deleted: ${e.javaClass.simpleName}")
                false
            }
        }
        if (drop.attestKeys.isNotEmpty()) {
            try {
                val left = removeAttestKeys(drop.attestKeys)
                if (left.isNotEmpty()) log("the attested keys $left could not be deleted")
            } catch (e: Exception) {
                log("the attested keys ${drop.attestKeys} could not be deleted: ${e.javaClass.simpleName}")
            }
        }
        // Reuse needs both the record at ("", 1) and the key: either one gone is enough. A record that was not ("", 1)
        // to begin with (another vault's key, a later epoch, nothing) is never reused for a transfer either.
        return !(transferKey && before.epoch == 1L) || reset || deleted
    }

    companion object {
        /** A transfer's phone key is made for "" (the vault id is not known yet, [PhoneSecretState.keyVault]). */
        const val TRANSFER_KEY_VAULT = ""

        /**
         * The record's reset ([resetPhone]'s write, under the record's lock): `("", 1)` becomes `("", 0)`; any other record
         * is left as it is (null: nothing written), so a key made meanwhile for a vault, or a later epoch, is never undone.
         */
        fun resetRecord(cur: LocalAccount): LocalAccount? =
            if (cur.phoneKeyVault == TRANSFER_KEY_VAULT && cur.phoneEpoch == 1L) cur.copy(phoneEpoch = 0) else null
    }
}
