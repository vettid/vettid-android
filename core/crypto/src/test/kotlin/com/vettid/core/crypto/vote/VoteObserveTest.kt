package com.vettid.core.crypto.vote

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Edwards
import com.vettid.core.crypto.VectorDoc
import com.vettid.core.crypto.json.JsonNull
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import com.vettid.core.crypto.rep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

/**
 * The observer's check of the published results ([PublishedResults.observe], BALLOT-PROTOCOL §8.4 items 2 to 4) against
 * the shared `vote.json` documents, and one changed result per check. Each changed document is rebuilt around its change
 * (`rows_hash`, `rows_root`, `counts.eligible` made again) so that only the check under test can catch it.
 */
class VoteObserveTest {
    private val v = VectorDoc.load("vote.json")
    private val docs = v.sub("documents")
    private val results = StrictJson.parseObject(docs.str("results"))
    private val proposalDoc = results.obj("proposal").base64("doc")
    private val proposalHash = Bytes.sha256(proposalDoc)
    private val label = "vettid/vote/1/ballot\u0000"

    /** A row as the test builds it: the tag and the ballot (null for an empty row). */
    private data class Row(val tag: ByteArray, val vk: ByteArray?, val choice: String?, val sig: ByteArray?)

    private val vectorRows: List<Row> = StrictJson.parseObject(docs.str("rows")).array("rows").map { e ->
        val o = e.asObject()
        val b = o["ballot"]
        if (b == null || b is JsonNull) {
            Row(o.base64("record_tag", 32), null, null, null)
        } else {
            val ballot = b.asObject()
            Row(o.base64("record_tag", 32), ballot.base64("vk"), ballot.string("choice_id"), ballot.base64("ballot_sig"))
        }
    }

    private val defaultTally = mapOf("yes" to 1L, "no" to 1L, "abstain" to 0L)

    private fun rowsJson(rows: List<Row>): ByteArray = buildString {
        append("{\"proposal_id\":\"01JBQZ0000000000000000000A\",\"rows\":[")
        rows.forEachIndexed { i, r ->
            if (i > 0) append(',')
            append("{\"ballot\":")
            if (r.vk == null) {
                append("null")
            } else {
                append("{\"ballot_sig\":\"${Base64s.encodeStd(r.sig!!)}\",\"choice_id\":\"${r.choice}\",")
                append("\"vk\":\"${Base64s.encodeStd(r.vk)}\"}")
            }
            append(",\"record_tag\":\"${Base64s.encodeStd(r.tag)}\"}")
        }
        append("],\"v\":1}")
    }.toByteArray()

    private fun leaf(r: Row): ByteArray = if (r.vk == null) {
        r.tag
    } else {
        Bytes.concat(r.tag, VoteRows.lp(r.vk), VoteRows.lp(r.choice!!.toByteArray()), VoteRows.lp(r.sig!!))
    }

    /** results.json around [rowsBytes], keeping the vector's proposal and extensions. */
    private fun resultsJson(
        rows: List<Row>,
        rowsBytes: ByteArray,
        tally: Map<String, Long> = defaultTally,
        ballots: Long = rows.count { it.vk != null }.toLong(),
    ): ByteArray {
        val t = tally.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        return (
            "{\"counts\":{\"ballots\":$ballots,\"eligible\":${rows.size},\"removed\":1,\"unhandled\":0}," +
                "\"extensions\":${results["extensions"]!!.raw},\"proposal\":${results["proposal"]!!.raw}," +
                "\"proposal_id\":\"01JBQZ0000000000000000000A\"," +
                "\"rows_hash\":\"${Base64s.encodeStd(Bytes.sha256(rowsBytes))}\"," +
                "\"rows_root\":\"${Base64s.encodeStd(VoteMerkle.root(rows.map(::leaf)))}\"," +
                "\"tally\":{$t},\"v\":1}"
            ).toByteArray()
    }

    private fun observe(rows: List<Row>, tally: Map<String, Long> = defaultTally, ballots: Long? = null) {
        val rowsBytes = rowsJson(rows)
        val res = if (ballots == null) resultsJson(rows, rowsBytes, tally) else resultsJson(rows, rowsBytes, tally, ballots)
        VoteRows.read(res, rowsBytes).observe()
    }

    private fun reason(block: () -> Unit): String = assertThrows(ResultsProblem::class.java) { block() }.reason

    /** A ballot for [tag] signed by a test key (seed [seedByte]), over the vector proposal. */
    private fun signed(tag: ByteArray, choice: String, seedByte: Int = 0x41): Row {
        val k = Ed25519PrivateKey.fromSeed(rep(seedByte, 32))
        val msg = Bytes.concat(proposalHash, tag, VoteRows.lp(choice.toByteArray()))
        return Row(tag, k.publicKey, choice, k.sign(label, msg))
    }

    private fun ballotMsg(r: Row) = Bytes.concat(proposalHash, r.tag, VoteRows.lp(r.choice!!.toByteArray()))

    @Test
    fun theSharedResultsPassTheWholeCheck() {
        VoteRows.read(docs.str("results").toByteArray(), docs.str("rows").toByteArray()).observe()
        // The rebuilt documents of the unchanged rows pass too: the builder adds nothing of its own.
        observe(vectorRows)
        // A ballot re-signed by another key, for the same choice, is as good.
        observe(listOf(signed(vectorRows[0].tag, "yes"), vectorRows[1], vectorRows[2]))
    }

