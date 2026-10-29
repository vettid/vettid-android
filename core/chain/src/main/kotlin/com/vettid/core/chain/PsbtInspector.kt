package com.vettid.core.chain

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Protocol
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.utils.Either

/**
 * The vault's signing policy for a PSBT (VAULT-MESSAGING §10.18 "The signing policy"; vettid-vault `vms/btc/psbt.go`, `Inspect`),
 * run on the phone: what `wallet.psbt.inspect` will answer for a PSBT, computed here from the PSBT itself. The vault is the
 * authority and the member sees the vault's summary; this exists so that the app (1) never sends a PSBT its own builder got wrong
 * (the builder checks its output with it), and (2) can compare the vault's answer with what it built (W2). It follows the vault's order
 * of checks, so that when several things are wrong the same reason comes first. Conformance: `VaultConformanceTest`.
 */
object PsbtInspector {
    const val MAX_PSBT = 65_536
    const val MAX_INPUTS = 64
    const val MAX_OUTPUTS = 64

    /** 1,000 sat/vB of the signed transaction's virtual size. */
    const val MAX_FEE_RATE = 1_000L
    const val MIN_OUTPUT = 330L

    /** 21 million BTC. */
    const val MAX_SATOSHI = 2_100_000_000_000_000L

    private class Refusal(val reason: String) : Exception(reason)

