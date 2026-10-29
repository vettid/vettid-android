package com.vettid.core.chain

import fr.acinq.bitcoin.Crypto

/**
 * A reader for the key-value maps of a BIP174 (version 0) PSBT, and nothing else. The wallet's policy checks (the vault's, which the
 * app mirrors) look at specific keys; a library's PSBT model hides some of them (taproot script data, a finalised input's partial
 * signatures) and fails on others before a policy reason can be given (a previous transaction that does not match its outpoint).
 * Reading the maps directly keeps the reasons the same as the vault's.
 *
 * What it refuses as malformed is what vettid-vault's decoder (btcd `btcutil/psbt` v1.1.10, `NewFromRawBytes`) refuses, for these
 * rules: the magic, canonical compact sizes, truncation, the transaction being the first global entry and parsing without witness,
 * singleton entries (once only, no key data), duplicate keys, key and value lengths, valid secp256k1 points for public keys, strict
 * DER for partial signatures, the `witness_utxo` layout (`readTxOut`), unknown entries repeated with the same key and value, and the
 * global xpub layout. Known gaps, all of which fail closed or are caught by the vault: the base58 version bytes of a global xpub are
 * checked against the four registered ones (btcd also checks the key's validity as hdkeychain does, approximated here); a taproot
 * leaf script's control block is checked for length, not for its leaf version. `BIP174` vectors with the vault's answers are in
 * `VaultConformanceTest`.
 */
internal class Bip174Malformed(message: String) : Exception(message)

internal class Bip174Entry(val type: Int, val keyData: ByteArray, val value: ByteArray) {
    val hasKeyData: Boolean get() = keyData.isNotEmpty()
}

internal class Bip174(
    val unsignedTx: ByteArray,
    val inputs: List<List<Bip174Entry>>,
    val outputs: List<List<Bip174Entry>>,
) {
    companion object {
        private const val MAX_KEY = 10_000
        private val XKEY_VERSIONS = listOf(0x0488B21E, 0x0488ADE4, 0x043587CF, 0x04358394)

        /** Global xpub entries as btcd's `ReadXPub` reads them (the other global entries are not looked at). */
        private fun checkGlobal(global: List<Bip174Entry>) {
            val seen = HashSet<String>()
            for (e in global.drop(1)) {
                if (e.type != 0x01) continue
                if (!seen.add(e.keyData.toHex())) throw Bip174Malformed("duplicate xpub")
                val k = e.keyData
                if (k.size != 78) throw Bip174Malformed("xpub")
                val version = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (k[i].toInt() and 0xff) }
                if (version !in XKEY_VERSIONS) throw Bip174Malformed("xpub version")
                val depth = k[4].toInt() and 0xff
                val zeroParent = (5..12).all { k[it].toInt() == 0 }
                if (depth == 0 && !zeroParent) throw Bip174Malformed("xpub depth")
                val isPrivate = version == XKEY_VERSIONS[1] || version == XKEY_VERSIONS[3]
                val keyBytes = k.copyOfRange(45, 78)
                if (isPrivate) {
                    if (keyBytes[0].toInt() != 0) throw Bip174Malformed("xprv")
                } else if (!validPubkey(keyBytes)) {
                    throw Bip174Malformed("xpub key")
                }
                if (e.value.size != 4 * (depth + 1)) throw Bip174Malformed("xpub path")
            }
        }
        private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())

        /** Everything in the global map except the transaction is read and dropped; a global map without the transaction is malformed. */
        fun read(raw: ByteArray, inputCount: (ByteArray) -> Pair<Int, Int>): Bip174 {
            val r = Reader(raw)
            if (raw.size < MAGIC.size || !raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw Bip174Malformed("magic")
            r.pos = MAGIC.size
            val global = r.readMap()
            // btcd insists the transaction is the first entry of the global map.
            val tx = global.firstOrNull()?.takeIf { it.type == 0x00 } ?: throw Bip174Malformed("no unsigned transaction")
            if (tx.hasKeyData) throw Bip174Malformed("transaction key data")
            checkGlobal(global)
            val (nIn, nOut) = inputCount(tx.value)
            val ins = List(nIn) { r.readMap() }
            val outs = List(nOut) { r.readMap() }
            // Bytes after the last map are NOT refused: btcd's decoder (the vault's) stops after the output maps. See the
            // vector `regtest/ok_trailing_bytes_ignored`.
            return Bip174(tx.value, ins, outs)
        }
    }

    private class Reader(val b: ByteArray) {
        var pos = 0

        fun compact(): Long {
            if (pos >= b.size) throw Bip174Malformed("end of data")
            val first = b[pos++].toInt() and 0xff
            val n = when (first) {
                0xfd -> 2
                0xfe -> 4
                0xff -> 8
                else -> return first.toLong()
            }
            if (pos + n > b.size) throw Bip174Malformed("end of data")
            var v = 0L
            for (i in 0 until n) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i))
            pos += n
            // Canonical form only (wire.ReadVarInt): a value that fits a shorter form must use it.
            val minimum = when (n) { 2 -> 0xfdL; 4 -> 0x10000L; else -> 0x100000000L }
            if (v < 0 || v < minimum) throw Bip174Malformed("non-canonical length")
            return v
        }

        fun bytes(n: Long): ByteArray {
            if (n < 0 || n > b.size - pos) throw Bip174Malformed("end of data")
            val out = b.copyOfRange(pos, pos + n.toInt())
            pos += n.toInt()
            return out
        }

        fun readMap(): List<Bip174Entry> {
            val out = ArrayList<Bip174Entry>()
            while (true) {
                val keyLen = compact()
                if (keyLen == 0L) return out
                if (keyLen > MAX_KEY) throw Bip174Malformed("key too long")
                val key = bytes(keyLen)
                // The key type is a compact size at the start of the key.
                val kr = Reader(key)
                val type = kr.compact()
                if (type > 0x02000000L) throw Bip174Malformed("key type")
                val keyData = key.copyOfRange(kr.pos, key.size)
                val value = bytes(compact())
                out += Bip174Entry(type.toInt(), keyData, value)
            }
        }
    }
}

