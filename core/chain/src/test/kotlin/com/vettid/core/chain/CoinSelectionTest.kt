package com.vettid.core.chain

import fr.acinq.bitcoin.psbt.Psbt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** W2-1 coin selection (ANDROID-PLAN §4 "Sending", step 3): pure, and checked against the builder and the vault's policy. */
class CoinSelectionTest {
    private val wallet = Vectors.wallets.getValue("regtest")
    private val wp = wallet.account(AccountType.P2WPKH)
    private val tr = wallet.account(AccountType.P2TR)
    private val payee = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"

    /** A P2WPKH payee's output script is 22 bytes. */
    private val payeeSize = 22

    private fun coin(type: AccountType, index: Int, sats: Long, confirmed: Boolean = true, change: Boolean = false) =
        Spendable("%064x".format(index * 10 + type.ordinal + (if (change) 5 else 0) + sats), 0, sats, type, change, index, confirmed)

    private fun p(index: Int, sats: Long, confirmed: Boolean = true) = coin(AccountType.P2WPKH, index, sats, confirmed)

    private fun select(coins: List<Spendable>, amount: Long?, rate: Double = 1.0, unconfirmed: Boolean = false) =
        CoinSelection.select(coins, payeeSize, amount, rate, AccountType.P2TR, unconfirmed)

    private fun selected(s: Selection) = s as Selection.Selected

    @Test
    fun anExactMatchNeedsNoChangeAndNoExtraFee() {
        // 1 P2WPKH input, 1 output: 110 vB, 110 sats at 1 sat/vB.
        val s = selected(select(listOf(p(0, 50_110), p(1, 30_000), p(2, 20_000)), 50_000))
        assertEquals(SelectionMethod.BRANCH_AND_BOUND, s.method)
        assertEquals(listOf(50_110L), s.coins.map { it.valueSats })
        assertEquals(0L, s.changeSats)
        assertEquals(110L, s.feeSats)
        assertEquals(0L, s.toFeeSats)
        assertEquals(110L, s.vsize)
    }

    @Test
    fun branchAndBoundFindsACombinationOfSeveralCoins() {
        // 2 inputs, 1 output: stripped 4+1+82+1+31+4 = 123 -> 492 + 2 + 216 = 710 -> 178 vB.
        val s = selected(select(listOf(p(0, 40_000), p(1, 25_000), p(2, 7_000), p(3, 3_000)), 65_000 - 178))
        assertEquals(SelectionMethod.BRANCH_AND_BOUND, s.method)
        assertEquals(setOf(40_000L, 25_000L), s.coins.map { it.valueSats }.toSet())
        assertEquals(178L, s.feeSats)
        assertEquals(0L, s.changeSats)
    }

    @Test
    fun changeGoesBackWhenThereIsEnoughOfIt() {
        val s = selected(select(listOf(p(0, 100_000)), 40_000))
        assertEquals(SelectionMethod.LARGEST_FIRST, s.method)
        // 1 input, 2 outputs (payee P2WPKH, change taproot): 4+1+41+1+31+43+4 = 125 -> 500+2+108 = 610 -> 153 vB.
        assertEquals(153L, s.vsize)
        assertEquals(153L, s.feeSats)
        assertEquals(100_000L - 40_000 - 153, s.changeSats)
        assertEquals(0L, s.toFeeSats)
    }

    @Test
    fun aRemainderBelowTheMinimumOutputGoesToTheFeeAndIsReported() {
        val s = selected(select(listOf(p(0, 40_400)), 40_000))
        assertEquals(0L, s.changeSats)
        assertEquals(400L, s.feeSats)
        assertEquals(400L - 110, s.toFeeSats)
        assertEquals(110L, s.vsize)
        // Exactly the minimum is an output.
        val edge = selected(select(listOf(p(0, 40_000 + 153 + 330)), 40_000))
        assertEquals(330L, edge.changeSats)
        val under = selected(select(listOf(p(0, 40_000 + 153 + 329)), 40_000))
        assertEquals(0L, under.changeSats)
        assertEquals(40_000L + 153 + 329, under.payeeSats + under.feeSats)
    }

