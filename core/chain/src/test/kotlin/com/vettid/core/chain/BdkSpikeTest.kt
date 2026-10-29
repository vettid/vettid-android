package com.vettid.core.chain

import org.bitcoindevkit.Amount
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.DerivationPath
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.OutPoint
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.Script
import org.bitcoindevkit.SignOptions
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.UnconfirmedTx
import org.bitcoindevkit.Wallet
import org.bitcoindevkit.Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Wallet W0 spike (ANDROID-PLAN §4 "Wallet", question 19). Runs on the host JVM against bdk-jvm (same Rust core and bindings
 * as bdk-android). Everything here is PUBLIC TEST DATA: the BIP39 "abandon ... about" phrase (the BIP84 and BIP86 specs'
 * test phrase, no value on any network) and the published xpubs of BIP84 and BIP86. No real funds, no real phrase.
 */
class BdkSpikeTest {
    private companion object {
        // Blockstream's Esplora completes BDK's full scan; mempool.space's does not (docs/spikes/WALLET-W0.md, "Chain source").
        const val SIGNET_URL = "https://blockstream.info/signet/api"
    }
    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    // BIP84 spec, account 0 of the test phrase, xpub form of the published zpub (same key, version bytes swapped).
    private val bip84Xpub = "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"
    // BIP86 spec, account 0.
    private val bip86Xpub = "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
    private val fingerprint = "73c5da0a"

    private fun watch(receive: String, change: String, network: Network): Wallet {
        val kind = if (network == Network.BITCOIN) NetworkKind.MAIN else NetworkKind.TEST
        return Wallet(Descriptor(receive, kind), Descriptor(change, kind), network, Persister.newInMemory())
    }

    @Test
    fun bip84_vector_mainnet() {
        val w = watch("wpkh([$fingerprint/84'/0'/0']$bip84Xpub/0/*)", "wpkh([$fingerprint/84'/0'/0']$bip84Xpub/1/*)", Network.BITCOIN)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", w.revealNextAddress(KeychainKind.EXTERNAL).address.toString())
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", w.revealNextAddress(KeychainKind.EXTERNAL).address.toString())
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", w.revealNextAddress(KeychainKind.INTERNAL).address.toString())
    }

    @Test
    fun bip86_vector_mainnet() {
        val w = watch("tr([$fingerprint/86'/0'/0']$bip86Xpub/0/*)", "tr([$fingerprint/86'/0'/0']$bip86Xpub/1/*)", Network.BITCOIN)
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", w.revealNextAddress(KeychainKind.EXTERNAL).address.toString())
        assertEquals("bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh", w.revealNextAddress(KeychainKind.EXTERNAL).address.toString())
        assertEquals("bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7", w.revealNextAddress(KeychainKind.INTERNAL).address.toString())
    }

