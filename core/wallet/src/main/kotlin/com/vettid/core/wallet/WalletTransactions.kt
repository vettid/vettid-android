package com.vettid.core.wallet

import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainTx
import com.vettid.core.chain.ChainTxOutput
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.VaultFailure
import java.time.Duration
import java.time.Instant

// The wallet's Transactions (W2-3; ANDROID-PLAN §4 "Wallet", "Transactions"). Memory only: nothing here is written anywhere, every
// type prints its type name only, and nothing is logged.

/** Which way a transaction moved the wallet's coins. */
enum class TxDirection {
    /** The wallet's coins paid others (and the fee). */
    SENT,

    /** Others paid the wallet; none of its coins were spent. */
    RECEIVED,

    /** Every output came back to the wallet's own addresses: only the fee was paid. */
    SELF,
}

/** Where a transaction stands: on the server's word, or (a payment the vault signed that the server does not show) on the vault's clock. */
sealed interface TxStatus {
    /** In a block; [confirmations] counts that block (1 for the tip's own), from the server's tip height. */
    data class Confirmed(val confirmations: Long) : TxStatus

    /** In the server's mempool, or (a judgement call, see [TransactionMerge]) signed less than 24 hours ago and not yet seen. */
    data object Waiting : TxStatus

    /** Signed by the vault, unknown to the server, and older than 24 hours by the vault's clock. */
    data object NotBroadcast : TxStatus
}

/**
 * A transaction of one wallet. [amountSats] is what the other side got ([TxDirection.SENT]), what the wallet got
 * ([TxDirection.RECEIVED]) or what moved ([TxDirection.SELF]); [netSats] is the change of the wallet's balance, the wallet's own
 * outputs less its own inputs (so a payment is negative by its amount and fee). [feeSats] is known for what the wallet spent.
 */
data class WalletTransaction(
    val txid: String,
    val direction: TxDirection,
    val amountSats: Long,
    val netSats: Long,
    val feeSats: Long?,
    val status: TxStatus,
    val blockHeight: Long?,
    val blockTime: Instant?,
    /** When the vault signed it (`wallet.history`), if it did. */
    val signedAt: Instant?,
    /** Where a sent payment went: the outputs that are not the wallet's own, the largest first. */
    val payees: List<WalletPayee>,
    /** The connection a payment request came from (`wallet.history`); null otherwise. */
    val connectionId: String?,
) {
    override fun toString(): String = "WalletTransaction"
}

/** The wallet's transactions, newest first, as of [at] on the server [host]. */
data class WalletTransactions(
    val at: Instant,
    val host: String,
    /** The chain server's block explorer site, when it has one (no source has one yet: see [ChainOpen.Ready.explorer]). */
    val explorer: String?,
    val items: List<WalletTransaction>,
) {
    /** The page of [txid] on the block explorer; null when the server has no site. */
    fun explorerUrl(txid: String): String? = explorer?.trimEnd('/')?.let { "$it/tx/$txid" }

    override fun toString(): String = "WalletTransactions"
}

/** The outcome of [WalletsManager.transactions]: the same states as the look-up, each shown as what it is. */
sealed interface TransactionsResult {
    data class Done(val transactions: WalletTransactions) : TransactionsResult {
        override fun toString(): String = "Done"
    }

    data object NeedsNetwork : TransactionsResult

    data object NetworkNotOffered : TransactionsResult

    /** No server for the network: not set, or the setting could not be read ([server]). */
    data class NoServer(val server: ChainServer) : TransactionsResult

    /** The server is on another network than it was ([found]). */
    data class ServerWrongNetwork(val found: Network?) : TransactionsResult

    /** The server did not answer, or answered what Esplora does not. */
    data class ServerFailed(val error: ChainError) : TransactionsResult {
        override fun toString(): String = "ServerFailed"
    }

    data object AddressMismatch : TransactionsResult

    /** The vault could not be asked (`wallet.history` or the wallet itself). */
    data class VaultFailed(val failure: VaultFailure) : TransactionsResult {
        override fun toString(): String = "VaultFailed"
    }

    /** The chain server's privacy note was not shown for this wallet: nothing is sent to the server. */
    data object NoteNeeded : TransactionsResult

    /** The vault was closed while this ran: nothing of it is kept. */
    data object Cleared : TransactionsResult
}

/**
 * Merges the two sources by `txid` (ANDROID-PLAN §4 "Transactions"): what the server lists for the wallet's addresses ([chain]) and
 * what the vault signed ([history]). Pure.
 *
 * The direction and the amounts come from the wallet's own inputs and outputs ([own] are the addresses the walk found with history; a
 * transaction touching the wallet touches one of them), the payees of a payment the vault signed from the vault's own record. A
 * payment in both is the server's figures with the vault's payees; one only in the vault's history is the vault's figures.
 *
 * Status: Confirmed with its confirmations from [tip]; Waiting when in the server's mempool; **Not broadcast** only when the vault
 * signed it, the server does not know it and it is older than 24 hours by the vault's clock ([vaultNow], as `recentSends` reads it).
 * **Judgement call:** a payment signed less than 24 hours ago that the server does not know yet is shown as Waiting (the app
 * broadcasts right after signing, so it is most likely still on its way), not as a third state with a text of its own; an entry
 * without a time counts as recent, as in `recentSends`.
 */
