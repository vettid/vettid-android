package com.vettid.core.chain

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Protocol
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The txid the client checks raw transactions and broadcasts against: double SHA-256 of the bytes without witness data. */
class TxIdsTest {
    @Test
    fun theGenesisCoinbaseHasItsWellKnownTxid() {
        assertEquals(EsploraFixtures.RAW_TXID, txidOf(EsploraFixtures.RAW_TX))
    }

    @Test
    fun aSegwitTransactionsTxidLeavesTheWitnessOut() {
        fun tx(witness: ScriptWitness) = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(TxId(ByteVector32("11".repeat(32))), 1), ByteVector.empty, 0xfffffffdL, witness)),
            txOut = listOf(TxOut(Satoshi(50_000), ByteVector("0014751e76e8199196d454941c45d1b3a323f1433bd6"))),
            lockTime = 0,
        )
        val signed = Transaction.write(tx(ScriptWitness(listOf(ByteVector("3044"), ByteVector("02aa")))), Protocol.PROTOCOL_VERSION)
        val bare = Transaction.write(tx(ScriptWitness.empty), Protocol.PROTOCOL_VERSION)
        assertNotEquals(signed.toHex(), bare.toHex())
        assertEquals(txidOf(bare), txidOf(signed))
        assertEquals(64, txidOf(signed)!!.length)
    }

    @Test
    fun bytesThatAreNotExactlyOneTransactionHaveNone() {
        assertNull(txidOf(EsploraFixtures.RAW_TX + byteArrayOf(0)))
        assertNull(txidOf(byteArrayOf(1, 2, 3)))
        assertNull(txidOf(ByteArray(0)))
    }
}
