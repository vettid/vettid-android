package com.vettid.core.chain

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.KeyPathWithMaster
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.psbt.TaprootBip32DerivationPath
import fr.acinq.bitcoin.utils.Either
import java.util.Base64

class SpendException(message: String) : Exception(message)

/**
 * A coin of the wallet, as the chain source reports it: the output's place in the wallet (account, chain, index) and the FULL
 * previous transaction (Esplora `GET /tx/:txid/hex`), which the vault requires on every input (§10.18, `previous_tx`).
 */
class Coin(val account: Account, val change: Boolean, val index: Int, val prevTx: Transaction, val vout: Int) {
    val outPoint: OutPoint = OutPoint(prevTx, vout.toLong())
    val txOut: TxOut get() = prevTx.txOut[vout]
    val value: Satoshi get() = txOut.amount

    init {
        // A chain source that lies about where a coin is cannot make the app build a PSBT the vault would sign differently.
        require(vout in prevTx.txOut.indices) { "output index out of range" }
        require(txOut.publicKeyScript == account.script(change, index)) { "the output is not the wallet's address at that index" }
    }

    /** Weight of the input once signed: outpoint, empty scriptSig, sequence (41 bytes x 4) plus the witness (§10.18: 108 or 66). */
    val inputWeight: Int get() = 164 + if (account.type == AccountType.P2WPKH) 108 else 66
}

/** Where the change goes: an address the vault issued (`wallet.address.new{change: true}`), so that its index is tracked there. */
class ChangeTarget(val account: Account, val index: Int)

class Payee(val address: String, val amount: Satoshi)

object Spender {
    /** The vault refuses outputs below this (§10.18, `output_amount`). */
    const val DUST = 330L
    private const val RBF_SEQUENCE = 0xfffffffdL

    private fun feeFor(weight: Int, satPerVb: Long): Long = (weight.toLong() * satPerVb + 3) / 4

    /**
     * An unsigned PSBT (BIP174 v0) spending [coins] (of either or both accounts) to [payees], largest coins first, with change to
     * [change] when it is worth at least [DUST]. Every input carries `non_witness_utxo` (also the taproot ones), its BIP32 or
     * taproot origin with the wallet's fingerprint, and for taproot the internal key and no leaf hashes; the change output carries
     * its derivation. Nothing here knows a private key.
     */
    fun build(network: Network, coins: List<Coin>, payees: List<Payee>, satPerVb: Long, change: ChangeTarget): Psbt {
        require(payees.isNotEmpty() && satPerVb >= 1)
        val outs = payees.map { p ->
            val script = when (val r = Bitcoin.addressToPublicKeyScript(network.chainHash, p.address)) {
                is Either.Right -> Script.write(r.value)
                is Either.Left -> throw SpendException("not an address of this network: ${p.address}")
            }
            if (p.amount.sat < DUST) throw SpendException("amount below ${DUST} sats")
            TxOut(p.amount, ByteVector(script))
        }
        val changeScript = change.account.script(true, change.index)
        val target = outs.sumOf { it.amount.sat }
        // 4 bytes version + 1 + 1 counts + 4 locktime = 40 WU, +2 for the segwit marker and flag.
        val fixed = 42 + outs.sumOf { TxOut.weight(it) }
        val changeWeight = TxOut.weight(TxOut(Satoshi(0), changeScript))

        var picked = emptyList<Coin>()
        var sum = 0L
        var changeValue = 0L
        var ok = false
        for (coin in coins.sortedByDescending { it.value.sat }) {
            picked = picked + coin
            sum += coin.value.sat
            val weight = fixed + picked.sumOf { it.inputWeight }
            val noChange = sum - target - feeFor(weight, satPerVb)
            if (noChange < 0) continue
            val withChange = sum - target - feeFor(weight + changeWeight, satPerVb)
            changeValue = if (withChange >= DUST) withChange else 0
            ok = true
            break
        }
        if (!ok) throw SpendException("insufficient funds")
        if (picked.size > 64) throw SpendException("more than 64 inputs (the vault's limit)")

        val txOuts = outs + if (changeValue > 0) listOf(TxOut(Satoshi(changeValue), changeScript)) else emptyList()
        val tx = Transaction(
            2,
            picked.map { TxIn(it.outPoint, ByteVector.empty, RBF_SEQUENCE) },
            txOuts,
            0,
        )
        var psbt = Psbt(tx)
        for (c in picked) {
            val fp = c.account.fingerprint
            val path = c.account.path(c.change, c.index)
            val pub = c.account.publicKey(c.change, c.index)
            // updateWitnessInputTx always sets non_witness_utxo (and witness_utxo), taproot included: BIP174's recommendation.
            psbt = when (c.account.type) {
                AccountType.P2WPKH -> psbt.updateWitnessInputTx(c.prevTx, c.vout, derivationPaths = mapOf(pub to KeyPathWithMaster(fp, path)))
                AccountType.P2TR -> {
                    val internal = pub.xOnly()
                    psbt.updateWitnessInputTx(
                        c.prevTx, c.vout,
                        taprootInternalKey = internal,
                        taprootDerivationPaths = mapOf(internal to TaprootBip32DerivationPath(emptyList(), fp, path)),
                    )
                }
            }.orThrow()
        }
        if (changeValue > 0) {
            val index = txOuts.lastIndex
            val a = change.account
            val pub = a.publicKey(true, change.index)
            val path = a.path(true, change.index)
            psbt = when (a.type) {
                AccountType.P2WPKH -> psbt.updateWitnessOutput(index, derivationPaths = mapOf(pub to KeyPathWithMaster(a.fingerprint, path)))
                AccountType.P2TR -> psbt.updateWitnessOutput(
                    index,
                    taprootInternalKey = pub.xOnly(),
                    taprootDerivationPaths = mapOf(pub.xOnly() to TaprootBip32DerivationPath(emptyList(), a.fingerprint, path)),
                )
            }.orThrow()
        }
        return psbt
    }

