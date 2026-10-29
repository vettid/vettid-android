package com.vettid.core.chain

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.KeyPathWithMaster
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.psbt.TaprootBip32DerivationPath
import fr.acinq.bitcoin.utils.Either

/**
 * A coin of the wallet to spend, as the chain source reports it: where it sits in the wallet (account, chain, index) and the FULL
 * previous transaction (Esplora `GET /tx/:txid/hex`), which the vault requires on every input (§10.18, `previous_tx`), taproot
 * inputs included.
 */
class BuildCoin(val accountType: AccountType, val change: Boolean, val index: Int, val prevTx: ByteArray, val vout: Int)

/** An output to build: a payment to an address, or an output to the wallet itself (change; the vault recognises it by its derivation). */
sealed interface BuildOutput {
    class Pay(val address: String, val amountSats: Long) : BuildOutput

    class Own(val accountType: AccountType, val change: Boolean, val index: Int, val amountSats: Long) : BuildOutput
}

/** The builder refused: [reason] is the vault's reason for the PSBT it would have refused, or `coin`, `duplicate_coin`, `address`. */
class BuildException(val reason: String) : Exception(reason)

/**
 * The unsigned PSBT an app gives the vault (§10.18; the counterpart of vettid-vault `vms/btc/build.go`, `BuildPSBT`): the coins with
 * their previous transactions and BIP32 or taproot derivations, the payees, and the change derivations. Coin selection and fees are
 * the caller's (W2); this assembles, and then runs the vault's policy ([PsbtInspector]) over what it made, so that a builder bug is an
 * error here and not a refusal at the vault. Nothing here knows a private key.
 *
 * Shape (the same bytes as vettid-vault `BuildPSBT`, checked by `VaultConformanceTest`): transaction version 2, locktime 0, every
 * sequence 0xfffffffd (BIP125, so a later release can bump the fee), `non_witness_utxo` and `witness_utxo` on every input, a P2WPKH
 * input with SIGHASH_ALL and its BIP32 derivation, a P2TR input with its internal key and a taproot derivation without leaf hashes
 * (default sighash), a change output with its derivation.
 */
object PsbtBuilder {
    private const val RBF_SEQUENCE = 0xfffffffdL
    private const val SIGHASH_ALL = 1

    @Suppress("SwallowedException")
    fun build(wallet: Wallet, coins: List<BuildCoin>, outputs: List<BuildOutput>): ByteArray = try {
        assemble(wallet, coins, outputs)
    } catch (e: IllegalArgumentException) {
        // An index outside the range the vault accepts (Account.MAX_INDEX), or a key the library will not derive.
        throw BuildException("coin")
    }

