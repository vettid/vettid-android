package com.vettid.core.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

/** Builder behaviour beyond the vault vectors, and the module boundary. */
class PsbtBuilderTest {
    private val wallet = Vectors.wallets.getValue("regtest")
    private val wp = wallet.account(AccountType.P2WPKH)
    private val tr = wallet.account(AccountType.P2TR)
    private val payee = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"

    // A previous transaction with one output to the account's address, as the vault's FundingTx makes them (a made-up input).
    private fun prevTx(a: Account, change: Boolean, index: Int, sats: Long, salt: Int): ByteArray {
        val script = a.scriptPubKey(change, index)
        val out = ByteArray(8) { ((sats shr (8 * it)) and 0xff).toByte() }
        val hex = "02000000" + "01" + ByteArray(32) { salt.toByte() }.toHex() + "00000000" + "01" + "51" + "ffffffff" + "01" +
            out.toHex() + byteArrayOf(script.size.toByte()).toHex() + script.toHex() + "00000000"
        return hex.hexToBytes()
    }

    private fun coin(a: Account, index: Int, sats: Long, salt: Int, change: Boolean = false) = BuildCoin(a.type, change, index, prevTx(a, change, index, sats, salt), 0)

    private fun reason(f: () -> Unit): String = try { f(); "none" } catch (e: BuildException) { e.reason }

    @Test
    fun both_accounts_in_one_psbt_carry_full_previous_transactions_and_the_summary_names_them() {
        val raw = PsbtBuilder.build(
            wallet,
            listOf(coin(tr, 0, 80_000, 1), coin(wp, 2, 40_000, 2)),
            listOf(BuildOutput.Pay(payee, 100_000), BuildOutput.Own(AccountType.P2TR, true, 1, 19_000)),
        )
        assertTrue(PsbtBase64.encode(raw).startsWith("cHNidP8"))
        val s = (PsbtInspector.inspect(wallet, raw) as InspectResult.Ok).summary
        assertEquals(listOf("p2tr", "p2wpkh"), s.inputs.map { it.type })
        assertEquals(listOf("m/86'/1'/0'/0/0", "m/84'/1'/0'/0/2"), s.inputs.map { it.path })
        assertEquals(1_000L, s.feeSats)
        assertEquals(19_000L, s.changeSats)
        assertEquals(100_000L, s.sendingSats)
    }

    @Test
    fun a_coin_that_is_not_at_the_place_claimed_is_refused() {
        // The previous output pays index 0; the coin says index 5: a lying chain source cannot make a PSBT the vault reads differently.
        val lying = BuildCoin(AccountType.P2WPKH, false, 5, prevTx(wp, false, 0, 50_000, 3), 0)
        assertEquals("coin", reason { PsbtBuilder.build(wallet, listOf(lying), listOf(BuildOutput.Pay(payee, 40_000))) })
        val wrongAccount = BuildCoin(AccountType.P2TR, false, 0, prevTx(wp, false, 0, 50_000, 4), 0)
        assertEquals("coin", reason { PsbtBuilder.build(wallet, listOf(wrongAccount), listOf(BuildOutput.Pay(payee, 40_000))) })
        val beyond = BuildCoin(AccountType.P2WPKH, false, 0, prevTx(wp, false, 0, 50_000, 5), 3)
        assertEquals("coin", reason { PsbtBuilder.build(wallet, listOf(beyond), listOf(BuildOutput.Pay(payee, 40_000))) })
    }

    @Test
    fun the_same_coin_twice_a_payee_of_another_network_and_a_missing_account_are_refused() {
        val c = coin(wp, 0, 50_000, 6)
        assertEquals("duplicate_coin", reason { PsbtBuilder.build(wallet, listOf(c, c), listOf(BuildOutput.Pay(payee, 40_000))) })
        assertEquals("address", reason { PsbtBuilder.build(wallet, listOf(c), listOf(BuildOutput.Pay("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", 40_000))) })
        assertEquals("address", reason { PsbtBuilder.build(wallet, listOf(c), listOf(BuildOutput.Pay("not an address", 40_000))) })
        val only84 = Wallet(Network.REGTEST, listOf(wp))
        assertEquals(PsbtReasons.NO_ACCOUNT, reason { PsbtBuilder.build(only84, listOf(coin(tr, 0, 50_000, 7)), listOf(BuildOutput.Pay(payee, 40_000))) })
        assertEquals(PsbtReasons.SHAPE, reason { PsbtBuilder.build(wallet, emptyList(), listOf(BuildOutput.Pay(payee, 40_000))) })
    }

