package com.vettid.core.chain

import kotlin.math.ceil

/** A coin of the wallet that can be spent, as the chain server reports it and the wallet's derivations place it. */
class Spendable(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val accountType: AccountType,
    val change: Boolean,
    val index: Int,
    val confirmed: Boolean,
) {
    override fun toString(): String = "Spendable"
}

/** How [CoinSelection.select] found its coins. */
enum class SelectionMethod { BRANCH_AND_BOUND, LARGEST_FIRST, SEND_ALL }

/** The outcome of [CoinSelection.select]; every figure is in satoshis. */
sealed interface Selection {
    /**
     * [coins] to spend, [payeeSats] to the payee, [changeSats] back to the wallet (0: no change output), [feeSats] the fee
     * (inputs minus outputs). [toFeeSats] is what was **added to the fee** beyond what [vsize] and the rate need, because the
     * remainder was too small for an output (below [CoinSelection.DUST]) or a better match than a change output: the review says so.
     * [leftBehind] counts coins a Send all did not spend (more than the vault signs in one transaction, or worth less than they cost).
     */
    class Selected(
        val coins: List<Spendable>,
        val payeeSats: Long,
        val changeSats: Long,
        val feeSats: Long,
        val vsize: Long,
        val toFeeSats: Long,
        val sendAll: Boolean,
        val method: SelectionMethod,
        val leftBehind: Int = 0,
    ) : Selection {
        override fun toString(): String = "Selected"
    }

    /** The coins offered do not cover the amount and the fee: [availableSats] against [neededSats]. */
    data class NotEnough(val availableSats: Long, val neededSats: Long) : Selection

    /** The fee rate is below 1 sat/vB, above 1,000 sat/vB (the vault's cap), or not a number. */
    data object BadFeeRate : Selection

    /** The amount is below [CoinSelection.DUST] (the vault refuses an output that small) or above 21 million BTC. */
    data object BadAmount : Selection

    /** The amount needs more than [CoinSelection.MAX_INPUTS] coins, the most the vault signs in one transaction. */
    data object TooManyInputs : Selection
}

/**
 * Coin selection (ANDROID-PLAN §4 "Sending", step 3), pure: over the coins given (both accounts, the caller offers confirmed ones
 * only unless the member asked for unconfirmed), **branch and bound** first (a set that pays the amount and the fee with no change,
 * the excess going to the fee when it is less than a change output would cost), then **largest first**; change when it is at least
 * [DUST] sats, otherwise the remainder goes to the fee. The fee is `ceil(rate * vsize)` for the signed transaction's virtual size as
 * the vault computes it ([PsbtInspector]), so that a fee rate means what the vault will see. A rate below [MIN_FEE_RATE] or above
 * [MAX_FEE_RATE] sat/vB is refused here.
 */
object CoinSelection {
    const val MIN_FEE_RATE = 1.0
    const val MAX_FEE_RATE = PsbtInspector.MAX_FEE_RATE.toDouble()

    /** The smallest output the vault signs (`output_amount`); smaller is never made. */
    const val DUST = PsbtInspector.MIN_OUTPUT
    const val MAX_INPUTS = PsbtInspector.MAX_INPUTS

    private const val BNB_TRIES = 100_000
    private const val INPUT_STRIPPED = 41 // outpoint 36, empty script 1, sequence 4
    private const val EPS = 1e-9

    /** Weight units of the witness of a spend of this account's coin: P2WPKH 108, taproot key path (default sighash) 66. */
    private fun witnessWeight(t: AccountType) = if (t == AccountType.P2WPKH) 108 else 66

    /** A coin's input in virtual bytes (68 for P2WPKH, 57.5 for taproot). */
    private fun inputVbytes(t: AccountType) = (INPUT_STRIPPED * 4 + witnessWeight(t)) / 4.0

    private fun scriptSizeOf(t: AccountType) = if (t == AccountType.P2WPKH) 22 else 34

    private fun varint(n: Int) = if (n < 0xfd) 1 else 3

    private fun outputStripped(scriptSize: Int) = 8 + varint(scriptSize) + scriptSize

    /** The signed transaction's virtual size: exactly [PsbtInspector]'s figure for the same inputs and outputs. */
    fun vsize(inputs: List<AccountType>, payeeScriptSize: Int, changeType: AccountType?): Long {
        val nOut = if (changeType == null) 1 else 2
        // version 4, the input count, the inputs, the output count, the outputs, locktime 4
        var stripped = 4 + varint(inputs.size) + INPUT_STRIPPED * inputs.size + varint(nOut) + 4
        stripped += outputStripped(payeeScriptSize)
        if (changeType != null) stripped += outputStripped(scriptSizeOf(changeType))
        var weight = stripped.toLong() * 4 + 2
        for (t in inputs) weight += witnessWeight(t)
        return (weight + 3) / 4
    }

    private fun feeFor(rate: Double, vsize: Long): Long = ceil(rate * vsize - EPS).toLong()

