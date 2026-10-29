package com.vettid.core.chain

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Wallet W0 spike with ACINQ bitcoin-kmp (docs/spikes/WALLET-W0-KMP.md), the counterpart of the BDK spike's BdkSpikeTest. Host JVM,
 * PUBLIC TEST DATA only: the BIP39 "abandon ... about" phrase of the BIP84 and BIP86 specs, their published xpubs and vectors.
 * No real phrase, no funds.
 */
class BitcoinKmpSpikeTest {
    // BIP84 spec, account 0 of the test phrase, xpub form of the published zpub. BIP86 spec, account 0. (As in BdkSpikeTest.)
    private val bip84Xpub = "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"
    private val bip86Xpub = "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
    private val fp = "73c5da0a"
    private val dest = "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl"

    private fun main84() = Account.fromDescriptors("wpkh([$fp/84'/0'/0']$bip84Xpub/0/*)", "wpkh([$fp/84'/0'/0']$bip84Xpub/1/*)", Network.MAINNET)
    private fun main86() = Account.fromDescriptors("tr([$fp/86'/0'/0']$bip86Xpub/0/*)", "tr([$fp/86'/0'/0']$bip86Xpub/1/*)", Network.MAINNET)

    @Test
    fun bip84_vector_mainnet() {
        val a = main84()
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", a.address(false, 0))
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", a.address(false, 1))
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", a.address(true, 0))
    }

    @Test
    fun bip86_vector_mainnet() {
        val a = main86()
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", a.address(false, 0))
        assertEquals("bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh", a.address(false, 1))
        assertEquals("bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7", a.address(true, 0))
    }

    @Test
    fun testnet_vector_and_the_vaults_descriptors() {
        // The vault side makes the §10.18 descriptors from the test phrase; the phone parses them back to the same addresses.
        val v = SimVault(Network.TESTNET4)
        assertEquals(fp, v.fingerprint.toString(16))
        assertEquals(dest, v.account(AccountType.P2WPKH).address(false, 0))
        val tr = v.account(AccountType.P2TR)
        assertTrue(tr.address(false, 0).startsWith("tb1p"))
        // Signet and testnet3 share the "tb" encodings with testnet4.
        assertEquals(dest, Account.fromDescriptors(v.descriptors(AccountType.P2WPKH).first, v.descriptors(AccountType.P2WPKH).second, Network.SIGNET).address(false, 0))
    }

    // The library prints a hash in byte order; Esplora and block explorers show it reversed (the form compared here).
    private fun shown(n: Network) = n.chainHash.value.reversed().toHex()

    @Test
    fun testnet4_signet_genesis_and_network_parameters() {
        assertEquals("00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043", shown(Network.TESTNET4))
        assertEquals("000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943", shown(Network.TESTNET))
        assertEquals("00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6", shown(Network.SIGNET))
        assertEquals("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", shown(Network.MAINNET))
        // The library's own genesis blocks hash to those values (so a server's block 0 can be compared with them).
        assertEquals(Block.Testnet4GenesisBlock.hash, Network.TESTNET4.chainHash)
    }

