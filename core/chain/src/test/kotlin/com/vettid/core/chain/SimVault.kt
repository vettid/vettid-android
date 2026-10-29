package com.vettid.core.chain

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.utils.Either

class VaultRefusal(val reason: String) : Exception(reason)

/**
 * The vault, simulated, for the public "abandon ... about" test phrase only. It holds the keys; the phone's code under test
 * (`Account`, `Spender`) never does. [check] follows VAULT-MESSAGING §10.18's signing policy (a subset, with the reasons' names);
 * [sign] then signs every input with the key it re-derived from the PSBT's own derivation entries.
 */
class SimVault(private val network: Network) {
    private val master = DeterministicWallet.generate(
        MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""),
    )
    val fingerprint: Long = master.fingerprint()

    private fun accountPub(purpose: Long) = master.derivePrivateKey("m/$purpose'/${network.coinType}'/0'").extendedPublicKey

    /** What `wallet.get` returns for the two accounts: `accounts[].descriptors.{receive,change}`. */
    fun descriptors(type: AccountType): Pair<String, String> {
        val key = accountPub(type.purpose).encode(network.testKeys)
        val fp = fingerprint.toString(16).padStart(8, '0')
        fun d(branch: Int) = "${type.descriptorFunction}([$fp/${type.purpose}'/${network.coinType}'/0']$key/$branch/*)"
        return d(0) to d(1)
    }

    fun account(type: AccountType): Account = descriptors(type).let { Account.fromDescriptors(it.first, it.second, network) }

    private fun pathOf(path: KeyPath, purpose: Long, i: Int): Pair<Boolean, Int> {
        val p = path.path
        val ok = p.size == 5 && p[0] == DeterministicWallet.hardened(purpose) && p[1] == DeterministicWallet.hardened(network.coinType) &&
            p[2] == DeterministicWallet.hardened(0) && (p[3] == 0L || p[3] == 1L) && p[4] in 0 until (1L shl 20)
        if (!ok) throw VaultRefusal("derivation_path (input $i)")
        return (p[3] == 1L) to p[4].toInt()
    }

    /** `wallet.psbt.inspect`: the reasons of §10.18 for what this spike's PSBTs can get wrong. Returns the fee in sats and the sat/vB. */
    fun check(psbt: Psbt): Pair<Long, Double> {
        val tx = psbt.global.tx
        if (tx.txIn.isEmpty() || tx.txIn.size > 64 || tx.txOut.isEmpty() || tx.txOut.size > 64) throw VaultRefusal("shape")
        if (tx.version != 1L && tx.version != 2L) throw VaultRefusal("version")
        if (tx.txIn.map { it.outPoint }.toSet().size != tx.txIn.size) throw VaultRefusal("duplicate_input")
        var totalIn = 0L
        var witnessWeight = 0
        psbt.inputs.forEachIndexed { i, input ->
            if (input !is Input.WitnessInput.PartiallySignedWitnessInput || input.partialSigs.isNotEmpty() || input.taprootKeySignature != null) throw VaultRefusal("input_script (input $i)")
            val prev = input.nonWitnessUtxo ?: throw VaultRefusal("previous_tx (input $i)")
            val op = tx.txIn[i].outPoint
            if (prev.txid != op.txid || op.index >= prev.txOut.size) throw VaultRefusal("previous_tx (input $i)")
            val out = prev.txOut[op.index.toInt()]
            if (input.txOut != out) throw VaultRefusal("witness_utxo (input $i)")
            if (out.amount.sat <= 0 || out.amount.sat > 21_000_000L * 100_000_000L) throw VaultRefusal("amount")
            totalIn += out.amount.sat
            val wallet = if (Script.isPay2wpkh(out.publicKeyScript.toByteArray())) AccountType.P2WPKH else if (Script.isPay2tr(out.publicKeyScript)) AccountType.P2TR else throw VaultRefusal("foreign_input (input $i)")
            val (change, index, derivedKey) = when (wallet) {
                AccountType.P2WPKH -> {
                    val (pub, d) = input.derivationPaths.entries.singleOrNull() ?: throw VaultRefusal("foreign_input (input $i)")
                    if (d.masterKeyFingerprint != fingerprint) throw VaultRefusal("foreign_input (input $i)")
                    val (c, ix) = pathOf(d.keyPath, 84, i)
                    if (accountPub(84).derivePublicKey(listOf(if (c) 1L else 0L, ix.toLong())).publicKey != pub) throw VaultRefusal("derivation_key (input $i)")
                    Triple(c, ix, pub)
                }
                AccountType.P2TR -> {
                    val (x, d) = input.taprootDerivationPaths.entries.singleOrNull() ?: throw VaultRefusal("foreign_input (input $i)")
                    if (d.masterKeyFingerprint != fingerprint) throw VaultRefusal("foreign_input (input $i)")
                    if (d.leaves.isNotEmpty()) throw VaultRefusal("derivation_path (leaf hashes, input $i)")
                    if (input.taprootInternalKey != null && input.taprootInternalKey != x) throw VaultRefusal("internal_key (input $i)")
                    val (c, ix) = pathOf(d.keyPath, 86, i)
                    val k = accountPub(86).derivePublicKey(listOf(if (c) 1L else 0L, ix.toLong())).publicKey
                    if (k.xOnly() != x) throw VaultRefusal("derivation_key (input $i)")
                    Triple(c, ix, k)
                }
            }
            @Suppress("UNUSED_VARIABLE") val unused = Triple(change, index, derivedKey)
            // derivation_script: the BIP86 tweak (key path, no script tree) or the P2WPKH hash must give the output's script.
            val script = when (wallet) {
                AccountType.P2WPKH -> Script.write(Script.pay2wpkh(derivedKey))
                AccountType.P2TR -> Script.write(Script.pay2tr(derivedKey.xOnly(), Crypto.TaprootTweak.KeyPathTweak))
            }
            if (!script.contentEquals(out.publicKeyScript.toByteArray())) throw VaultRefusal("derivation_script (input $i)")
            witnessWeight += if (wallet == AccountType.P2WPKH) 108 else 66
        }
        tx.txOut.forEach { if (it.amount.sat < 330) throw VaultRefusal("output_amount") }
        // Change outputs (own derivation) would be re-derived and checked the same way; here: if one claims a derivation, it must match.
        psbt.outputs.forEachIndexed { i, o ->
            o.taprootDerivationPaths.forEach { (x, d) ->
                val (c, ix) = pathOf(d.keyPath, 86, i)
                val k = accountPub(86).derivePublicKey(listOf(if (c) 1L else 0L, ix.toLong())).publicKey
                if (k.xOnly() != x || !Script.write(Script.pay2tr(x, Crypto.TaprootTweak.KeyPathTweak)).contentEquals(tx.txOut[i].publicKeyScript.toByteArray())) throw VaultRefusal("derivation_script (change $i)")
            }
            o.derivationPaths.forEach { (pub, d) ->
                val (c, ix) = pathOf(d.keyPath, 84, i)
                if (accountPub(84).derivePublicKey(listOf(if (c) 1L else 0L, ix.toLong())).publicKey != pub || !Script.write(Script.pay2wpkh(pub)).contentEquals(tx.txOut[i].publicKeyScript.toByteArray())) throw VaultRefusal("derivation_script (change $i)")
            }
        }
        val fee = totalIn - tx.txOut.sumOf { it.amount.sat }
        if (fee <= 0) throw VaultRefusal("fee")
        val vsize = (tx.weight() + witnessWeight + 2 + 3) / 4.0
        val rate = fee / vsize
        if (rate > 1000) throw VaultRefusal("fee_rate")
        return fee to rate
    }

    /** `wallet.sign`: check, then sign each input with the private key at its own derivation path. */
    fun sign(psbt: Psbt): Psbt {
        check(psbt)
        var p = psbt
        psbt.inputs.forEachIndexed { i, input ->
            val path = input.derivationPaths.values.firstOrNull()?.keyPath ?: input.taprootDerivationPaths.values.single().keyPath
            val priv = master.derivePrivateKey(path).privateKey
            p = when (val r = p.sign(priv, i)) {
                is Either.Right -> r.value.psbt
                is Either.Left -> throw VaultRefusal(r.value.toString())
            }
        }
        return p
    }
}