    @Test
    fun sendAllSpendsEveryCoinOfBothAccountsAndTakesTheFeeFromTheAmount() {
        val coins = listOf(p(0, 50_000), coin(AccountType.P2TR, 1, 30_000), p(2, 10_000))
        val s = selected(select(coins, null, rate = 2.0))
        assertTrue(s.sendAll)
        assertEquals(SelectionMethod.SEND_ALL, s.method)
        assertEquals(3, s.coins.size)
        assertEquals(0L, s.changeSats)
        assertEquals(90_000L, s.payeeSats + s.feeSats)
        assertEquals(CoinSelection.vsize(coins.map { it.accountType }, payeeSize, null) * 2, s.feeSats)
    }

    @Test
    fun sendAllLeavesCoinsThatCostMoreThanTheyAreWorth() {
        // At 100 sat/vB a P2WPKH input costs 6,800 sats.
        val s = selected(select(listOf(p(0, 50_000), p(1, 5_000)), null, rate = 100.0))
        assertEquals(1, s.coins.size)
        assertEquals(1, s.leftBehind)
        val none = select(listOf(p(0, 1_000)), null, rate = 100.0)
        assertTrue(none is Selection.NotEnough)
    }

    @Test
    fun unconfirmedCoinsAreLeftOutUnlessAsked() {
        val coins = listOf(p(0, 20_000), p(1, 500_000, confirmed = false))
        assertTrue(select(coins, 100_000) is Selection.NotEnough)
        val off = select(coins, 15_000) as Selection.Selected
        assertEquals(listOf(20_000L), off.coins.map { it.valueSats })
        val on = selected(select(coins, 100_000, unconfirmed = true))
        assertEquals(listOf(500_000L), on.coins.map { it.valueSats })
        // Send all off and on.
        assertEquals(1, selected(select(coins, null)).coins.size)
        assertEquals(2, selected(select(coins, null, unconfirmed = true)).coins.size)
    }

    @Test
    fun notEnoughFundsSaysWhatWasThereAndWhatWasNeeded() {
        val r = select(listOf(p(0, 10_000), p(1, 5_000)), 15_000) as Selection.NotEnough
        assertEquals(15_000L, r.availableSats)
        assertEquals(15_000L + 178, r.neededSats)
        assertTrue(select(emptyList(), 1_000) is Selection.NotEnough)
    }

    @Test
    fun theFeeRateIsRefusedBelowOneAndAboveAThousand() {
        val coins = listOf(p(0, 5_000_000))
        assertEquals(Selection.BadFeeRate, select(coins, 10_000, rate = 0.99))
        assertEquals(Selection.BadFeeRate, select(coins, 10_000, rate = 0.0))
        assertEquals(Selection.BadFeeRate, select(coins, 10_000, rate = -3.0))
        assertEquals(Selection.BadFeeRate, select(coins, 10_000, rate = 1000.01))
        assertEquals(Selection.BadFeeRate, select(coins, 10_000, rate = Double.NaN))
        assertEquals(Selection.BadFeeRate, select(coins, 10_000, rate = Double.POSITIVE_INFINITY))
        assertTrue(select(coins, 10_000, rate = 1.0) is Selection.Selected)
        assertTrue(select(coins, 10_000, rate = 1000.0) is Selection.Selected)
        assertEquals(Selection.BadFeeRate, select(coins, null, rate = 0.5))
    }

    @Test
    fun anAmountBelowTheMinimumOutputIsRefused() {
        assertEquals(Selection.BadAmount, select(listOf(p(0, 50_000)), 329))
        assertTrue(select(listOf(p(0, 50_000)), 330) is Selection.Selected)
        assertEquals(Selection.BadAmount, select(listOf(p(0, 50_000)), 0))
    }

    @Test
    fun moreThanSixtyFourCoinsAreTooManyInputs() {
        val coins = (0 until 70).map { p(it, 2_000) }
        assertEquals(Selection.TooManyInputs, select(coins, 130_000))
        // Send all takes the largest 64 and says how many it left.
        val all = selected(select(coins, null))
        assertEquals(64, all.coins.size)
        assertEquals(6, all.leftBehind)
    }