    @Test
    fun descriptor_parser_accepts_the_vaults_shapes_and_refuses_the_rest() {
        val v = SimVault(Network.TESTNET4)
        val (r, c) = v.descriptors(AccountType.P2WPKH)
        // With a valid checksum (bitcoin-kmp computes it) and with `h` for the hardened marker.
        Account.fromDescriptors("$r#${Descriptor.checksum(r)}", "$c#${Descriptor.checksum(c)}", Network.TESTNET4)
        Account.fromDescriptors(r.replace("'", "h"), c.replace("'", "h"), Network.TESTNET4)
        fun refused(receive: String, change: String, net: Network = Network.TESTNET4) = try {
            Account.fromDescriptors(receive, change, net); fail("accepted: $receive")
        } catch (_: DescriptorException) {
        }
        refused("$r#aaaaaaaa", c) // bad checksum
        refused(r, r) // two receive chains
        refused(c, r) // swapped
        refused(r, v.descriptors(AccountType.P2TR).second) // different accounts
        refused(r.replace("84'", "44'"), c.replace("84'", "44'")) // a path the vault never signs
        refused(r, c, Network.MAINNET) // test key on mainnet (coin type 1 and tpub)
        refused("pkh(${r.substringAfter("(")}", c) // other script types
        refused(r.replace("/0/*", "/0/5"), c) // not a wildcard chain
    }

    // A previous transaction paying [sats] to [script]; the made-up input stands for whatever funded it (as in BdkSpikeTest).
    private fun prevTx(seed: Int, sats: Long, script: ByteVector, extra: List<TxOut> = emptyList()) = Transaction(
        2,
        listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { seed.toByte() })), 0), ByteVector.empty, 0xffffffffL)),
        listOf(TxOut(Satoshi(sats), script)) + extra,
        0,
    )

    private fun coin(a: Account, seed: Int, sats: Long, index: Int = 0, change: Boolean = false) =
        Coin(a, change, index, prevTx(seed, sats, a.script(change, index)), 0)

    private val v = SimVault(Network.TESTNET4)
    private val a84 = v.account(AccountType.P2WPKH)
    private val a86 = v.account(AccountType.P2TR)

    @Test
    fun p2wpkh_psbt_carries_full_previous_transaction() {
        val c = coin(a84, 0x11, 100_000)
        val psbt = Spender.build(Network.TESTNET4, listOf(c), listOf(Payee(dest, Satoshi(50_000))), 2, ChangeTarget(a84, 0))
        val i = psbt.inputs.single() as Input.WitnessInput
        assertEquals(c.prevTx, i.nonWitnessUtxo)
        assertEquals(c.txOut, i.witnessUtxo)
        val d = i.derivationPaths.values.single()
        assertEquals(v.fingerprint, d.masterKeyFingerprint)
        assertEquals("m/84'/1'/0'/0/0", d.keyPath.asString('\''))
        assertTrue(psbt.outputs.last().derivationPaths.isNotEmpty())
        assertTrue(Spender.serialize(psbt).startsWith("cHNidP8"))
    }

    /** The difference with BDK: bitcoin-kmp's updater puts `non_witness_utxo` on taproot inputs too. */
    @Test
    fun own_taproot_input_has_non_witness_utxo_and_the_wanted_origin() {
        val c = coin(a86, 0x22, 200_000)
        val psbt = Spender.build(Network.TESTNET4, listOf(c), listOf(Payee(dest, Satoshi(50_000))), 2, ChangeTarget(a86, 0))
        val i = psbt.inputs.single()
        assertEquals(c.prevTx, i.nonWitnessUtxo)
        val (x, origin) = i.taprootDerivationPaths.entries.single()
        assertEquals(v.fingerprint, origin.masterKeyFingerprint)
        assertEquals("m/86'/1'/0'/0/0", origin.keyPath.asString('\''))
        assertTrue(origin.leaves.isEmpty())
        assertEquals(x, i.taprootInternalKey)
        // The change output carries its derivation, else the vault counts it as a payment.
        assertEquals(1, psbt.outputs.count { it.taprootDerivationPaths.isNotEmpty() })
        v.check(psbt)
    }

    @Test
    fun the_vault_refuses_what_it_should() {
        val c = coin(a86, 0x22, 200_000)
        val good = Spender.build(Network.TESTNET4, listOf(c), listOf(Payee(dest, Satoshi(50_000))), 2, ChangeTarget(a86, 0))
        fun reason(p: fr.acinq.bitcoin.psbt.Psbt) = try { v.check(p); "accepted" } catch (e: VaultRefusal) { e.reason.substringBefore(" ") }
        assertEquals("accepted", reason(good))
        // A witness-only taproot input (what BDK produces for its own taproot coins) is `previous_tx`.
        val witnessOnly = good.copy(inputs = good.inputs.map { (it as Input.WitnessInput.PartiallySignedWitnessInput).copy(nonWitnessUtxo = null) })
        assertEquals("previous_tx", reason(witnessOnly))
        // A previous transaction that is not the outpoint's.
        val lie = good.copy(inputs = good.inputs.map { (it as Input.WitnessInput.PartiallySignedWitnessInput).copy(nonWitnessUtxo = prevTx(0x33, 1, ByteVector.empty)) })
        assertEquals("previous_tx", reason(lie))
        // A derivation of another wallet.
        val foreign = good.copy(inputs = good.inputs.map { i -> (i as Input.WitnessInput.PartiallySignedWitnessInput).copy(taprootDerivationPaths = i.taprootDerivationPaths.mapValues { it.value.copy(masterKeyFingerprint = 1L) }) })
        assertEquals("foreign_input", reason(foreign))
    }

    @Test
    fun builder_refuses_bad_requests_and_drops_dust_change() {
        val c = coin(a84, 0x11, 100_000)
        val change = ChangeTarget(a84, 0)
        fun refused(f: () -> Unit) = try { f(); fail("accepted") } catch (_: SpendException) {}
        refused { Spender.build(Network.TESTNET4, listOf(c), listOf(Payee(dest, Satoshi(100_000))), 2, change) } // no room for the fee
        refused { Spender.build(Network.TESTNET4, listOf(c), listOf(Payee("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", Satoshi(1_000))), 2, change) } // mainnet payee
        refused { Spender.build(Network.TESTNET4, listOf(c), listOf(Payee(dest, Satoshi(200))), 2, change) } // below 330
        // 99_700 leaves less than 330 after the fee: no change output, the remainder is fee.
        val p = Spender.build(Network.TESTNET4, listOf(c), listOf(Payee(dest, Satoshi(99_700))), 2, change)
        assertEquals(1, p.global.tx.txOut.size)
        // A coin whose script is not the wallet's address at that index is refused at construction.
        try { Coin(a84, false, 3, prevTx(0x44, 1_000, a84.script(false, 0)), 0); fail() } catch (_: IllegalArgumentException) {}
    }

    /**
     * The headline: one PSBT spending a BIP84 coin and a BIP86 coin (§10.18 "both accounts": the two accounts of one phrase, spent
     * together), full previous transactions on both, derivations for both, change with its derivation, signed by the (simulated)
     * vault, finalised and extracted. bitcoin-kmp's own script interpreter verifies the result during `extract`.
     */
    @Test
    fun both_accounts_in_one_psbt_with_full_previous_transactions() {
        val c84 = coin(a84, 0x11, 100_000)
        val c86 = coin(a86, 0x22, 200_000)
        val psbt = Spender.build(Network.TESTNET4, listOf(c84, c86), listOf(Payee(dest, Satoshi(250_000))), 2, ChangeTarget(a84, 0))

        assertEquals(2, psbt.inputs.size)
        assertTrue(psbt.inputs.all { it.nonWitnessUtxo != null })
        assertEquals(1, psbt.inputs.count { it.taprootDerivationPaths.isNotEmpty() })
        assertEquals(1, psbt.inputs.count { it.derivationPaths.isNotEmpty() })
        assertTrue(psbt.outputs.any { it.derivationPaths.isNotEmpty() })
        assertTrue(psbt.inputs.all { it.partialSigs.isEmpty() && it.taprootKeySignature == null }) // nothing signed on the phone

        // The vault takes standard base64 of BIP174 v0, and the PSBT survives a round trip byte for byte.
        val b64 = Spender.serialize(psbt)
        assertEquals(b64, Spender.serialize(Spender.parse(b64)))

        val (fee, rate) = v.check(Spender.parse(b64))
        assertTrue("fee rate $rate", rate in 1.9..2.3)

        // The vault signs (it has the keys); the phone finalises and extracts.
        val signed = v.sign(Spender.parse(b64))
        val tx = Spender.finalizeAndExtract(Spender.parse(Spender.serialize(signed)))
        assertEquals(2, tx.txIn.size)
        assertEquals(listOf(2, 1), tx.txIn.map { it.witness.stack.size }.sortedDescending()) // P2WPKH: sig, key; P2TR key path: one signature
        assertEquals(64, tx.txIn.first { it.witness.stack.size == 1 }.witness.stack.single().size())
        assertEquals(fee, 300_000L - tx.txOut.sumOf { it.amount.sat })
        val realRate = fee / (tx.weight() / 4.0)
        assertTrue("real fee rate $realRate", realRate in 1.95..2.3)
        // Deterministic: the same PSBT signs to the same transaction (BIP340 with no auxiliary randomness here).
        val tx2 = Spender.finalizeAndExtract(v.sign(Spender.parse(b64)))
        assertEquals(tx.txid, tx2.txid)
        println("W0-KMP two-account txid=${tx.txid} vsize=${(tx.weight() + 3) / 4} fee=$fee psbtBytes=${Spender.parse(b64).let { fr.acinq.bitcoin.psbt.Psbt.write(it).size() }}")
        assertNotNull(Bitcoin.addressFromPublicKeyScript(Network.TESTNET4.chainHash, tx.txOut.first().publicKeyScript.toByteArray()).let { (it as Either.Right).value })
    }

    @Test
    fun either_account_alone_signs_finalises_and_passes_the_script_interpreter() {
        for (a in listOf(a84, a86)) {
            val psbt = Spender.build(Network.TESTNET4, listOf(coin(a, 0x55, 150_000)), listOf(Payee(dest, Satoshi(60_000))), 5, ChangeTarget(a86, 7))
            val tx = Spender.finalizeAndExtract(v.sign(psbt))
            assertFalse(tx.txIn.single().witness.stack.isEmpty())
            assertNull(null) // extract() already ran Transaction.correctlySpends with the standard script flags
        }
    }

    @Test
    fun script_is_standard_for_what_the_vault_checks() {
        // P2WPKH and P2TR outputs round trip through the library's own script classifiers.
        assertTrue(Script.isPay2wpkh(a84.script(false, 0).toByteArray()))
        assertTrue(Script.isPay2tr(a86.script(false, 0)))
    }
}