    @Suppress("CyclomaticComplexMethod")
    private fun assemble(wallet: Wallet, coins: List<BuildCoin>, outputs: List<BuildOutput>): ByteArray {
        if (coins.isEmpty() || outputs.isEmpty()) throw BuildException(PsbtReasons.SHAPE)
        val prevTxs = coins.map { c ->
            val tx = try {
                Transaction.read(c.prevTx)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                throw BuildException("coin")
            }
            if (c.vout !in tx.txOut.indices) throw BuildException("coin")
            val account = account(wallet, c.accountType)
            // A chain source that lies about where a coin is cannot make the app build a PSBT the vault would read differently.
            if (!tx.txOut[c.vout].publicKeyScript.toByteArray().contentEquals(account.scriptPubKey(c.change,
                c.index))) throw BuildException("coin")
            tx
        }
        val txOuts = outputs.map { o ->
            when (o) {
                is BuildOutput.Pay -> {
                    val script = when (val r = Bitcoin.addressToPublicKeyScript(wallet.network.chainHash, o.address)) {
                        is Either.Right -> fr.acinq.bitcoin.Script.write(r.value)
                        is Either.Left -> throw BuildException("address")
                    }
                    TxOut(Satoshi(o.amountSats), ByteVector(script))
                }
                is BuildOutput.Own -> TxOut(Satoshi(o.amountSats), ByteVector(account(wallet, o.accountType).scriptPubKey(o.change,
                    o.index)))
            }
        }
        val tx = Transaction(
            2,
            coins.indices.map { TxIn(OutPoint(prevTxs[it], coins[it].vout.toLong()), ByteVector.empty, RBF_SEQUENCE) },
            txOuts,
            0,
        )
        if (tx.txIn.map { it.outPoint }.toSet().size != tx.txIn.size) throw BuildException("duplicate_coin")
        var psbt = Psbt(tx)
        for ((i, c) in coins.withIndex()) {
            val account = account(wallet, c.accountType)
            val pub = account.publicKey(c.change, c.index)
            val origin = KeyPathWithMaster(fingerprintLong(account), account.fullPath(c.change, c.index))
            // updateWitnessInputTx always sets non_witness_utxo (and witness_utxo), taproot included.
            psbt = when (account.type) {
                AccountType.P2WPKH -> psbt.updateWitnessInputTx(prevTxs[i], c.vout, sighashType = SIGHASH_ALL,
                    derivationPaths = mapOf(pub to origin))
                AccountType.P2TR -> {
                    val internal = pub.xOnly()
                    psbt.updateWitnessInputTx(
                        prevTxs[i], c.vout,
                        taprootInternalKey = internal,
                        taprootDerivationPaths = mapOf(internal to TaprootBip32DerivationPath(emptyList(), origin.masterKeyFingerprint,
                            origin.keyPath)),
                    )
                }
            }.orThrow()
        }
        for ((i, o) in outputs.withIndex()) {
            if (o !is BuildOutput.Own) continue
            val account = account(wallet, o.accountType)
            val pub = account.publicKey(o.change, o.index)
            val origin = KeyPathWithMaster(fingerprintLong(account), account.fullPath(o.change, o.index))
            psbt = when (account.type) {
                AccountType.P2WPKH -> psbt.updateWitnessOutput(i, derivationPaths = mapOf(pub to origin))
                AccountType.P2TR -> psbt.updateWitnessOutput(
                    i,
                    taprootInternalKey = pub.xOnly(),
                    taprootDerivationPaths = mapOf(pub.xOnly() to TaprootBip32DerivationPath(emptyList(), origin.masterKeyFingerprint,
                        origin.keyPath)),
                )
            }.orThrow()
        }
        val bytes = Psbt.write(psbt).toByteArray()
        // The vault's policy over what was made: a refusal here is the reason the vault would have given.
        when (val r = PsbtInspector.inspect(wallet, bytes)) {
            is InspectResult.Refused -> throw BuildException(r.reason)
            is InspectResult.Ok -> Unit
        }
        return bytes
    }

    private fun account(wallet: Wallet, type: AccountType): Account =
        wallet.accounts.firstOrNull { it.type == type } ?: throw BuildException(PsbtReasons.NO_ACCOUNT)

    private fun fingerprintLong(a: Account): Long = a.fingerprint.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }

    private fun <A> Either<*, A>.orThrow(): A = when (this) {
        is Either.Right -> value
        is Either.Left -> throw BuildException("psbt: $value")
    }
}

/** The `psbt` member of `wallet.psbt.inspect` and `wallet.sign`: standard base64 (RFC 4648 section 4, padded) of at most 65,536 bytes. */
object PsbtBase64 {
    /** vettid-vault `features/wallet/wallet.go`: `maxPSBTBase64` = (65,536 + 2) / 3 * 4 characters, the longest text the vault reads. */
    const val MAX_CHARS: Int = (PsbtInspector.MAX_PSBT + 2) / 3 * 4

    fun encode(psbt: ByteArray): String = java.util.Base64.getEncoder().encodeToString(psbt)

    /** Null for text the vault answers `bad_request` to (empty, too long, not standard base64). */
    @Suppress("SwallowedException")
    fun decode(text: String): ByteArray? {
        if (text.isEmpty() || text.length > MAX_CHARS || text.length % 4 != 0) return null
        val bytes = try { java.util.Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) { null } ?: return null
        // Strict, as Go's StdEncoding.Strict(): the trailing padding bits must be zero, i.e. the text is the canonical encoding.
        return if (encode(bytes) == text) bytes else null
    }
}