    @Suppress("LongParameterList", "ReturnCount", "CyclomaticComplexMethod")
    fun select(
        coins: List<Spendable>,
        payeeScriptSize: Int,
        amountSats: Long?,
        feeRate: Double,
        changeType: AccountType,
        includeUnconfirmed: Boolean = false,
    ): Selection {
        if (!(feeRate >= MIN_FEE_RATE && feeRate <= MAX_FEE_RATE)) return Selection.BadFeeRate
        if (amountSats != null && (amountSats < DUST || amountSats > PsbtInspector.MAX_SATOSHI)) return Selection.BadAmount
        val eligible = coins.filter { it.confirmed || includeUnconfirmed }
            .sortedWith(compareByDescending<Spendable> { it.valueSats }.thenBy { it.txid }.thenBy { it.vout })
        val ctx = Ctx(payeeScriptSize, feeRate, changeType)
        return if (amountSats == null) sendAll(eligible, ctx) else spend(eligible, amountSats, ctx)
    }

    private class Ctx(val payeeScriptSize: Int, val rate: Double, val changeType: AccountType) {
        fun fee(inputs: List<Spendable>, withChange: Boolean): Long =
            feeFor(rate, vsize(inputs.map { it.accountType }, payeeScriptSize, if (withChange) changeType else null))

        fun effective(c: Spendable): Double = c.valueSats - rate * inputVbytes(c.accountType)
    }

    private fun sendAll(eligible: List<Spendable>, ctx: Ctx): Selection {
        val worth = eligible.filter { ctx.effective(it) > 0 }
        val taken = worth.take(MAX_INPUTS)
        val total = taken.sumOf { it.valueSats }
        if (taken.isEmpty()) return Selection.NotEnough(eligible.sumOf { it.valueSats }, ctx.fee(eligible.take(1), false) + DUST)
        val fee = ctx.fee(taken, false)
        val amount = total - fee
        if (amount < DUST) return Selection.NotEnough(total, fee + DUST)
        return Selection.Selected(
            taken, amount, 0, fee, vsize(taken.map { it.accountType }, ctx.payeeScriptSize, null), 0, true, SelectionMethod.SEND_ALL,
            leftBehind = eligible.size - taken.size,
        )
    }

    private fun spend(eligible: List<Spendable>, amount: Long, ctx: Ctx): Selection {
        bnb(eligible, amount, ctx)?.let { return it }
        return largestFirst(eligible, amount, ctx)
    }

    private fun done(coins: List<Spendable>, amount: Long, change: Long, ctx: Ctx, method: SelectionMethod): Selection.Selected {
        val total = coins.sumOf { it.valueSats }
        val fee = total - amount - change
        val noChangeFee = ctx.fee(coins, false)
        val vsize = vsize(coins.map { it.accountType }, ctx.payeeScriptSize, if (change > 0) ctx.changeType else null)
        val required = if (change > 0) ctx.fee(coins, true) else noChangeFee
        return Selection.Selected(coins, amount, change, fee, vsize, fee - required, false, method)
    }

    private fun largestFirst(eligible: List<Spendable>, amount: Long, ctx: Ctx): Selection {
        val picked = ArrayList<Spendable>()
        var total = 0L
        for (c in eligible) {
            if (picked.size == MAX_INPUTS) return Selection.TooManyInputs
            picked += c
            total += c.valueSats
            val feeNoChange = ctx.fee(picked, false)
            if (total < amount + feeNoChange) continue
            val change = total - amount - ctx.fee(picked, true)
            // A remainder under [DUST] is no output: it goes to the fee.
            return done(picked.toList(), amount, if (change >= DUST) change else 0, ctx, SelectionMethod.LARGEST_FIRST)
        }
        return Selection.NotEnough(total, amount + ctx.fee(picked, false))
    }

    /**
     * Murch's branch and bound over effective values: a subset within [target, target + the cost of a change output and its later
     * spend).
     */
    private fun bnb(eligible: List<Spendable>, amount: Long, ctx: Ctx): Selection? {
        val cands = eligible.filter { ctx.effective(it) > 0 }
        if (cands.isEmpty()) return null
        val base = ctx.rate * vsize(emptyList(), ctx.payeeScriptSize, null)
        val window = ctx.rate * (outputStripped(scriptSizeOf(ctx.changeType)) + inputVbytes(ctx.changeType))
        val set = BnbSearch(cands, cands.map { ctx.effective(it) }, amount + base, window).run() ?: return null
        // The doubles guided the search; the exact figures decide.
        val total = set.sumOf { it.valueSats }
        if (set.size > MAX_INPUTS || total - amount < ctx.fee(set, false)) return null
        return done(set, amount, 0, ctx, SelectionMethod.BRANCH_AND_BOUND)
    }

    /** The depth-first search: include a coin first, then leave it out; pruned above the window and where what is left cannot reach. */
    private class BnbSearch(
        private val cands: List<Spendable>,
        private val eff: List<Double>,
        private val target: Double,
        private val window: Double,
    ) {
        private val suffix = DoubleArray(cands.size + 1).also { s -> for (i in cands.indices.reversed()) s[i] = s[i + 1] + eff[i] }
        private val chosen = BooleanArray(cands.size)
        private var tries = 0

        fun run(): List<Spendable>? = if (search(0, 0.0, 0)) cands.filterIndexed { k, _ -> chosen[k] } else null

        private fun search(i: Int, sum: Double, count: Int): Boolean {
            if (++tries > BNB_TRIES || sum > target + window || count > MAX_INPUTS) return false
            if (sum >= target) return true
            if (i == cands.size || sum + suffix[i] < target) return false
            chosen[i] = true
            if (search(i + 1, sum + eff[i], count + 1)) return true
            chosen[i] = false
            return search(i + 1, sum, count)
        }
    }
}
