package com.vettid.core.chain

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Protocol
import fr.acinq.bitcoin.Transaction

/**
 * The txid of a serialised transaction, in display order: the double SHA-256 of its bytes without witness data. Null when [bytes]
 * are not exactly one transaction (a parse failure, or bytes that do not serialise back to themselves, such as trailing bytes).
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun txidOf(bytes: ByteArray): String? {
    val tx = try {
        Transaction.read(bytes, Protocol.PROTOCOL_VERSION)
    } catch (e: Exception) {
        return null
    }
    if (!Transaction.write(tx, Protocol.PROTOCOL_VERSION).contentEquals(bytes)) return null
    val stripped = Transaction.write(tx, Protocol.PROTOCOL_VERSION or Transaction.SERIALIZE_TRANSACTION_NO_WITNESS)
    return Crypto.hash256(stripped).toDisplayHex()
}

/** A serialised transaction, for the modules that must not see bitcoin-kmp: its id and its hex form. */
object Tx {
    /** The txid in display order; null when [bytes] are not exactly one transaction. */
    fun id(bytes: ByteArray): String? = txidOf(bytes)

    fun toHex(bytes: ByteArray): String = bytes.toHex()

    /** The bytes of lower or upper case hex; null for text that is not hex of even length. */
    fun fromHex(hex: String): ByteArray? =
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it in "0123456789abcdefABCDEF" }) null else hex.hexToBytes()
}