/** btcd `validatePubkey`: a compressed or uncompressed secp256k1 point. */
internal fun validPubkey(b: ByteArray): Boolean = (b.size == 33 || b.size == 65) && Crypto.isPubKeyValid(b)

/** btcd `validateXOnlyPubkey`: 32 bytes that are the x coordinate of a point. */
internal fun validXonly(b: ByteArray): Boolean = b.size == 32 && Crypto.isPubKeyValid(byteArrayOf(2) + b)

private val CURVE_ORDER = java.math.BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

/**
 * btcd `validateSignature` (`ecdsa.ParseDERSignature`): strict DER (BIP66) of a signature, followed by anything (the sighash byte); R and S
 * positive, below the curve order, minimally encoded.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod")
internal fun validDer(sig: ByteArray): Boolean {
    fun u(i: Int) = sig[i].toInt() and 0xff
    if (sig.size < 8 || u(0) != 0x30) return false
    val total = u(1)
    if (total + 2 > sig.size) return false
    var pos = 2
    val parts = ArrayList<java.math.BigInteger>()
    repeat(2) {
        if (pos + 2 > total + 2 || u(pos) != 0x02) return false
        val len = u(pos + 1)
        pos += 2
        if (len == 0 || pos + len > total + 2) return false
        if (u(pos) and 0x80 != 0) return false
        if (len > 1 && u(pos) == 0 && u(pos + 1) and 0x80 == 0) return false
        val v = java.math.BigInteger(1, sig.copyOfRange(pos, pos + len))
        if (v.signum() == 0 || v >= CURVE_ORDER) return false
        parts += v
        pos += len
    }
    return pos == total + 2
}

/** A derivation entry of either kind, in the order they stand in the map (BIP32 ones first, then taproot ones, as the vault lists them). */
internal class Derivation(val fingerprint: ByteArray, val path: LongArray, val key: ByteArray, val taproot: Boolean, val leafHashes: Int)

private fun u32le(b: ByteArray, at: Int): Long =
    (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xff) shl (8 * i)) }

private fun bip32Of(key: ByteArray, value: ByteArray, taproot: Boolean, leafHashes: Int = 0): Derivation {
    if (value.size < 4 || value.size % 4 != 0) throw Bip174Malformed("derivation value")
    return Derivation(value.copyOfRange(0, 4), LongArray(value.size / 4 - 1) { i -> u32le(value, 4 * (i + 1)) }, key, taproot, leafHashes)
}

private fun taprootDerivation(key: ByteArray, value: ByteArray): Derivation {
    if (value.size < 5) throw Bip174Malformed("taproot derivation value")
    val first = value[0].toInt() and 0xff
    val (n, pos) = when (first) {
        0xfd -> (((value[1].toLong() and 0xff) or ((value[2].toLong() and 0xff) shl 8)) to 3).also {
            if (it.first < 0xfd) throw Bip174Malformed("taproot derivation leaf count")
        }
        0xfe, 0xff -> throw Bip174Malformed("taproot derivation leaf count")
        else -> first.toLong() to 1
    }
    if (value.size < n * 32 + 5 || (value.size - pos - n * 32) % 4 != 0L ||
        value.size - pos - n * 32 < 4) throw Bip174Malformed("taproot derivation value")
    return bip32Of(key, value.copyOfRange(pos + (n * 32).toInt(), value.size), taproot = true, leafHashes = n.toInt())
}

/** What an input map holds, with the format checks the vault's decoder makes (duplicates, key data, lengths). */
internal class InputMap(entries: List<Bip174Entry>) {
    var nonWitnessUtxo: ByteArray? = null

    /** The `witness_utxo` as btcd reads it: the amount and the script (`value[9:]`; the length byte is not looked at). */
    var witnessUtxo: Pair<Long, ByteArray>? = null
    var sighash: Long = 0
    var finalScriptSig = false
    var finalScriptWitness = false
    var redeemScript = false
    var witnessScript = false
    var partialSigs = 0
    var taprootKeySig = false
    var taprootScriptSigs = 0
    var taprootLeafScripts = 0
    var taprootMerkleRoot = false
    var taprootInternalKey: ByteArray? = null
    private val bip32 = ArrayList<Derivation>()
    private val taproot = ArrayList<Derivation>()
    val derivations: List<Derivation> get() = bip32 + taproot

