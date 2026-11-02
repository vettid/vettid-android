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

    /** In the server's mempool. */
    data object Waiting : TxStatus

    /**
     * Signed by the vault and unknown to the server, but under 24 hours old by the vault's clock (or with no time, as `recentSends`
     * counts it): it may still be on its way. Shown as "Not seen by {host} yet".
     */
    data object NotSeen : TxStatus

    /**
     * Signed by the vault, unknown to the server, and either older than 24 hours by the vault's clock or still held unsent on this
     * phone (its broadcast was refused and the server has said it does not have it).
     */
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
    /** The vault signed it: its figures are `wallet.history`'s and the server gives only the status. Otherwise they are the server's. */
    val vaultSigned: Boolean = false,
) {
    override fun toString(): String = "WalletTransaction"
}

/** The wallet's transactions, newest first, as of [at] on the server [host]. */
data class WalletTransactions(
    val at: Instant,
    val host: String,
    val items: List<WalletTransaction>,
) {
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
 * Merges the two sources by `txid` (ANDROID-PLAN §4 "Transactions"). Pure.
 *
 * **The vault's figures win for a payment it signed:** its direction (SENT, or SELF when every payee is the wallet's own, never
 * RECEIVED), amount, fee and payees come from `wallet.history`; the server gives only where the transaction stands (mempool or block,
 * height, block time). A server that lies about a payment the vault signed changes its status at most. A transaction only the server
 * lists has its direction and amounts computed from the wallet's own inputs and outputs ([own] are the addresses the walk found with
 * history); one that touches none of them is dropped.
 *
 * Status: Confirmed with its confirmations from [tip]; Waiting when in the server's mempool; for a payment the vault signed that the
 * server does not know: **Not broadcast** when it is a kept unsent payment ([unsent]) or older than 24 hours by the vault's clock
 * ([vaultNow], as `recentSends`: `at` not after the cutoff), else **Not seen** (under 24 hours). An entry with no `at` is recent, as
 * `recentSends` counts it, so it stays Not seen until the vault's history gives it a time.
 */
internal object TransactionMerge {
    val NOT_BROADCAST_AFTER: Duration = Duration.ofHours(24)

    fun merge(
        own: Set<String>,
        chain: Map<String, ChainTx>,
        history: List<WalletTx>,
        tip: Long?,
        vaultNow: Instant,
        unsent: Set<String> = emptySet(),
    ): List<WalletTransaction> {
        val mine = own.map { it.lowercase() }.toSet()
        val signed = history.associateBy { it.txid.lowercase() }
        val out = ArrayList<WalletTransaction>()
        for ((txid, tx) in chain) {
            val h = signed[txid]
            val row = if (h != null) fromVault(txid, h, mine, serverStatus(tx, tip), tx) else fromChain(txid, tx, mine, tip)
            if (row != null) out += row
        }
        val cutoff = vaultNow.minus(NOT_BROADCAST_AFTER)
        for ((txid, h) in signed) {
            if (txid in chain) continue
            val old = h.at != null && !h.at.isAfter(cutoff)
            out += fromVault(txid, h, mine, if (old || txid in unsent) TxStatus.NotBroadcast else TxStatus.NotSeen, null)
        }
        // Newest first: what is not in a block before what is, by the time known; an entry with no time is the newest.
        return out.sortedWith(
            compareByDescending<WalletTransaction> { it.blockHeight == null }
                .thenByDescending { it.blockHeight ?: 0L }
                .thenByDescending { it.blockTime ?: it.signedAt ?: Instant.MAX }
                .thenBy { it.txid },
        )
    }

    private fun serverStatus(tx: ChainTx, tip: Long?): TxStatus =
        if (tx.confirmed) TxStatus.Confirmed(confirmations(tx.blockHeight, tip)) else TxStatus.Waiting

    /** Adds [values], an overflow being an answer that is not a chain's ([ChainError.Malformed]). */
    private fun total(values: Sequence<Long>): Long = try {
        values.fold(0L) { a, b -> Math.addExact(a, b) }
    } catch (_: ArithmeticException) {
        throw ChainError.Malformed()
    }

    /** Adds the vault's figures; an overflow is an answer that does not read ([WalletWireException]). */
    private fun vaultTotal(values: Sequence<Long>): Long = try {
        values.fold(0L) { a, b -> Math.addExact(a, b) }
    } catch (_: ArithmeticException) {
        throw WalletWireException("amounts")
    }

    /** What a transaction does to the wallet, from the addresses it touches that are the wallet's own. */
    private class Totals(
        val spends: Boolean,
        val foreignIn: Boolean,
        val ownIn: Long,
        val ownOut: Long,
        val others: List<ChainTxOutput>,
        val allIn: Long?,
        val allOut: Long,
    )

