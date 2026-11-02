package com.vettid.feature.wallet

import com.vettid.core.chain.InspectSummary
import com.vettid.core.wallet.CapReason
import com.vettid.core.wallet.DiscardResult
import com.vettid.core.wallet.FeeEstimates
import com.vettid.core.wallet.PrepareResult
import com.vettid.core.wallet.PreparedSend
import com.vettid.core.wallet.RecentReport
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
@Suppress("TooManyFunctions") // one function per step of a send
interface SendEngine {
    suspend fun feeEstimates(walletId: String): FeeEstimates

    suspend fun prepare(walletId: String, request: SendRequest): SendPrepared

    /** Approve the payment prepared last, with the password (typed once). */
    suspend fun approve(password: String): SendResult

    /** Try again: the signed transaction is broadcast again, else the unanswered signing is sent again (the same request). */
    suspend fun retry(walletId: String): SendResult

    /** Asks the chain server whether it has the kept transaction (review note 2). */
    suspend fun checkSent(walletId: String): SendResult

    /** The vault's recent signed payments against the chain server, before a new payment is prepared. */
    suspend fun recentPayments(walletId: String): RecentReport

    /** "Send anyway": the core refuses a new payment until what the check found is acknowledged. */
    fun acknowledgeRecent(walletId: String)

    /** Drops the unsent payment; [DiscardResult.MUST_CHECK] while it may be on the network and no server has said otherwise. */
    fun discard(walletId: String): DiscardResult

    /** A kept transaction may be on the network: [checkSent] first, and no Discard until it says the server does not have it. */
    fun needsCheck(walletId: String): Boolean

    fun hasUnsent(walletId: String): Boolean

    /** Forgets the payment prepared (not the signed one: that is [discard]). */
    fun forget()
}

/** [SendEngine] over the wallet manager. */
@Suppress("TooManyFunctions") // one function per step of a send
class ManagerSendEngine(private val wallets: WalletsManager) : SendEngine {
    private var prepared: PreparedSend? = null

    override suspend fun feeEstimates(walletId: String) = wallets.feeEstimates(walletId)

    override suspend fun prepare(walletId: String, request: SendRequest): SendPrepared {
        prepared = null
        return when (val r = wallets.prepareSend(walletId, request)) {
            is PrepareResult.Ready -> {
                val review = reviewOf(r.prepared)
                if (review == null) {
                    SendPrepared.Stopped(PrepareResult.Different(emptySet()))
                } else {
                    prepared = r.prepared
                    SendPrepared.Ready(review)
                }
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

    override suspend fun recentPayments(walletId: String) = wallets.recentSends(walletId)

    override fun acknowledgeRecent(walletId: String) = wallets.acknowledgeRecent(walletId)

    override fun discard(walletId: String): DiscardResult {
        prepared = null
        return wallets.discardUnsent(walletId)
    }

    override fun needsCheck(walletId: String) = wallets.mustCheck(walletId)

    override fun hasUnsent(walletId: String) = wallets.hasUnsent(walletId)

    override fun forget() {
        prepared = null
    }

    private fun reviewOf(p: PreparedSend): Review? = reviewFrom(p.vault, p.cap, p.toFeeSats, p.sendAll, p.leftBehind)

    companion object {
        /**
         * The review from **the vault's summary** only: its payee (as it wrote the address), amounts, fee and `vsize`, never the app's
         * own selection or what was typed. Null when the summary has no single payee: nothing is shown then (the compare makes it
         * unreachable, and there is no fallback to what was entered).
         */
        fun reviewFrom(vault: InspectSummary, cap: SpendCap?, toFeeSats: Long, sendAll: Boolean, leftBehind: Int): Review? {
            val payee = vault.outputs.singleOrNull { !it.change } ?: return null
            return Review(
                address = payee.address,
                payeeSats = vault.sendingSats,
                changeSats = vault.changeSats,
                feeSats = vault.feeSats,
                vsize = vault.vsize,
                totalOutSats = vault.sendingSats + vault.feeSats,
                toFeeSats = toFeeSats,
                sendAll = sendAll,
                leftBehind = leftBehind,
                hasCap = cap != null,
                capRefusal = cap?.let(::refusalOf),
            )
        }

        /** The inspect's `cap` when it says `ok: false`, as the text needs it. */
        fun refusalOf(cap: SpendCap): CapText? = if (cap.ok) {
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
}