internal object TransactionMerge {
    val NOT_BROADCAST_AFTER: Duration = Duration.ofHours(24)

    fun merge(
        own: Set<String>,
        chain: Map<String, ChainTx>,
        history: List<WalletTx>,
        tip: Long?,
        vaultNow: Instant,
    ): List<WalletTransaction> {
        val mine = own.map { it.lowercase() }.toSet()
        val signed = history.associateBy { it.txid.lowercase() }
        val out = ArrayList<WalletTransaction>()
        for ((txid, tx) in chain) out += fromChain(txid, tx, mine, signed[txid], tip)
        val cutoff = vaultNow.minus(NOT_BROADCAST_AFTER)
        for ((txid, h) in signed) {
            if (txid !in chain) out += fromVault(txid, h, mine, cutoff)
        }
        // Newest first: what is not in a block (waiting) before what is, by the time known; an entry with no time is the newest.
        return out.sortedWith(
            compareByDescending<WalletTransaction> { it.blockHeight == null }
                .thenByDescending { it.blockHeight ?: 0L }
                .thenByDescending { it.blockTime ?: it.signedAt ?: Instant.MAX }
                .thenBy { it.txid },
        )
    }

    /** What a transaction does to the wallet, from the addresses it touches that are the wallet's own. */
    private class Totals(val spends: Boolean, val ownIn: Long, val ownOut: Long, val others: List<ChainTxOutput>, val allIn: Long?)

    private fun totalsOf(tx: ChainTx, own: Set<String>): Totals {
        fun ours(address: String?) = address != null && address.lowercase() in own
        val allIn = if (tx.inputs.all { it.prevValueSats != null }) tx.inputs.sumOf { it.prevValueSats ?: 0L } else null
        return Totals(
            spends = tx.inputs.any { ours(it.prevAddress) },
            ownIn = tx.inputs.filter { ours(it.prevAddress) }.sumOf { it.prevValueSats ?: 0L },
            ownOut = tx.outputs.filter { ours(it.address) }.sumOf { it.valueSats },
            others = tx.outputs.filterNot { ours(it.address) },
            allIn = allIn,
        )
    }

    private fun directionOf(t: Totals): TxDirection = when {
        !t.spends -> TxDirection.RECEIVED
        // Outputs that carry no value (OP_RETURN) do not make a payee.
        t.others.none { it.valueSats > 0 } -> TxDirection.SELF
        else -> TxDirection.SENT
    }

    /** The server's fee, else inputs less outputs when every input's value is known, else the vault's. */
    private fun feeOf(tx: ChainTx, t: Totals, signed: WalletTx?): Long? =
        tx.feeSats ?: t.allIn?.let { it - tx.outputs.sumOf { o -> o.valueSats } }?.takeIf { it >= 0 } ?: signed?.feeSats

    private fun fromChain(txid: String, tx: ChainTx, own: Set<String>, signed: WalletTx?, tip: Long?): WalletTransaction {
        val t = totalsOf(tx, own)
        val direction = directionOf(t)
        val amount = if (direction == TxDirection.SENT) t.others.sumOf { it.valueSats } else t.ownOut
        val payees = if (direction == TxDirection.SENT) {
            // The vault's payees when it signed this; else the outputs that are not the wallet's own.
            signed?.payees?.takeIf { it.isNotEmpty() }
                ?: t.others.filter { it.valueSats > 0 && it.address != null }.map { WalletPayee(it.address.orEmpty(), it.valueSats) }
        } else {
            emptyList()
        }
        return WalletTransaction(
            txid = txid,
            direction = direction,
            amountSats = amount,
            netSats = t.ownOut - t.ownIn,
            feeSats = feeOf(tx, t, signed).takeIf { direction != TxDirection.RECEIVED },
            status = if (tx.confirmed) TxStatus.Confirmed(confirmations(tx.blockHeight, tip)) else TxStatus.Waiting,
            blockHeight = tx.blockHeight.takeIf { tx.confirmed },
            blockTime = tx.blockTime?.takeIf { tx.confirmed }?.let { Instant.ofEpochSecond(it) },
            signedAt = signed?.at,
            payees = payees.sortedByDescending { it.amountSats },
            connectionId = signed?.connectionId,
        )
    }

    private fun fromVault(txid: String, h: WalletTx, own: Set<String>, cutoff: Instant): WalletTransaction {
        val ownPaid = h.payees.filter { it.address.lowercase() in own }.sumOf { it.amountSats }
        val self = h.payees.isNotEmpty() && ownPaid == h.sendingSats
        return WalletTransaction(
            txid = txid,
            direction = if (self) TxDirection.SELF else TxDirection.SENT,
            amountSats = h.sendingSats,
            netSats = ownPaid - h.sendingSats - h.feeSats,
            feeSats = h.feeSats,
            status = if (h.at != null && !h.at.isAfter(cutoff)) TxStatus.NotBroadcast else TxStatus.Waiting,
            blockHeight = null,
            blockTime = null,
            signedAt = h.at,
            payees = if (self) emptyList() else h.payees.sortedByDescending { it.amountSats },
            connectionId = h.connectionId,
        )
    }

    /** The block counts as the first confirmation; a tip older than the transaction's block (the server moved on) still gives 1. */
    fun confirmations(blockHeight: Long?, tip: Long?): Long =
        if (blockHeight == null || tip == null) 1 else (tip - blockHeight + 1).coerceAtLeast(1)
}
