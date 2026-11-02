// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.wallet.FeeEstimates
import com.vettid.core.wallet.SendRequest
import com.vettid.core.wallet.SendResult

/** A foreign testnet address (BIP173's vector) and a mainnet one. */
internal const val PAYEE = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
internal const val MAINNET_PAYEE = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

internal fun sampleReview(
    capRefusal: CapText? = null,
    hasCap: Boolean = true,
    toFeeSats: Long = 0,
    change: Long = 58_800,
    sendAll: Boolean = false,
) = Review(
    address = PAYEE, payeeSats = 40_000, changeSats = change, feeSats = 1_200, vsize = 200, totalOutSats = 41_200, toFeeSats = toFeeSats,
    sendAll = sendAll, leftBehind = 0, hasCap = hasCap, capRefusal = capRefusal,
)

/** The sending core as the screens see it: canned answers, and what was asked. */
internal class FakeEngine : SendEngine {
    var estimates: FeeEstimates = FeeEstimates.Done(mapOf(2 to 12.0, 6 to 5.0, 144 to 1.0))
    var prepared: SendPrepared = SendPrepared.Ready(sampleReview())
    val requests = mutableListOf<SendRequest>()
    val passwords = mutableListOf<String>()
    var approveResult: SendResult = SendResult.Sent("ab".repeat(32))
    var approveFailure: VaultFailure? = null
    var retryResult: SendResult = SendResult.Sent("ab".repeat(32))
    var checkResult: SendResult = SendResult.NotOnServer
    var unsent = false
    var prepareFailure: VaultFailure? = null
    var discards = 0
    var forgets = 0
    var retries = 0
    var checks = 0

    override suspend fun feeEstimates(walletId: String) = estimates

    override suspend fun prepare(walletId: String, request: SendRequest): SendPrepared {
        requests += request
        prepareFailure?.let { throw it }
        return prepared
    }

    override suspend fun approve(password: String): SendResult {
        passwords += password
        approveFailure?.let { throw it }
        return approveResult
    }

    override suspend fun retry(walletId: String): SendResult {
        retries++
        return retryResult
    }

    override suspend fun checkSent(walletId: String): SendResult {
        checks++
        return checkResult
    }

    override fun discard(walletId: String) {
        discards++
        unsent = false
    }

    override fun hasUnsent(walletId: String) = unsent

    override fun forget() {
        forgets++
    }
}
