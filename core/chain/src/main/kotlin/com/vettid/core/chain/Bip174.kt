package com.vettid.core.chain

/**
 * A reader for the key-value maps of a BIP174 (version 0) PSBT, and nothing else. The wallet's policy checks (the vault's, which the
 * app mirrors) look at specific keys; a library's PSBT model hides some of them (taproot script data, a finalised input's partial
 * signatures) and fails on others before a policy reason can be given (a previous transaction that does not match its outpoint).
 * Reading the maps directly keeps the reasons the same as the vault's. What it refuses is what vettid-vault's decoder (btcd
 * `psbt.NewFromRawBytes`) refuses as malformed, as far as the checks here reach: curve-point validity of keys and DER validity of
 * signatures are NOT checked here (the vault checks them; the app never needs the difference).
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
            if (v < 0) throw Bip174Malformed("length")
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

/** A derivation entry of either kind, in the order they stand in the map (BIP32 ones first, then taproot ones, as the vault lists them). */
internal class Derivation(val fingerprint: ByteArray, val path: LongArray, val key: ByteArray, val taproot: Boolean, val leafHashes: Int)

/** What an input map holds, with the format checks the vault's decoder makes (duplicates, key data, lengths). */
internal class InputMap(entries: List<Bip174Entry>) {
    var nonWitnessUtxo: ByteArray? = null
    var witnessUtxo: ByteArray? = null
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
            // Singletons carry no key data and appear once; keyed entries appear once per key. (btcd also refuses a repeated
            // unknown entry only when its value is equal; an unknown entry is not looked at here.)
            when (e.type) {
                0x00 -> single(e, seen) { nonWitnessUtxo = e.value }
                0x01 -> single(e, seen) { witnessUtxo = e.value }
                0x02 -> {
                    if (e.keyData.size != 33 || !seen.add(id)) throw Bip174Malformed("partial signature")
                    partialSigs++
                }
                0x03 -> single(e, seen) {
                    if (e.value.size != 4) throw Bip174Malformed("sighash length")
                    sighash = (0 until 4).fold(0L) { acc, i -> acc or ((e.value[i].toLong() and 0xff) shl (8 * i)) }
                }
                0x04 -> single(e, seen) { redeemScript = e.value.isNotEmpty() }
                0x05 -> single(e, seen) { witnessScript = e.value.isNotEmpty() }
                0x06 -> {
                    if (e.keyData.size != 33 || !seen.add(id)) throw Bip174Malformed("bip32 derivation")
                    bip32 += bip32Of(e.keyData, e.value, taproot = false)
                }
                0x07 -> single(e, seen) { finalScriptSig = e.value.isNotEmpty() }
                0x08 -> single(e, seen) { finalScriptWitness = e.value.isNotEmpty() }
                0x13 -> single(e, seen) {
                    if (e.value.size != 64 && e.value.size != 65) throw Bip174Malformed("taproot key signature length")
                    taprootKeySig = true
                }
                0x14 -> {
                    if (e.keyData.size != 64 || (e.value.size != 64 && e.value.size != 65) || !seen.add(id)) throw Bip174Malformed("taproot script signature")
                    taprootScriptSigs++
                }
                0x15 -> {
                    if (e.value.isEmpty() || e.keyData.size < 33 || (e.keyData.size - 33) % 32 != 0 || !seen.add(id)) throw Bip174Malformed("taproot leaf script")
                    taprootLeafScripts++
                }
                0x16 -> {
                    if (e.keyData.size != 32 || !seen.add(id)) throw Bip174Malformed("taproot derivation")
                    taproot += taprootDerivation(e.keyData, e.value)
                }
                0x17 -> single(e, seen) {
                    if (e.value.size != 32) throw Bip174Malformed("taproot internal key length")
                    taprootInternalKey = e.value
                }
                0x18 -> single(e, seen) { taprootMerkleRoot = e.value.isNotEmpty() }
                else -> Unit
            }
        }
    }

    private inline fun single(e: Bip174Entry, seen: MutableSet<String>, f: () -> Unit) {
        if (e.hasKeyData || !seen.add(e.type.toString())) throw Bip174Malformed("entry ${e.type}")
        f()
    }

    companion object {
        fun bip32Of(key: ByteArray, value: ByteArray, taproot: Boolean, leafHashes: Int = 0): Derivation {
            if (value.size < 4 || value.size % 4 != 0) throw Bip174Malformed("derivation value")
            val fp = value.copyOfRange(0, 4)
            val path = LongArray(value.size / 4 - 1) { i -> u32le(value, 4 * (i + 1)) }
            return Derivation(fp, path, key, taproot, leafHashes)
        }

        fun taprootDerivation(key: ByteArray, value: ByteArray): Derivation {
            if (value.size < 5) throw Bip174Malformed("taproot derivation value")
            var pos = 0
            val first = value[pos++].toInt() and 0xff
            val n: Long = when (first) {
                0xfd -> { val v = (value[1].toLong() and 0xff) or ((value[2].toLong() and 0xff) shl 8); pos = 3; v }
                0xfe, 0xff -> throw Bip174Malformed("taproot derivation leaf count")
                else -> first.toLong()
            }
            if (value.size < n * 32 + pos + 4 || (value.size - pos - n * 32) % 4 != 0L) throw Bip174Malformed("taproot derivation value")
            return bip32Of(key, value.copyOfRange(pos + (n * 32).toInt(), value.size), taproot = true, leafHashes = n.toInt())
        }

        fun u32le(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xff) or ((b[at + 1].toLong() and 0xff) shl 8) or ((b[at + 2].toLong() and 0xff) shl 16) or ((b[at + 3].toLong() and 0xff) shl 24)
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
                0x00, 0x01 -> if (e.hasKeyData || !seen.add(e.type.toString())) throw Bip174Malformed("output entry ${e.type}")
                0x02 -> {
                    if (e.keyData.size != 33 || !seen.add(id)) throw Bip174Malformed("bip32 derivation")
                    bip32 += InputMap.bip32Of(e.keyData, e.value, taproot = false)
                }
                0x05 -> if (e.hasKeyData || e.value.size != 32 || !seen.add("5")) throw Bip174Malformed("taproot internal key")
                0x07 -> {
                    if (e.keyData.size != 32 || !seen.add(id)) throw Bip174Malformed("taproot derivation")
                    taproot += InputMap.taprootDerivation(e.keyData, e.value)
                }
                else -> Unit
            }
        }
    }
}