    @Test
    fun the_vaults_limits_are_checked_before_anything_is_sent() {
        val c = coin(wp, 0, 50_000, 8)
        assertEquals(PsbtReasons.OUTPUT_AMOUNT, reason { PsbtBuilder.build(wallet, listOf(c), listOf(BuildOutput.Pay(payee, 329))) }) // dust
        assertEquals(PsbtReasons.FEE, reason { PsbtBuilder.build(wallet, listOf(c), listOf(BuildOutput.Pay(payee, 50_000))) }) // no fee
        // A 1-in 1-out P2WPKH spend has a vsize of 110: 1,000 sat/vB is 110,000 sats, allowed; one sat more is not.
        val big = coin(wp, 1, 1_000_000, 10)
        PsbtBuilder.build(wallet, listOf(big), listOf(BuildOutput.Pay(payee, 1_000_000 - 110_000)))
        assertEquals(PsbtReasons.FEE_RATE, reason { PsbtBuilder.build(wallet, listOf(big), listOf(BuildOutput.Pay(payee, 1_000_000 - 110_001))) })
    }

    @Test
    fun the_inspector_reads_what_a_hostile_psbt_looks_like_without_throwing() {
        val raw = PsbtBuilder.build(wallet, listOf(coin(wp, 0, 50_000, 9)), listOf(BuildOutput.Pay(payee, 49_000)))
        // Every truncation and every single-byte change is a refusal or a valid answer, never an exception.
        for (n in raw.indices step 7) {
            PsbtInspector.inspect(wallet, raw.copyOfRange(0, n))
        }
        for (i in raw.indices step 3) {
            val m = raw.copyOf()
            m[i] = (m[i].toInt() xor 0x5a).toByte()
            PsbtInspector.inspect(wallet, m)
        }
        assertEquals(InspectResult.Refused(PsbtReasons.SIZE), PsbtInspector.inspect(wallet, ByteArray(0)))
        assertEquals(InspectResult.Refused(PsbtReasons.SIZE), PsbtInspector.inspect(wallet, ByteArray(PsbtInspector.MAX_PSBT + 1)))
    }

    /** "Nothing outside :core:chain sees a bitcoin-kmp type": no public member of the module's public classes mentions one. */
    @Test
    fun no_bitcoin_kmp_type_in_the_public_surface() {
        val classes = listOf(
            Account::class.java, Wallet::class.java, Network::class.java, AccountType::class.java, PsbtInspector::class.java, PsbtBuilder::class.java,
            PsbtBase64::class.java, PsbtReasons::class.java, InspectSummary::class.java, SummaryInput::class.java, SummaryOutput::class.java,
            InspectResult::class.java, InspectResult.Ok::class.java, InspectResult.Refused::class.java, BuildCoin::class.java,
            BuildOutput::class.java, BuildOutput.Pay::class.java, BuildOutput.Own::class.java, BuildException::class.java, DescriptorException::class.java,
        )
        val leaks = ArrayList<String>()
        fun check(owner: Class<*>, what: String, vararg types: Class<*>) {
            if (types.any { it.name.startsWith("fr.acinq") }) leaks += "${owner.name}.$what"
        }
        for (c in classes) {
            for (m in c.declaredMethods) {
                // Kotlin `internal` members are public in bytecode with a `$module` suffix in the name; `private` ones are not public.
                if (!Modifier.isPublic(m.modifiers) || m.name.contains('$')) continue
                check(c, m.name, m.returnType, *m.parameterTypes)
            }
            for (k in c.declaredConstructors) if (Modifier.isPublic(k.modifiers) && !k.isSynthetic) check(c, "<init>", *k.parameterTypes)
            for (f in c.declaredFields) if (Modifier.isPublic(f.modifiers)) check(c, f.name, f.type)
        }
        assertEquals(emptyList<String>(), leaks)
    }
}
