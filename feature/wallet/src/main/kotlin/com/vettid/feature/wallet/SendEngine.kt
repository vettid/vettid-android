package com.vettid.feature.wallet

import com.vettid.core.wallet.CapReason
import com.vettid.core.wallet.FeeEstimates
import com.vettid.core.wallet.PrepareResult
import com.vettid.core.wallet.PreparedSend
import com.vettid.core.wallet.SendRequest
import com.vettid.core.wallet.SendResult
import com.vettid.core.wallet.SpendCap
import com.vettid.core.wallet.WalletsManager

/** A payment prepared: the review to show, or what stopped it. */
sealed interface SendPrepared {
    class Ready(val review: Review) : SendPrepared {
        override fun toString(): String = "Ready"
    }

    class Stopped(val result: PrepareResult) : SendPrepared {
        override fun toString(): String = "Stopped"
    }
}

/**
 * What Send asks of the sending core ([WalletsManager]), so that the view model's tests need no chain server and no vault's PSBT. The
 * payment prepared is kept in the engine only (the PSBT never reaches the screen's state) until it is approved or replaced.
 */
interface SendEngine {
    suspend fun feeEstimates(walletId: String): FeeEstimates

    suspend fun prepare(walletId: String, request: SendRequest): SendPrepared

    /** Approve the payment prepared last, with the password (typed once). */
    suspend fun approve(password: String): SendResult

    /** Try again: the signed transaction is broadcast again, else the unanswered signing is sent again (the same request). */
    suspend fun retry(walletId: String): SendResult

    /** Asks the chain server whether it has the kept transaction (review note 2). */
    suspend fun checkSent(walletId: String): SendResult

    fun discard(walletId: String)

    fun hasUnsent(walletId: String): Boolean

    /** Forgets the payment prepared (not the signed one: that is [discard]). */
    fun forget()
}

/** [SendEngine] over the wallet manager. */
class ManagerSendEngine(private val wallets: WalletsManager) : SendEngine {
    private var prepared: PreparedSend? = null

    override suspend fun feeEstimates(walletId: String) = wallets.feeEstimates(walletId)

    override suspend fun prepare(walletId: String, request: SendRequest): SendPrepared {
        prepared = null
        return when (val r = wallets.prepareSend(walletId, request)) {
            is PrepareResult.Ready -> {
                prepared = r.prepared
                SendPrepared.Ready(reviewOf(r.prepared))
            }
            else -> SendPrepared.Stopped(r)
        }
    }

    override suspend fun approve(password: String): SendResult {
        val p = prepared ?: return SendResult.NothingToSend
        val result = wallets.approveSend(p, password)
        // The password was not accepted, or the unlock window asks again: the same payment can be approved once more.
        if (result != SendResult.CredentialLocked) prepared = null
        return result
    }

    override suspend fun retry(walletId: String): SendResult {
        val r = wallets.retryBroadcast(walletId)
        return if (r == SendResult.NothingToSend) wallets.retrySign(walletId) else r
    }

    override suspend fun checkSent(walletId: String) = wallets.checkSent(walletId)

    override fun discard(walletId: String) {
        prepared = null
        wallets.discardUnsent(walletId)
    }

    override fun hasUnsent(walletId: String) = wallets.hasUnsent(walletId)

    override fun forget() {
        prepared = null
    }

    private fun reviewOf(p: PreparedSend): Review {
        val v = p.vault
        val payee = v.outputs.firstOrNull { !it.change }
        return Review(
            address = payee?.address ?: p.address,
            payeeSats = v.sendingSats,
            changeSats = v.changeSats,
            feeSats = v.feeSats,
            vsize = v.vsize,
            totalOutSats = v.sendingSats + v.feeSats,
            toFeeSats = p.toFeeSats,
            sendAll = p.sendAll,
            leftBehind = p.leftBehind,
            hasCap = p.cap != null,
            capRefusal = p.cap?.let(::refusalOf),
        )
    }

    private fun refusalOf(cap: SpendCap): CapText? = if (cap.ok) {
        null
    } else {
        val reason = when (cap.reason) {
            "per_tx" -> CapReason.PER_TX
            "per_period" -> CapReason.PER_PERIOD
            else -> CapReason.OTHER
        }
        CapText(
            reason, cap.limitSats, if (reason == CapReason.PER_PERIOD) cap.remainingSats else null,
            if (reason == CapReason.PER_PERIOD) cap.allowedAfter else null, other = cap.reason.takeIf { reason == CapReason.OTHER },
        )
    }
}