    private fun totalsOf(tx: ChainTx, own: Set<String>): Totals {
        fun ours(address: String?) = address != null && address.lowercase() in own
        val allIn = if (tx.inputs.all { it.prevValueSats != null }) total(tx.inputs.asSequence().map { it.prevValueSats ?: 0L }) else null
        return Totals(
            spends = tx.inputs.any { ours(it.prevAddress) },
            foreignIn = tx.inputs.any { !ours(it.prevAddress) },
            ownIn = total(tx.inputs.asSequence().filter { ours(it.prevAddress) }.map { it.prevValueSats ?: 0L }),
            ownOut = total(tx.outputs.asSequence().filter { ours(it.address) }.map { it.valueSats }),
            others = tx.outputs.filterNot { ours(it.address) },
            allIn = allIn,
            allOut = total(tx.outputs.asSequence().map { it.valueSats }),
        )
    }

    private fun feeOf(tx: ChainTx, t: Totals, direction: TxDirection, mixed: Boolean): Long? =
        if (mixed || direction == TxDirection.RECEIVED) null else tx.feeSats ?: t.allIn?.let { it - t.allOut }?.takeIf { it >= 0 }

    private fun payeesOf(t: Totals): List<WalletPayee> =
        t.others.filter { it.valueSats > 0 && it.address != null }.map { WalletPayee(it.address.orEmpty(), it.valueSats) }

    private fun directionOf(t: Totals, net: Long, mixed: Boolean): TxDirection = when {
        !t.spends -> TxDirection.RECEIVED
        mixed -> when {
            net < 0 -> TxDirection.SENT
            net > 0 -> TxDirection.RECEIVED
            else -> TxDirection.SELF
        }
        // Outputs that carry no value (OP_RETURN) do not make a payee.
        t.others.none { it.valueSats > 0 } -> TxDirection.SELF
        else -> TxDirection.SENT
    }

    /**
     * A transaction only the server lists. Inputs of the wallet's own and of others in one transaction share the fee, so the wallet's
     * part is its net change alone: the amount is what left (or came in) and no fee is shown.
     */
    private fun fromChain(txid: String, tx: ChainTx, own: Set<String>, tip: Long?): WalletTransaction? {
        val t = totalsOf(tx, own)
        if (!t.spends && t.ownOut == 0L) return null
        val net = t.ownOut - t.ownIn
        val mixed = t.spends && t.foreignIn
        val direction = directionOf(t, net, mixed)
        val amount = when {
            mixed -> Math.abs(net)
            direction == TxDirection.SENT -> total(t.others.asSequence().map { it.valueSats })
            else -> t.ownOut
        }
        val fee = feeOf(tx, t, direction, mixed)
        val payees = if (direction == TxDirection.SENT && !mixed) payeesOf(t) else emptyList()
        return WalletTransaction(
            txid = txid, direction = direction, amountSats = amount, netSats = net, feeSats = fee, status = serverStatus(tx, tip),
            blockHeight = tx.blockHeight.takeIf { tx.confirmed },
            blockTime = tx.blockTime?.takeIf { tx.confirmed }?.let { Instant.ofEpochSecond(it) },
            signedAt = null, payees = payees.sortedByDescending { it.amountSats }, connectionId = null,
        )
    }

    /** A payment the vault signed: its own figures, with [status] (and, when the server lists it, the block's height and time). */
    private fun fromVault(txid: String, h: WalletTx, own: Set<String>, status: TxStatus, tx: ChainTx?): WalletTransaction {
        // Overflow in the vault's own figures is its answer not reading (a vault failure), not the chain server's.
        val ownPaid = vaultTotal(h.payees.asSequence().filter { it.address.lowercase() in own }.map { it.amountSats })
        val self = h.payees.isNotEmpty() && ownPaid == h.sendingSats
        val leaving = vaultTotal(sequenceOf(h.sendingSats, h.feeSats))
        return WalletTransaction(
            txid = txid,
            direction = if (self) TxDirection.SELF else TxDirection.SENT,
            amountSats = h.sendingSats,
            netSats = ownPaid - leaving,
            feeSats = h.feeSats,
            status = status,
            blockHeight = tx?.blockHeight?.takeIf { tx.confirmed },
            blockTime = tx?.blockTime?.takeIf { tx.confirmed }?.let { Instant.ofEpochSecond(it) },
            signedAt = h.at,
            payees = if (self) emptyList() else h.payees.sortedByDescending { it.amountSats },
            connectionId = h.connectionId,
            vaultSigned = true,
        )
    }

    /** The block counts as the first confirmation; a tip older than the transaction's block (the server moved on) still gives 1. */
    fun confirmations(blockHeight: Long?, tip: Long?): Long =
        if (blockHeight == null || tip == null) 1 else (tip - blockHeight + 1).coerceAtLeast(1)
}
