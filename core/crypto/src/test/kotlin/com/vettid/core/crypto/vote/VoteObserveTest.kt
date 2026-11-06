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
import org.junit.Assert.assertTrue
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

    private companion object {
        const val PID = "01JBQZ0000000000000000000A"
        const val OTHER = "01JBQZ0000000000000000000B"
    }

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
        VoteRows.read(res, rowsBytes).observe(PID)
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
        VoteRows.read(docs.str("results").toByteArray(), docs.str("rows").toByteArray()).observe(PID)
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

    /** Observes [rowsBytes] as given (rebuilt results around them): for the shape of rows.json itself. */
    private fun observeRaw(rowsBytes: ByteArray, resultsEdit: (String) -> String = { it }) {
        val res = String(resultsJson(vectorRows, rowsBytes))
        VoteRows.read(resultsEdit(res).toByteArray(), rowsBytes).observe(PID)
    }

    @Test
    fun rowsThatAreNotExactlyTheDocumentOrNameAnotherProposalAreReported() {
        val rows = String(rowsJson(vectorRows))
        observeRaw(rows.toByteArray())
        // Another proposal named by rows.json, or by results.json.
        assertEquals("rows_shape", reason { observeRaw(rows.replace(PID, OTHER).toByteArray()) })
        assertEquals("proposal_id", reason { observeRaw(rows.toByteArray()) { it.replace(PID, OTHER) } })
        // A member too many, one missing, or v not 1.
        assertEquals("rows_shape", reason { observeRaw(rows.replace(",\"v\":1}", ",\"v\":1,\"x\":0}").toByteArray()) })
        assertEquals("rows_shape", reason { observeRaw(rows.replace(",\"v\":1}", "}").toByteArray()) })
        assertEquals("rows_shape", reason { observeRaw(rows.replace(",\"v\":1}", ",\"v\":2}").toByteArray()) })
        assertEquals("rows_shape", reason { observeRaw(rows.replace(",\"v\":1}", ",\"v\":\"1\"}").toByteArray()) })
    }

    @Test
    fun rowsOverTheCapOrNestedTooDeepAreNotRead() {
        assertEquals(3 * 512L + 4_096, VoteRows.rowsCap(3))
        assertEquals(VoteRows.ROWS_CAP_MAX, VoteRows.rowsCap(100_000))
        assertEquals(51_204_096L, VoteRows.rowsCap(VoteRows.MAX_ROWS.toLong()))
        assertEquals(3 * 512L + 4_096, VoteRows.rowsCap(docs.str("results").toByteArray()))
        assertEquals(null, VoteRows.rowsCap("{}".toByteArray()))
        // White space is allowed (rows.json need not be JCS): padded to one byte over the cap, it is not read.
        val rows = String(rowsJson(vectorRows))
        val cap = VoteRows.rowsCap(vectorRows.size.toLong()).toInt()
        val atCap = rows.dropLast(1) + " ".repeat(cap - rows.length) + "}"
        observeRaw(atCap.toByteArray())
        val over = rows.dropLast(1) + " ".repeat(cap - rows.length + 1) + "}"
        assertEquals("rows_size", reason { observeRaw(over.toByteArray()) })
        // A fifth level of nesting (an object inside a ballot) is not read.
        val deep = rows.replace("\"choice_id\":\"yes\"", "\"choice_id\":\"yes\",\"x\":{}")
        assertEquals("rows", reason { observeRaw(deep.toByteArray()) })
    }

    @Test
    fun thePublishedTallyAndCountsAreReadForTheVaultsComparison() {
        val p = VoteRows.read(docs.str("results").toByteArray(), docs.str("rows").toByteArray())
        assertEquals(mapOf("abstain" to 0L, "no" to 1L, "yes" to 1L), p.tally())
        assertEquals(PublishedCounts(eligible = 3, ballots = 2, removed = 1, unhandled = 0), p.counts())
    }

    @Test
    fun aRowsHashThatDiffersIsReportedNotFatal() {
        val rowsBytes = rowsJson(vectorRows)
        val hash = Base64s.encodeStd(Bytes.sha256(rowsBytes))
        val res = String(resultsJson(vectorRows, rowsBytes)).replace(hash, Base64s.encodeStd(ByteArray(32)))
        val p = VoteRows.read(res.toByteArray(), rowsBytes)
        assertEquals(1L, p.ownRow(vectorRows[1].tag).index)
        assertEquals("rows_hash", reason { p.observe(PID) })
    }

    @Test
    fun rowsOutOfOrderOrRepeatedAreReportedAndTheOwnRowIsTheFirstUnderItsTag() {
        // The first two rows swapped: the tree is made over the rows as published, and the row is still had with its proof.
        val swapped = listOf(vectorRows[1], vectorRows[0], vectorRows[2])
        val rowsBytes = rowsJson(swapped)
        val res = resultsJson(swapped, rowsBytes)
        val p = VoteRows.read(res, rowsBytes)
        val own = p.ownRow(vectorRows[0].tag)
        assertEquals(1L, own.index)
        val root = Base64s.decodeStd(StrictJson.parseObject(res).string("rows_root"))
        assertTrue(VoteMerkle.verify(root, leaf(vectorRows[0]), own.index, own.treeSize, own.path))
        assertEquals("rows_order", reason { p.observe(PID) })
        // A tag twice: the first row under it is the member's.
        val twice = listOf(vectorRows[0], vectorRows[0].copy(vk = null, choice = null, sig = null), vectorRows[2])
        val twiceBytes = rowsJson(twice)
        val p2 = VoteRows.read(resultsJson(twice, twiceBytes, mapOf("yes" to 1L, "no" to 0L, "abstain" to 0L)), twiceBytes)
        assertEquals(0L, p2.ownRow(vectorRows[0].tag).index)
        assertTrue(p2.ownRow(vectorRows[0].tag).ballot != null)
        assertEquals("rows_order", reason { p2.observe(PID) })
    }

    @Test
    fun ballotsOfOtherLengthsAreReportedAndStillMadeIntoLeaves() {
        val longVk = vectorRows[0].copy(vk = ByteArray(33) { 3 })
        val rows = listOf(longVk, vectorRows[1], vectorRows[2])
        val rowsBytes = rowsJson(rows)
        val p = VoteRows.read(resultsJson(rows, rowsBytes), rowsBytes)
        assertEquals(33, p.ownRow(longVk.tag).ballot!!.vk.size)
        assertEquals("ballot_shape", reason { p.observe(PID) })
        for (bad in listOf(vectorRows[0].copy(sig = ByteArray(65)), vectorRows[0].copy(choice = "y".repeat(33)))) {
            val r = listOf(bad, vectorRows[1], vectorRows[2])
            val b = rowsJson(r)
            val tally = if (bad.choice == "yes") defaultTally else mapOf("yes" to 0L, "no" to 1L, "abstain" to 0L)
            assertEquals("ballot_shape", reason { VoteRows.read(resultsJson(r, b, tally), b).observe(PID) })
        }
    }

    @Test
    fun whatStopsTheSubmitIsStillRefusedByRead() {
        val rowsBytes = rowsJson(vectorRows)
        val res = resultsJson(vectorRows, rowsBytes)
        // Leaves that do not reach rows_root: the vault could not take the row's proof.
        val otherRoot = String(res).replace(Base64s.encodeStd(VoteMerkle.root(vectorRows.map(::leaf))), Base64s.encodeStd(ByteArray(32)))
        assertEquals("rows_root", reason { VoteRows.read(otherRoot.toByteArray(), rowsBytes) })
        // Fewer rows than counts.eligible.
        val fewer = rowsJson(vectorRows.dropLast(1))
        assertEquals("rows_count", reason { VoteRows.read(res, fewer) })
    }
}