    /** The vault's side, simulated: the test phrase's accounts on testnet (coin type 1), as the vault's `wallet.get` would give them. */
    private class VaultSim(network: Network) {
        val kind = NetworkKind.TEST
        val secret = DescriptorSecretKey(kind, Mnemonic.fromString("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"), null)
        val network = network
        fun pub(path: String): String = secret.derive(DerivationPath(path)).asPublic().toString()
        val p2wpkhKey = pub("m/84'/1'/0'")
        val p2trKey = pub("m/86'/1'/0'")
        // What `wallet.get` gives: "<kind>([fp/path]xpub/0/*)".
        val p2wpkhRecv = "wpkh(${p2wpkhKey.trimEnd('*', '/')}/0/*)"
        val p2wpkhChg = "wpkh(${p2wpkhKey.trimEnd('*', '/')}/1/*)"
        val p2trRecv = "tr(${p2trKey.trimEnd('*', '/')}/0/*)"
        val p2trChg = "tr(${p2trKey.trimEnd('*', '/')}/1/*)"
        fun signer84() = Wallet(Descriptor.newBip84(secret, KeychainKind.EXTERNAL, kind), Descriptor.newBip84(secret, KeychainKind.INTERNAL, kind), network, Persister.newInMemory())
        fun signer86() = Wallet(Descriptor.newBip86(secret, KeychainKind.EXTERNAL, kind), Descriptor.newBip86(secret, KeychainKind.INTERNAL, kind), network, Persister.newInMemory())
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xff).toByte() }

    /** A non-segwit-serialised transaction (version 2, one input spending a made-up outpoint, empty scriptSig) paying [outs]. */
    private fun rawTx(seed: Int, outs: List<Pair<Long, ByteArray>>): Transaction {
        var b = le(2, 4) + byteArrayOf(1) + ByteArray(32) { seed.toByte() } + le(0, 4) + byteArrayOf(0) + le(0xffffffffL, 4) + byteArrayOf(outs.size.toByte())
        for ((value, script) in outs) b += le(value, 8) + byteArrayOf(script.size.toByte()) + script
        return Transaction(b + le(0, 4))
    }

    private fun fund(w: Wallet, seed: Int, sats: Long) {
        val spk = w.revealNextAddress(KeychainKind.EXTERNAL).address.scriptPubkey().toBytes()
        w.applyUnconfirmedTxs(listOf(UnconfirmedTx(rawTx(seed, listOf(sats to spk)), 1uL)))
    }

    private val dest = Address("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", Network.TESTNET4).scriptPubkey()

    private val opts = SignOptions(trustWitnessUtxo = false, assumeHeight = null, allowAllSighashes = false, tryFinalize = true, signWithTapInternalKey = true, allowGrinding = true)

    @Test
    fun testnet_vector_and_testnet4_genesis() {
        val v = VaultSim(Network.TESTNET4)
        val w = watch(v.p2wpkhRecv, v.p2wpkhChg, Network.TESTNET4)
        assertEquals("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", w.revealNextAddress(KeychainKind.EXTERNAL).address.toString())
        // Network.TESTNET4 selects testnet4's genesis block (the wallet's first checkpoint), not testnet3's.
        assertEquals("00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043", w.latestCheckpoint().hash.toString())
        val t3 = watch(v.p2wpkhRecv, v.p2wpkhChg, Network.TESTNET)
        assertEquals("000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943", t3.latestCheckpoint().hash.toString())
        val sig = watch(v.p2wpkhRecv, v.p2wpkhChg, Network.SIGNET)
        assertEquals("00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6", sig.latestCheckpoint().hash.toString())
    }

    @Test
    fun watch_only_wallet_cannot_sign() {
        val v = VaultSim(Network.TESTNET4)
        val w84 = watch(v.p2wpkhRecv, v.p2wpkhChg, Network.TESTNET4)
        fund(w84, 0x11, 100_000)
        val psbt = TxBuilder().addRecipient(dest, Amount.fromSat(50_000uL)).feeRate(FeeRate.fromSatPerVb(2uL)).finish(w84)
        assertEquals(false, w84.sign(psbt, opts))
        assertEquals(true, psbt.input().all { it.partialSigs.isEmpty() && it.tapKeySig == null })
    }

    @Test
    fun p2wpkh_psbt_carries_full_previous_transaction() {
        val v = VaultSim(Network.TESTNET4)
        val w84 = watch(v.p2wpkhRecv, v.p2wpkhChg, Network.TESTNET4)
        fund(w84, 0x11, 100_000)
        val psbt = TxBuilder().addRecipient(dest, Amount.fromSat(50_000uL)).feeRate(FeeRate.fromSatPerVb(2uL)).finish(w84)
        val i = psbt.input().single()
        assertNotNull(i.nonWitnessUtxo)
        assertEquals(1, i.bip32Derivation.size)
        assertEquals(fingerprint, i.bip32Derivation.values.single().fingerprint)
    }

    /** Finding: BDK adds `non_witness_utxo` to own taproot inputs never (only `witness_utxo`), which the vault refuses. */
    @Test
    fun own_taproot_input_has_no_non_witness_utxo() {
        val v = VaultSim(Network.TESTNET4)
        val w86 = watch(v.p2trRecv, v.p2trChg, Network.TESTNET4)
        fund(w86, 0x22, 200_000)
        val psbt = TxBuilder().addRecipient(dest, Amount.fromSat(50_000uL)).feeRate(FeeRate.fromSatPerVb(2uL)).finish(w86)
        val i = psbt.input().single()
        assertEquals(null, i.nonWitnessUtxo)
        // What the vault wants of taproot, BDK does give: derivation with the master fingerprint, no leaf hashes, internal key.
        val origin = i.tapKeyOrigins.values.single()
        assertEquals(fingerprint, origin.keySource.fingerprint)
        assertEquals(emptyList<String>(), origin.tapLeafHashes)
        assertEquals(i.tapKeyOrigins.keys.single(), i.tapInternalKey)
        // The change output carries its derivation (else the vault counts it as a payment).
        assertEquals(1, psbt.output().count { it.tapKeyOrigins.isNotEmpty() })
    }

    /**
     * The headline: one PSBT spending a BIP84 coin and a BIP86 coin, `non_witness_utxo` on both (the taproot one added by us as
     * a foreign input), derivations for both, change with a derivation, signed by the vault (simulated with the test phrase),
     * finalised and extracted. The watch-only wallets never hold a private key.
     */
    @Test
    fun both_accounts_in_one_psbt_with_full_previous_transactions() {
        val v = VaultSim(Network.TESTNET4)
        val w84 = watch(v.p2wpkhRecv, v.p2wpkhChg, Network.TESTNET4)
        val w86 = watch(v.p2trRecv, v.p2trChg, Network.TESTNET4)
        fund(w84, 0x11, 100_000)
        fund(w86, 0x22, 200_000)

        val taprootCoin = w86.listUnspent().single()
        val solo = TxBuilder().addUtxo(taprootCoin.outpoint).manuallySelectedOnly().drainTo(dest).finish(w86)
        val foreign = solo.input().single()
        foreign.nonWitnessUtxo = w86.getTx(taprootCoin.outpoint.txid)!!.transaction

        val psbt = TxBuilder()
            .addUtxos(w84.listUnspent().map { it.outpoint })
            .addForeignUtxo(taprootCoin.outpoint, foreign, 66uL)
            .addRecipient(dest, Amount.fromSat(250_000uL))
            .feeRate(FeeRate.fromSatPerVb(2uL))
            .finish(w84)

        val inputs = psbt.input()
        assertEquals(2, inputs.size)
        assertEquals(true, inputs.all { it.nonWitnessUtxo != null })
        assertEquals(1, inputs.count { it.tapKeyOrigins.isNotEmpty() })
        assertEquals(1, inputs.count { it.bip32Derivation.isNotEmpty() })
        assertEquals(true, psbt.output().any { it.bip32Derivation.isNotEmpty() })
        // The vault takes standard base64 of BIP174 v0.
        assertEquals(true, psbt.serialize().startsWith("cHNidP8"))
        assertEquals(psbt.serialize(), Psbt(psbt.serialize()).serialize())

        // The vault (simulated): signs both accounts with the phrase; the phone's wallets above could not.
        assertEquals(false, w84.sign(psbt, opts))
        v.signer86().sign(psbt, opts)
        v.signer84().sign(psbt, opts)
        val finalized = psbt.finalize()
        assertEquals(true, finalized.couldFinalize)
        val tx = finalized.psbt.extractTx()
        assertEquals(listOf(64, 71).sorted(), tx.input().flatMap { it.witness.map { w -> w.size } }.filter { it > 60 }.sorted().map { if (it > 64) 71 else 64 })
        // Fee rate came out near the 2 sat/vB asked for, so the taproot satisfaction weight of 66 was right.
        val fee = 300_000L - 250_000L - tx.output().first { it.value.toSat() != 250_000uL }.value.toSat().toLong()
        assert(fee.toDouble() / tx.vsize().toDouble() in 1.9..2.3) { "fee rate ${fee.toDouble() / tx.vsize().toDouble()}" }
    }

    /** The optional live proof: Esplora full scan of the test account on testnet4. `VETTID_W0_LIVE=1`. Reads public data only. */
    @Test(timeout = 90_000)
    fun live_esplora_sync_testnet4() {
        if (System.getenv("VETTID_W0_LIVE") != "1") return
        val v = VaultSim(Network.TESTNET4)
        // Account 777 of the test phrase: the usual account 0 of the "abandon" phrase has thousands of public transactions, a
        // full scan of it is a load test of the server, not a proof.
        val key = v.pub("m/84'/1'/777'").trimEnd('*', '/')
        val signet = (System.getenv("VETTID_W0_URL") ?: SIGNET_URL).contains("signet")
        val w84 = watch("wpkh($key/0/*)", "wpkh($key/1/*)", if (signet) Network.SIGNET else Network.TESTNET4)
        val client = org.bitcoindevkit.EsploraClient(System.getenv("VETTID_W0_URL") ?: SIGNET_URL, null)
        println("LIVE height=${client.getHeight()}")
        // Finding: client.getFeeEstimates() throws HttpResponse 203 on mempool.space (BDK accepts only 200); not called here.
        val update = client.fullScan(w84.startFullScan().build(), 5uL, 2uL)
        w84.applyUpdate(update)
        println("LIVE height=${client.getHeight()} balance=${w84.balance().total.toSat()} utxos=${w84.listUnspent().size} tip=${w84.latestCheckpoint().height}")
    }
}
