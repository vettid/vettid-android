package com.vettid.core.crypto.vote

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.VectorDoc
import com.vettid.core.crypto.assertBytes
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import com.vettid.core.crypto.json.asString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vote's Merkle tree and the member's row of the published results (BALLOT-PROTOCOL §8.2) against vettid-vault's
 * `testdata/vote/vectors.json` (`vote.json`, see SOURCE.md), byte for byte.
 */
class VoteRowsTest {
    private val v = VectorDoc.load("vote.json")
    private val docs = v.sub("documents")

    @Test
    fun rootsAndProofsMatchTheVectors() {
        for (case in v.o.array("merkle")) {
            val m = case.asObject()
            val leaves = m.array("leaves").map { Bytes.unhex(it.asString()) }
            assertBytes("root of ${leaves.size}", Bytes.unhex(m.string("root")), VoteMerkle.root(leaves))
            val proofs = m.optArray("proofs").orEmpty()
            for ((i, p) in proofs.withIndex()) {
                val want = (p as com.vettid.core.crypto.json.JsonArray).items.map { Bytes.unhex(it.asString()) }
                val got = VoteMerkle.proof(leaves, i)!!
                assertEquals(want.size, got.size)
                want.indices.forEach { assertBytes("proof $i/$it", want[it], got[it]) }
                assertTrue(VoteMerkle.verify(Bytes.unhex(m.string("root")), leaves[i], i.toLong(), leaves.size.toLong(), got))
            }
        }
    }

    @Test
    fun aProofForAnotherLeafOrSizeFails() {
        val leaves = (0 until 5).map { byteArrayOf(it.toByte()) }
        val root = VoteMerkle.root(leaves)
        val p = VoteMerkle.proof(leaves, 2)!!
        assertTrue(VoteMerkle.verify(root, leaves[2], 2, 5, p))
        assertFalse(VoteMerkle.verify(root, leaves[3], 2, 5, p))
        assertFalse(VoteMerkle.verify(root, leaves[2], 3, 5, p))
        assertFalse(VoteMerkle.verify(root, leaves[2], 2, 5, p.dropLast(1)))
        assertFalse(VoteMerkle.verify(root, leaves[2], 5, 5, p))
        assertNull(VoteMerkle.proof(leaves, 5))
    }

    private fun rows() = StrictJson.parseObject(docs.str("rows")).array("rows").map { it.asObject() }

    @Test
    fun everyMembersRowIsFoundWithAProofTheVaultAccepts() {
        val results = docs.str("results").toByteArray()
        val rowsBytes = docs.str("rows").toByteArray()
        val rootB64 = StrictJson.parseObject(results).string("rows_root")
        val root = Base64s.decodeStd(rootB64)
        for ((i, row) in rows().withIndex()) {
            val tag = row.base64("record_tag", 32)
            val own = VoteRows.ownRow(results, rowsBytes, tag)
            assertEquals(i.toLong(), own.index)
            assertEquals(3L, own.treeSize)
            assertEquals(row["ballot"]?.let { it !is com.vettid.core.crypto.json.JsonNull }, own.ballot != null)
            assertEquals(32, root.size)
        }
    }

    @Test
    fun theMembersOwnTagIsInTheRowsAndAnUnknownTagIsNot() {
        val results = docs.str("results").toByteArray()
        val rowsBytes = docs.str("rows").toByteArray()
        val own = VoteRows.ownRow(results, rowsBytes, rows()[1].base64("record_tag", 32))
        val root = Base64s.decodeStd(StrictJson.parseObject(results).string("rows_root"))
        assertEquals(1L, own.index)
        assertEquals(3L, own.treeSize)
        assertTrue(own.path.isNotEmpty() && root.size == 32)
        assertEquals("no_row", assertThrows(ResultsProblem::class.java) { VoteRows.ownRow(results, rowsBytes, ByteArray(32) { 7 }) }.reason)
    }

    @Test
    fun rowsThatAreNotWhatTheResultsNameAreAProblem() {
        val results = docs.str("results").toByteArray()
        val rowsBytes = docs.str("rows").toByteArray()
        val tag = v.hex("record_tag")
        // Any changed byte of rows.json: its hash is not rows_hash.
        val changed = rowsBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertEquals("rows_hash", assertThrows(ResultsProblem::class.java) { VoteRows.ownRow(results, changed, tag) }.reason)
        // Results that are not a document.
        assertEquals("results", assertThrows(ResultsProblem::class.java) { VoteRows.ownRow("{}".toByteArray(), rowsBytes, tag) }.reason)
        assertEquals("results", assertThrows(ResultsProblem::class.java) { VoteRows.ownRow("nope".toByteArray(), rowsBytes, tag) }.reason)
    }
}