    private fun refuse(reason: String): Nothing = throw Refusal(reason)

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun inspect(wallet: Wallet, psbt: ByteArray): InspectResult = try {
        InspectResult.Ok(check(wallet, psbt))
    } catch (r: Refusal) {
        InspectResult.Refused(r.reason)
    } catch (e: Bip174Malformed) {
        InspectResult.Refused(PsbtReasons.MALFORMED)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Whatever a decoder trips over on hostile bytes is a refusal, as in the vault (`ParsePSBT` recovers a panic): never a crash.
        InspectResult.Refused(PsbtReasons.MALFORMED)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseTx(bytes: ByteArray, withWitness: Boolean): Transaction? = try {
        Transaction.read(bytes,
            if (withWitness) Protocol.PROTOCOL_VERSION else Protocol.PROTOCOL_VERSION or Transaction.SERIALIZE_TRANSACTION_NO_WITNESS)
    } catch (e: Exception) {
        null
    }

    // A port of vettid-vault `Inspect`, check for check and in its order: one function on purpose.
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private fun check(wallet: Wallet, raw: ByteArray): InspectSummary {
        if (raw.isEmpty() || raw.size > MAX_PSBT) refuse(PsbtReasons.SIZE)
        var tx: Transaction? = null
        val parsed = try {
            Bip174.read(raw) { txBytes ->
                val t = parseTx(txBytes, withWitness = false) ?: throw Bip174Malformed("transaction")
                if (t.txIn.any { it.hasWitness || !it.signatureScript.isEmpty() }) throw Bip174Malformed("signed transaction")
                tx = t
                t.txIn.size to t.txOut.size
            }
        } catch (e: Bip174Malformed) {
            throw e
        } catch (e: Exception) {
            // Anything a decoder trips over is a refusal, as in the vault (`ParsePSBT` recovers a panic).
            throw Bip174Malformed(e.message ?: "decode")
        }
        val t = tx ?: refuse(PsbtReasons.MALFORMED)
        if (t.txIn.isEmpty() || t.txIn.size > MAX_INPUTS || t.txOut.isEmpty() || t.txOut.size > MAX_OUTPUTS) refuse(PsbtReasons.SHAPE)
        if (t.version < 1 || t.version > 2) refuse(PsbtReasons.VERSION)

        val inputs = ArrayList<SummaryInput>()
        val seen = HashSet<String>()
        var totalIn = 0L
        val inputKinds = ArrayList<Pair<AccountType, Boolean>>() // account type, sighash default (taproot only)
        // Everything the decoder reads is read before any policy check (a malformed map beats every policy reason).
        val maps = parsed.inputs.map { InputMap(it) }
        val outMaps = parsed.outputs.map { OutputMap(it) }
        val prevTxs = maps.map { m ->
            m.nonWitnessUtxo?.let { parseTx(it, withWitness = true) ?: throw Bip174Malformed("non-witness utxo") }
        }
        for ((i, m) in maps.withIndex()) {
            val op = t.txIn[i].outPoint
            val opKey = op.hash.value.toByteArray().toHex() + ":" + op.index
            if (!seen.add(opKey)) refuse(PsbtReasons.DUPLICATE_INPUT)
            val prevTx = prevTxs[i]
            if (prevTx == null || !prevTx.txid.value.toByteArray().contentEquals(op.txid.value.toByteArray()) ||
                op.index >= prevTx.txOut.size) refuse(PsbtReasons.PREVIOUS_TX)
            val prev = prevTx.txOut[op.index.toInt()]
            m.witnessUtxo?.let { (amount, script) ->
                if (amount != prev.amount.sat || !script.contentEquals(prev.publicKeyScript.toByteArray())) refuse(PsbtReasons.WITNESS_UTXO)
            }
            if (m.sighash != 0L && m.sighash != SIGHASH_ALL) refuse(PsbtReasons.SIGHASH)
            if (m.finalScriptSig || m.finalScriptWitness || m.redeemScript || m.witnessScript || m.partialSigs > 0 || m.taprootKeySig ||
                m.taprootScriptSigs > 0 || m.taprootLeafScripts > 0 || m.taprootMerkleRoot
            ) {
                refuse(PsbtReasons.INPUT_SCRIPT)
            }
            val value = prev.amount.sat
            if (value <= 0 || value > MAX_SATOSHI) refuse(PsbtReasons.AMOUNT)
            val own = own(wallet, m.derivations, prev.publicKeyScript.toByteArray()) ?: refuse(PsbtReasons.FOREIGN_INPUT)
            val account = own.account
            if (account.type == AccountType.P2TR) {
                m.taprootInternalKey?.let { ik ->
                    if (!account.derivationKey(own.change, own.index).contentEquals(ik)) refuse(PsbtReasons.INTERNAL_KEY)
                }
            }
            val sighashDefault = account.type == AccountType.P2TR && m.sighash == 0L
            inputKinds += account.type to sighashDefault
            totalIn += value
            inputs += SummaryInput(op.hash.value.toByteArray().toDisplayHex(), op.index, value, account.type.vaultName,
                account.path(own.change,
                    own.index))
        }

        val outputs = ArrayList<SummaryOutput>()
        var sending = 0L
        var change = 0L
        var totalOut = 0L
        for ((i, out) in t.txOut.withIndex()) {
            val v = out.amount.sat
            if (v < MIN_OUTPUT || v > MAX_SATOSHI) refuse(PsbtReasons.OUTPUT_AMOUNT)
            val script = out.publicKeyScript.toByteArray()
            if (!isStandard(script)) refuse(PsbtReasons.OUTPUT_SCRIPT)
            val address = when (val a = Bitcoin.addressFromPublicKeyScript(wallet.network.chainHash, script)) {
                is Either.Right -> a.value
                is Either.Left -> refuse(PsbtReasons.OUTPUT_SCRIPT)
            }
            val own = own(wallet, outMaps[i].derivations, script)
            if (own != null) {
                outputs += SummaryOutput(address, v, true, own.account.type.vaultName, own.account.path(own.change, own.index))
                change += v
            } else {
                outputs += SummaryOutput(address, v, false, null, null)
                sending += v
            }
            totalOut += v
        }
        if (totalIn > MAX_SATOSHI || totalOut >= totalIn) refuse(PsbtReasons.FEE)
        val fee = totalIn - totalOut
        val vsize = vsize(t, inputKinds)
        if (fee > MAX_FEE_RATE * vsize) refuse(PsbtReasons.FEE_RATE)
        // Every input is segwit, so the transaction's id does not change when it is signed.
        return InspectSummary(t.hash.value.toByteArray().toDisplayHex(), inputs, outputs, totalIn, sending, change, fee, vsize)
    }

    private const val SIGHASH_ALL = 1L

    private class Own(val account: Account, val change: Boolean, val index: Int)

    /**
     * The wallet's derivation among those a PSBT claims for a script (vettid-vault `own`): the first one with the wallet's fingerprint
     * decides. One that does not check out is a refusal; none at all is null (the script is not the wallet's).
     */
    @Suppress("CyclomaticComplexMethod")
    private fun own(wallet: Wallet, ds: List<Derivation>, script: ByteArray): Own? {
        for (d in ds) {
            if (!d.fingerprint.contentEquals(wallet.fingerprint)) continue
            var account: Account? = null
            for (a in wallet.accounts) {
                val want = a.originPath
                if ((a.type == AccountType.P2TR) == d.taproot && d.path.size == 5 && d.path[0] == want[0] && d.path[1] == want[1] &&
                    d.path[2] == want[2]) account = a
            }
            if (account == null || d.leafHashes != 0) refuse(PsbtReasons.DERIVATION_PATH)
            val chain = d.path[3]
            val index = d.path[4]
            if (chain > 1 || index >= Account.MAX_INDEX) refuse(PsbtReasons.DERIVATION_PATH)
            val isChange = chain == 1L
            if (!account.derivationKey(isChange, index.toInt()).contentEquals(d.key)) refuse(PsbtReasons.DERIVATION_KEY)
            if (!account.scriptPubKey(isChange, index.toInt()).contentEquals(script)) refuse(PsbtReasons.DERIVATION_SCRIPT)
            return Own(account, isChange, index.toInt())
        }
        return null
    }

    /** P2PKH, P2SH, P2WPKH, P2WSH, P2TR: the standard scripts the vault will pay to (vettid-vault `standard`). */
    @Suppress("CyclomaticComplexMethod")
    internal fun isStandard(s: ByteArray): Boolean {
        fun u(i: Int) = s[i].toInt() and 0xff
        return when (s.size) {
            25 -> u(0) == 0x76 && u(1) == 0xa9 && u(2) == 0x14 && u(23) == 0x88 && u(24) == 0xac
            23 -> u(0) == 0xa9 && u(1) == 0x14 && u(22) == 0x87
            22 -> u(0) == 0x00 && u(1) == 0x14
            34 -> (u(0) == 0x00 || u(0) == 0x51) && u(1) == 0x20
            else -> false
        }
    }

    /**
     * The signed transaction's virtual size as the vault computes it: the stripped size, plus marker and flag, plus per input the
     * witness of a P2WPKH spend (108 weight units) or of a taproot key-path spend (66, 67 with SIGHASH_ALL).
     */
    private fun vsize(tx: Transaction, kinds: List<Pair<AccountType, Boolean>>): Long {
        var weight = Transaction.write(tx, Protocol.PROTOCOL_VERSION or Transaction.SERIALIZE_TRANSACTION_NO_WITNESS).size.toLong() * 4 + 2
        for ((type, sighashDefault) in kinds) {
            weight += when {
                type == AccountType.P2WPKH -> 108
                sighashDefault -> 66
                else -> 67
            }
        }
        return (weight + 3) / 4
    }
}