    @Test
    fun aTallyOffByOneIsAProblem() {
        assertEquals("tally", reason { observe(vectorRows, mapOf("yes" to 2L, "no" to 1L, "abstain" to 0L)) })
        assertEquals("tally", reason { observe(vectorRows, mapOf("yes" to 1L, "no" to 0L, "abstain" to 0L)) })
        // A tally that leaves out a choice, or names one the proposal lacks.
        assertEquals("tally", reason { observe(vectorRows, mapOf("yes" to 1L, "no" to 1L)) })
        assertEquals("tally", reason { observe(vectorRows, defaultTally + ("maybe" to 0L)) })
    }

    @Test
    fun ballotsOffIsAProblem() {
        assertEquals("ballots", reason { observe(vectorRows, ballots = 3) })
        assertEquals("ballots", reason { observe(vectorRows, ballots = 1) })
    }

    @Test
    fun aRowMissingOrExtraOrAnotherTagIsAProblem() {
        // One row fewer, or one more, than the proposal's eligible members.
        assertEquals("eligible", reason { observe(vectorRows.dropLast(1)) })
        val extra = Row(ByteArray(32) { 0xf0.toByte() }, null, null, null)
        assertEquals("eligible", reason { observe(vectorRows + extra) })
        // As many rows, but one tag the proposal's record_root does not have.
        assertEquals("record_root", reason { observe(vectorRows.dropLast(1) + extra) })
    }

    @Test
    fun anUnknownChoiceIsAProblem() {
        // Validly signed by its own key: only the choice is wrong.
        val rows = listOf(signed(vectorRows[0].tag, "maybe"), vectorRows[1], vectorRows[2])
        assertEquals("choice_id", reason { observe(rows) })
    }

    @Test
    fun aBadSignatureIsAProblem() {
        val r = vectorRows[1]
        val flipped = r.copy(sig = r.sig!!.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() })
        assertEquals("ballot_sig", reason { observe(listOf(vectorRows[0], flipped, vectorRows[2])) })
        // Signed for another tag (another member's row).
        val moved = signed(vectorRows[0].tag, "no").copy(tag = vectorRows[1].tag)
        assertEquals("ballot_sig", reason { observe(listOf(vectorRows[0], moved, vectorRows[2])) })
    }

    @Test
    fun aNonCanonicalSIsAProblem() {
        val r = vectorRows[0]
        val s = BigInteger(1, r.sig!!.copyOfRange(32, 64).reversedArray()).add(Edwards.L)
        val bad = r.copy(sig = r.sig.copyOf(32) + Edwards.le(s))
        assertEquals("ballot_sig", reason { observe(listOf(bad, vectorRows[1], vectorRows[2])) })
    }

    @Test
    fun aSmallOrderKeyOrRIsAProblem() {
        // A small-order key from the shared vectors, with its signature.
        val small = v.o.array("ed25519").map { it.asObject() }.first { it.string("case") == "small-order key and R 0" }
        val keyRow = vectorRows[0].copy(vk = Bytes.unhex(small.string("key")), sig = Bytes.unhex(small.string("sig")))
        assertEquals("ballot_sig", reason { observe(listOf(keyRow, vectorRows[1], vectorRows[2])) })
        // A good key with R the identity: [S]B = R + [k]A holds, and only the small-order rule refuses it.
        val seed = rep(0x52, 32)
        val pub = Ed25519PrivateKey.fromSeed(seed).publicKey
        val base = Row(vectorRows[0].tag, pub, "yes", null)
        val lm = label.toByteArray(Charsets.US_ASCII) + ballotMsg(base)
        val sig = Edwards.sign(Edwards.scalar(seed), pub, lm, BigInteger.ZERO, Edwards.IDENTITY)
        assertEquals("ballot_sig", reason { observe(listOf(base.copy(sig = sig), vectorRows[1], vectorRows[2])) })
    }

    @Test
    fun aMixedOrderKeyWhoseEquationHoldsOnlyTimesEightIsAProblem() {
        val small = v.o.array("ed25519").map { it.asObject() }.first { it.string("case") == "small-order key and R 3" }
        val seed = rep(0x53, 32)
        val a = Edwards.scalar(seed)
        val mixed = Edwards.encode(Edwards.add(Edwards.mul(a, Edwards.BASE), Edwards.decode(Bytes.unhex(small.string("key")))!!))
        val base = Row(vectorRows[0].tag, mixed, "yes", null)
        val lm = label.toByteArray(Charsets.US_ASCII) + ballotMsg(base)
        fun sigWhere(holds: Boolean): ByteArray {
            var r = BigInteger.ONE
            while (true) {
                val sig = Edwards.sign(a, mixed, lm, r)
                val k = Edwards.challenge(sig.copyOf(32), mixed, lm)
                if ((k.mod(BigInteger.valueOf(8)).signum() == 0) == holds) return sig
                r = r.add(BigInteger.ONE)
            }
        }
        assertEquals("ballot_sig", reason { observe(listOf(base.copy(sig = sigWhere(false)), vectorRows[1], vectorRows[2])) })
        // §2 accepts a mixed-order key whose equation holds without the factor 8: so does the observer.
        observe(listOf(base.copy(sig = sigWhere(true)), vectorRows[1], vectorRows[2]))
    }

    @Test
    fun theFourInputsAreStillReadFirst() {
        // read() refuses what it refused before; observe() is never reached.
        val rowsBytes = rowsJson(vectorRows)
        val res = resultsJson(vectorRows, rowsBytes)
        val changed = rowsBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertEquals("rows_hash", reason { VoteRows.read(res, changed) })
    }
}
