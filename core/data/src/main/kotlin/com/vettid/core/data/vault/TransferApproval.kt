package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException

/** The old phone's approval of a transfer (§6.7.1 step 3, release R §11.14.10). */
internal object TransferApproval {
    /**
     * (Owner decision 2026-10-23) What runs right before each approval is deposited: [renew] the vault's standing
     * deposit token for this phone's mailbox (its full 30 days from the approval, which the vault later uses to deliver
     * `device.unlinked{transferred}`), while this phone is still the vault's app. A failed renewal never blocks the
     * approval: [onRenewFailed] logs it (its class only) and the notice has whatever the old token has left. A
     * cancellation is not a failure: it stops the approval too.
     */
    @Suppress("TooGenericExceptionCaught") // any failure of the renewal is only logged
    fun renewal(renew: suspend () -> Unit, onRenewFailed: (Exception) -> Unit): suspend () -> Unit = {
        try {
            renew()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onRenewFailed(e)
        }
    }
}