    init {
        val seen = HashSet<String>()
        for (e in entries) {
            val id = e.type.toString() + ":" + e.keyData.toHex()
            when (e.type) {
                0x00 -> single(e, seen) { nonWitnessUtxo = e.value }
                0x01 -> single(e, seen) {
                    if (e.value.size < 10) throw Bip174Malformed("witness utxo")
                    witnessUtxo = (0 until 8).fold(0L) { acc, i ->
                        acc or ((e.value[i].toLong() and 0xff) shl (8 * i)) } to e.value.copyOfRange(9,
                            e.value.size)
                }
                0x02 -> {
                    if (!validPubkey(e.keyData) || !validDer(e.value) || !seen.add(id)) throw Bip174Malformed("partial signature")
                    partialSigs++
                }
                0x03 -> {
                    // btcd's duplicate test is "the sighash is already non-zero".
                    if (e.hasKeyData || sighash != 0L || e.value.size != 4) throw Bip174Malformed("sighash")
                    sighash = u32le(e.value, 0)
                }
                0x04 -> single(e, seen) { redeemScript = e.value.isNotEmpty() }
                0x05 -> single(e, seen) { witnessScript = e.value.isNotEmpty() }
                0x06 -> {
                    if (!validPubkey(e.keyData)) throw Bip174Malformed("bip32 derivation key")
                    val d = bip32Of(e.keyData, e.value, taproot = false)
                    if (!seen.add(id)) throw Bip174Malformed("duplicate derivation")
                    bip32 += d
                }
                0x07 -> single(e, seen) { finalScriptSig = e.value.isNotEmpty() }
                0x08 -> single(e, seen) { finalScriptWitness = e.value.isNotEmpty() }
                0x13 -> single(e, seen) {
                    if (e.value.size != 64 && e.value.size != 65) throw Bip174Malformed("taproot key signature length")
                    taprootKeySig = true
                }
                0x14 -> {
                    if (e.keyData.size != 64 || (e.value.size != 64 && e.value.size != 65) ||
                        !seen.add(id)) throw Bip174Malformed("taproot script signature")
                    taprootScriptSigs++
                }
                0x15 -> {
                    if (e.value.isEmpty() || e.keyData.size < 33 || (e.keyData.size - 33) % 32 != 0 ||
                        !seen.add(id)) throw Bip174Malformed("taproot leaf script")
                    taprootLeafScripts++
                }
                0x16 -> {
                    if (!validXonly(e.keyData)) throw Bip174Malformed("taproot derivation key")
                    val d = taprootDerivation(e.keyData, e.value)
                    if (!seen.add(id)) throw Bip174Malformed("duplicate taproot derivation")
                    taproot += d
                }
                0x17 -> single(e, seen) {
                    if (!validXonly(e.value)) throw Bip174Malformed("taproot internal key")
                    taprootInternalKey = e.value
                }
                0x18 -> single(e, seen) { taprootMerkleRoot = e.value.isNotEmpty() }
                else -> if (!seen.add("u:$id:" + e.value.toHex())) throw Bip174Malformed("duplicate entry")
            }
        }
    }

    private inline fun single(e: Bip174Entry, seen: MutableSet<String>, f: () -> Unit) {
        if (e.hasKeyData || !seen.add(e.type.toString())) throw Bip174Malformed("entry ${e.type}")
        f()
    }
}

/** An output map: its derivations (BIP32 first, then taproot), and the format checks. */
internal class OutputMap(entries: List<Bip174Entry>) {
    private val bip32 = ArrayList<Derivation>()
    private val taproot = ArrayList<Derivation>()
    val derivations: List<Derivation> get() = bip32 + taproot

    init {
        val seen = HashSet<String>()
        for (e in entries) {
            val id = e.type.toString() + ":" + e.keyData.toHex()
            when (e.type) {
                // redeem script, witness script, taproot internal key, taproot tree: once each, no key data
                0x00, 0x01, 0x05, 0x06 -> {
                    if (e.hasKeyData || !seen.add(e.type.toString())) throw Bip174Malformed("output entry ${e.type}")
                    if (e.type == 0x05 && !validXonly(e.value)) throw Bip174Malformed("taproot internal key")
                }
                0x02 -> {
                    if (!validPubkey(e.keyData)) throw Bip174Malformed("bip32 derivation key")
                    val d = bip32Of(e.keyData, e.value, taproot = false)
                    if (!seen.add(id)) throw Bip174Malformed("duplicate derivation")
                    bip32 += d
                }
                0x07 -> {
                    if (!validXonly(e.keyData)) throw Bip174Malformed("taproot derivation key")
                    val d = taprootDerivation(e.keyData, e.value)
                    if (!seen.add(id)) throw Bip174Malformed("duplicate taproot derivation")
                    taproot += d
                }
                else -> if (!seen.add("u:$id:" + e.value.toHex())) throw Bip174Malformed("duplicate entry")
            }
        }
    }
}