    /** The virtual size is the vault's: the same as [PsbtInspector]'s for the PSBT that the builder makes from the selection. */
    @Test
    fun theSelectionBuildsAPsbtTheVaultsPolicyAcceptsWithTheSameFeeAndSize() {
        val rnd = Random(7)
        repeat(40) { round ->
            val coins = (0 until 1 + rnd.nextInt(6)).map { i ->
                val type = if (rnd.nextBoolean()) AccountType.P2WPKH else AccountType.P2TR
                coin(type, i, 5_000L + rnd.nextInt(200_000), change = rnd.nextBoolean())
            }.distinctBy { it.txid }
            val total = coins.sumOf { it.valueSats }
            val rate = 1.0 + rnd.nextInt(40) / 4.0
            val amount = if (round % 5 == 0) null else 330L + rnd.nextLong().mod(total / 2)
            val s = select(coins, amount, rate) as? Selection.Selected ?: return@repeat
            assertEquals(s.coins.sumOf { it.valueSats }, s.payeeSats + s.changeSats + s.feeSats)
            assertTrue(s.changeSats == 0L || s.changeSats >= CoinSelection.DUST)
            assertTrue(s.feeSats >= Math.ceil(rate * s.vsize - 1e-6).toLong())
            val outputs = listOfNotNull(
                BuildOutput.Pay(payee, s.payeeSats),
                if (s.changeSats > 0) BuildOutput.Own(AccountType.P2TR, true, 3, s.changeSats) else null,
            )
            val raw = PsbtBuilder.build(wallet, s.coins.map { buildCoin(it) }, outputs)
            val summary = (PsbtInspector.inspect(wallet, raw) as InspectResult.Ok).summary
            assertEquals(s.vsize, summary.vsize)
            assertEquals(s.feeSats, summary.feeSats)
            assertEquals(s.changeSats, summary.changeSats)
            assertEquals(s.payeeSats, summary.sendingSats)
        }
    }

    @Test
    fun everyInputSignalsReplacementAndCarriesItsDerivation() {
        val coins = listOf(p(0, 80_000), coin(AccountType.P2TR, 1, 60_000))
        val s = selected(select(coins, 100_000))
        val raw = PsbtBuilder.build(
            wallet, s.coins.map { buildCoin(it) },
            listOf(BuildOutput.Pay(payee, s.payeeSats), BuildOutput.Own(AccountType.P2TR, true, 0, s.changeSats)),
        )
        val psbt = (Psbt.read(raw) as fr.acinq.bitcoin.utils.Either.Right).value
        assertTrue(psbt.global.tx.txIn.all { it.sequence == 0xfffffffdL })
        // Each input has its full previous transaction and a derivation with the wallet's fingerprint; the change output has its own.
        assertTrue(psbt.inputs.all { it.nonWitnessUtxo != null })
        assertTrue(psbt.inputs.all { it.derivationPaths.isNotEmpty() || it.taprootDerivationPaths.isNotEmpty() })
        assertTrue(psbt.outputs[1].derivationPaths.isNotEmpty() || psbt.outputs[1].taprootDerivationPaths.isNotEmpty())
        val summary = (PsbtInspector.inspect(wallet, raw) as InspectResult.Ok).summary
        val fp = wallet.fingerprintHex.toLong(16)
        assertTrue(psbt.inputs.all { i -> i.derivationPaths.values.all { it.masterKeyFingerprint == fp } })
        assertTrue(psbt.inputs.all { i -> i.taprootDerivationPaths.values.all { it.masterKeyFingerprint == fp } })
        assertEquals(listOf(false, true), summary.outputs.map { it.change })
    }

    // The previous transaction a chain server would give: one output to the coin's address, as PsbtBuilderTest makes it.
    private fun buildCoin(c: Spendable): BuildCoin {
        val a = if (c.accountType == AccountType.P2TR) tr else wp
        val script = a.scriptPubKey(c.change, c.index)
        val out = ByteArray(8) { ((c.valueSats shr (8 * it)) and 0xff).toByte() }
        val salt = (c.txid.takeLast(2).toInt(16)).toByte()
        val hex = "02000000" + "01" + ByteArray(32) { salt }.toHex() + "00000000" + "01" + "51" + "ffffffff" + "01" +
            out.toHex() + byteArrayOf(script.size.toByte()).toHex() + script.toHex() + "00000000"
        return BuildCoin(c.accountType, c.change, c.index, hex.hexToBytes(), 0)
    }
}