    /** Standard base64 of BIP174 v0, what `wallet.psbt.inspect` and `wallet.sign` take. */
    fun serialize(psbt: Psbt): String = Base64.getEncoder().encodeToString(Psbt.write(psbt).toByteArray())

    fun parse(base64: String): Psbt = when (val r = Psbt.read(Base64.getDecoder().decode(base64))) {
        is Either.Right -> r.value
        is Either.Left -> throw SpendException("not a PSBT: ${r.value}")
    }

    /**
     * Finalise a PSBT the vault signed (partial signatures or a taproot key signature in each input) and extract the transaction.
     * `Psbt.extract` runs bitcoin-kmp's script interpreter over every input with the standard flags before it returns.
     */
    fun finalizeAndExtract(signed: Psbt): Transaction {
        var p = signed
        signed.inputs.forEachIndexed { i, input ->
            if (input.scriptWitness != null) return@forEachIndexed // already final
            val witnessInput = input as? Input.WitnessInput.PartiallySignedWitnessInput ?: throw SpendException("input $i is not a segwit input")
            val witness = when {
                witnessInput.taprootKeySignature != null -> {
                    val sig = witnessInput.taprootKeySignature!!.toByteArray()
                    if (sig.size == 64) Script.witnessKeyPathPay2tr(ByteVector64(sig)) else Script.witnessKeyPathPay2tr(ByteVector64(sig.copyOf(64)), sig[64].toInt() and 0xff)
                }
                witnessInput.partialSigs.size == 1 -> {
                    val (pub, sig) = witnessInput.partialSigs.entries.single()
                    fr.acinq.bitcoin.ScriptWitness(listOf(sig, pub.value))
                }
                else -> throw SpendException("input $i is not signed")
            }
            p = p.finalizeWitnessInput(i, witness).orThrow()
        }
        return p.extract().orThrow()
    }

    private fun <A> Either<*, A>.orThrow(): A = when (this) {
        is Either.Right -> value
        is Either.Left -> throw SpendException(value.toString())
    }

    /** Fee of a PSBT whose inputs all carry their previous transaction, in sats (null if an input has none). */
    fun fee(psbt: Psbt): Long? = psbt.computeFees()?.sat

}
