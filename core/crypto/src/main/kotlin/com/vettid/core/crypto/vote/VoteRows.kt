package com.vettid.core.crypto.vote

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.json.JsonString
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import com.vettid.core.crypto.json.asUint

/**
 * A member's row of a vote's published results (BALLOT-PROTOCOL §8.2, §8.5): the first row `rows.json` holds under the
 * member's record tag, with its inclusion proof to the results' `rows_root`. [ballot] is the row's ballot as the site has
 * it, or null for an empty row.
 */
class OwnRow(val ballot: OwnBallot?, val index: Long, val treeSize: Long, val path: List<ByteArray>)

/** A row's ballot, decoded: `vk`, `choice_id` (its UTF-8 bytes) and `ballot_sig`. */
class OwnBallot(val vk: ByteArray, val choiceId: ByteArray, val ballotSig: ByteArray) {
    val choice: String get() = String(choiceId, Charsets.UTF_8)
}

/** Why the site's results could not be taken as they are: said to the member as a problem with the published results. */
class ResultsProblem(val reason: String) : Exception(reason)

/**
 * A vote's published results read from the site's bytes ([VoteRows.read]): `results.json` and `rows.json` as [RowsTable]
 * (no text of rows.json is kept). [ownRow] is the member's row for `vote.results.submit`; [observe] is the rest of the
 * observer's check.
 */
class PublishedResults internal constructor(
    private val results: JsonObject,
    private val rows: RowsTable,
    /** What the observer only reports (review 1, finding 5): it does not stop the submit. */
    private val rowsHashMatches: Boolean,
    private val sortedOnce: Boolean,
    private val lengthsMatch: Boolean,
) {
    /** The first row of [recordTag] with its proof. Throws [ResultsProblem] (`no_row`) when the tag has no row. */
    fun ownRow(recordTag: ByteArray, checkpoint: () -> Unit = {}): OwnRow {
        var found = -1
        for (i in 0 until rows.count) {
            if (Bytes.constantTimeEquals(rows.tag(i), recordTag)) {
                found = i
                break
            }
        }
        if (found < 0) throw ResultsProblem("no_row")
        val path = VoteMerkle.proofOfHashes(rows.leafHashes, rows.count, found, checkpoint) ?: throw ResultsProblem("rows")
        val own = rows.ownBallot.takeIf { found == rows.ownIndex }
        val ballot = when {
            !rows.hasBallot(found) -> null
            own != null -> OwnBallot(own.first, own.second, own.third)
            rows.kept(found) -> OwnBallot(rows.vk(found), rows.choice(found), rows.sig(found))
            // A ballot of other lengths is kept only for the tag the rows were read for (VoteRows.read's ownTag).
            else -> throw ResultsProblem("rows")
        }
        return OwnRow(ballot, found.toLong(), rows.count.toLong(), path)
    }

    /** The bytes held for the rows (for the memory bound: review 1, finding 2). */
    fun retainedRowBytes(): Long = rows.retainedBytes()

    /**
     * The observer's check of the rows against the proposal the results carry (BALLOT-PROTOCOL §8.4 items 2 to 4; VAULT-
     * MESSAGING 0.86.4 §10.19.6 step 1): the tags number the proposal's `eligible` and reach its `record_root`; every
     * ballot's `choice_id` is one of the proposal's choices and its `ballot_sig` verifies under its `vk` by the four rules
     * of BALLOT-PROTOCOL §2 ([Ed25519.verifyStrict]); the `tally` per choice and `counts.ballots` recompute. Throws
     * [ResultsProblem] at the first difference, cheapest first and the signatures last; [checkpoint] is called every 256
     * rows and tree nodes, so a long check can be cancelled. The signatures, the anchors and the proposal's own checks are
     * the vault's (§10.19.6 step 2); this never decides anything for it.
     */
    @Suppress("ThrowsCount", "CyclomaticComplexMethod", "LongMethod")
    fun observe(proposalId: String, checkpoint: () -> Unit = {}) {
        // Only reported, never a reason not to submit (§10.19.6 step 1): the documents name this proposal, and rows.json is
        // exactly {v: 1, proposal_id, rows}.
        if ((results["proposal_id"] as? JsonString)?.value != proposalId) throw ResultsProblem("proposal_id")
        if (!rows.documentExact || rows.proposalId != proposalId) throw ResultsProblem("rows_shape")
        // rows.json is the one rows_hash names, sorted by tag with each tag once, its ballots of the protocol's lengths.
        if (!rowsHashMatches) throw ResultsProblem("rows_hash")
        if (!sortedOnce) throw ResultsProblem("rows_order")
        if (!lengthsMatch) throw ResultsProblem("ballot_shape")
        // Every row exactly {record_tag, ballot} and every ballot exactly {vk, choice_id, ballot_sig} (BALLOT-PROTOCOL §8.5:
        // a verifier refuses unknown fields; `pad` belongs to message bodies, not to rows.json; review 1, finding 8).
        if (!rows.membersExact) throw ResultsProblem("rows_members")
        val proposalBytes: ByteArray
        val choices: Set<String>
        val recordRoot: ByteArray
        val eligible: Long
        val tally: JsonObject
        val ballotsCount: Long
        try {
            proposalBytes = results.obj("proposal").base64("doc")
            if (proposalBytes.size > VoteRows.MAX_PROPOSAL) throw ResultsProblem("proposal")
            val proposal = StrictJson.parseObject(proposalBytes)
            val list = proposal.array("choices")
            if (list.size > VoteRows.MAX_CHOICES) throw ResultsProblem("proposal")
            choices = list.map { it.asObject().string("id") }.toSet()
            if (choices.size != list.size) throw ResultsProblem("proposal")
            recordRoot = proposal.base64("record_root", VoteMerkle.HASH)
            eligible = proposal.uint("eligible", 1, VoteRows.MAX_ROWS.toLong())
            tally = results.obj("tally")
            ballotsCount = results.obj("counts").uint("ballots", 0, VoteRows.MAX_ROWS.toLong())
        } catch (_: CryptoException) {
            throw ResultsProblem("proposal")
        }
        // The tags are those the signed proposal fixed before anyone voted: as many, and the same tree (§8.2 record leaf).
        if (rows.count.toLong() != eligible) throw ResultsProblem("eligible")
        // The choices and the counts, from the rows alone.
        val counted = HashMap<String, Long>()
        for (i in 0 until rows.count) {
            if (!rows.hasBallot(i)) continue
            val id = String(rows.choice(i), Charsets.UTF_8)
            if (id !in choices) throw ResultsProblem("choice_id")
            counted[id] = (counted[id] ?: 0L) + 1
        }
        if (tally.members.keys != choices) throw ResultsProblem("tally")
        for (id in choices) {
            val n = try {
                tally.members.getValue(id).asUint(0, VoteRows.MAX_ROWS.toLong())
            } catch (_: CryptoException) {
                throw ResultsProblem("tally")
            }
            if (n != (counted[id] ?: 0L)) throw ResultsProblem("tally")
        }
        if (ballotsCount != counted.values.sum()) throw ResultsProblem("ballots")
        val tagHashes = ByteArray(rows.count * VoteMerkle.HASH)
        for (i in 0 until rows.count) VoteMerkle.leafHash(rows.tag(i)).copyInto(tagHashes, i * VoteMerkle.HASH)
        val root = VoteMerkle.rootOfHashes(tagHashes, rows.count, checkpoint)
        if (!Bytes.constantTimeEquals(root, recordRoot)) throw ResultsProblem("record_root")
        // Every ballot under its own key (§5): ballot_msg = proposal_hash || record_tag || lp(choice_id).
        val proposalHash = Bytes.sha256(proposalBytes)
        for (i in 0 until rows.count) {
            if (i % CHECK_EVERY == 0) checkpoint()
            if (!rows.hasBallot(i)) continue
            val msg = Bytes.concat(proposalHash, rows.tag(i), VoteRows.lp(rows.choice(i)))
            if (!Ed25519.verifyStrict(rows.vk(i), VoteRows.BALLOT_LABEL, msg, rows.sig(i))) throw ResultsProblem("ballot_sig")
        }
    }

    /** The published `tally`, to compare with the vault's own (`vote.get`). Throws [ResultsProblem] when it does not read. */
    fun tally(): Map<String, Long> = try {
        results.obj("tally").members.mapValues { it.value.asUint(0, VoteRows.MAX_ROWS.toLong()) }
    } catch (_: CryptoException) {
        throw ResultsProblem("tally")
    }

    /** The published `counts` (eligible, ballots, removed, unhandled). Throws [ResultsProblem] when they do not read. */
    fun counts(): PublishedCounts = try {
        val c = results.obj("counts")
        val max = StrictJson.MAX_SAFE_INTEGER
        PublishedCounts(c.uint("eligible", 0, max), c.uint("ballots", 0, max), c.uint("removed", 0, max), c.uint("unhandled", 0, max))
    } catch (_: CryptoException) {
        throw ResultsProblem("counts")
    }

    private companion object {
        const val CHECK_EVERY = 256
    }
}

/** The `counts` of `results.json` (BALLOT-PROTOCOL §8.5). */
data class PublishedCounts(val eligible: Long, val ballots: Long, val removed: Long, val unhandled: Long)

/**
 * The part of the observer's check (BALLOT-PROTOCOL §8.4) the app does from the site's bytes for `vote.results.submit`
 * (VAULT-MESSAGING §10.19.6 step 1): the hash of `rows.json`, the rows in tag order, the tree they make against
 * `rows_root`, and the member's row ([read], [ownRow]); and the rest of the observer's check ([PublishedResults.observe]).
 * The signature, the anchor and the verdict are the vault's; it never relies on this.
 */
object VoteRows {
    /** The most rows read: a proposal's `eligible` is at most 100,000 (VAULT-MESSAGING 0.83.3 §10.19.4 step 2). */
    const val MAX_ROWS = 100_000

    /** The decoded proposal is at most 65,536 bytes, with at most 17 choices (§10.19.4 step 2). */
    internal const val MAX_PROPOSAL = 65_536
    internal const val MAX_CHOICES = 17

    /**
     * The most bytes of `rows.json` (VAULT-MESSAGING 0.86.4 §10.19.6 step 1): 512 bytes a row of `counts.eligible` plus 4,096,
     * never more than [ROWS_CAP_MAX]. The download is capped the same way.
     */
    fun rowsCap(eligible: Long): Long = minOf(ROW_BYTES * eligible.coerceIn(0, MAX_ROWS.toLong()) + ROWS_SLACK, ROWS_CAP_MAX)

    /** [rowsCap] for the `counts.eligible` of [resultsBytes]; null when it does not read (the results are then a problem). */
    fun rowsCap(resultsBytes: ByteArray): Long? = try {
        rowsCap(StrictJson.parseObject(resultsBytes).obj("counts").uint("eligible", 1, MAX_ROWS.toLong()))
    } catch (_: CryptoException) {
        null
    }

    private const val ROW_BYTES = 512L
    private const val ROWS_SLACK = 4_096L
    const val ROWS_CAP_MAX = 51_204_096L

    /** `"vettid/vote/1/ballot\x00"` (BALLOT-PROTOCOL §5). */
    internal const val BALLOT_LABEL = "vettid/vote/1/ballot\u0000"

    /**
     * Checks [rowsBytes] against [resultsBytes] and returns the row of [recordTag] with its proof. Throws [ResultsProblem]
     * for anything that does not hold, including a tag that has no row.
     */
    fun ownRow(resultsBytes: ByteArray, rowsBytes: ByteArray, recordTag: ByteArray): OwnRow =
        read(resultsBytes, rowsBytes, recordTag).ownRow(recordTag)

    /**
     * Reads the published results. What stops the submit of a row (VAULT-MESSAGING 0.86.4 §10.19.6 step 1, ANDROID-PLAN
     * 0.1.117) throws [ResultsProblem]: `results.json` that does not name `rows_hash`, `rows_root` and `counts.eligible`;
     * `rows.json` over [rowsCap], nested deeper than 4, or with a row that cannot be made into a leaf ([RowsReader]); and
     * what the vault's own check of the row needs: `counts.eligible` rows whose leaves reach `rows_root`. The hash of
     * `rows.json`, the order and uniqueness of the tags and the lengths of `vk`, `ballot_sig` and `choice_id` are only
     * reported, by [PublishedResults.observe] (review 1, finding 5). [checkpoint] is called every 256 rows and tree nodes.
     */
    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    fun read(resultsBytes: ByteArray, rowsBytes: ByteArray, ownTag: ByteArray? = null, checkpoint: () -> Unit = {}): PublishedResults {
        val results: JsonObject
        val rowsHash: ByteArray
        val rowsRoot: ByteArray
        val eligible: Long
        try {
            results = StrictJson.parseObject(resultsBytes)
            rowsHash = results.base64("rows_hash", VoteMerkle.HASH)
            rowsRoot = results.base64("rows_root", VoteMerkle.HASH)
            eligible = results.obj("counts").uint("eligible", 1, MAX_ROWS.toLong())
        } catch (_: CryptoException) {
            throw ResultsProblem("results")
        }
        if (rowsBytes.size > rowsCap(eligible)) throw ResultsProblem("rows_size")
        val hashMatches = Bytes.constantTimeEquals(Bytes.sha256(rowsBytes), rowsHash)
        val rows = RowsReader(rowsBytes, eligible.toInt(), ownTag, checkpoint).read()
        // Sorted by tag, each once (§8.2), and the lengths of §8.5: reported, not a reason not to submit.
        val sorted = (1 until rows.count).all { compare(rows.tags, (it - 1) * KEY, it * KEY) < 0 }
        val lengths = rows.lengthsMatch
        val root = VoteMerkle.rootOfHashes(rows.leafHashes, rows.count, checkpoint)
        if (!Bytes.constantTimeEquals(root, rowsRoot)) throw ResultsProblem("rows_root")
        return PublishedResults(results, rows, hashMatches, sorted, lengths)
    }

    internal fun lp(x: ByteArray) = Bytes.concat(Bytes.uintBE(x.size.toLong(), 2), x)

    /** The 32 bytes at [x] against those at [y] of [a], unsigned. */
    private fun compare(a: ByteArray, x: Int, y: Int): Int {
        for (k in 0 until KEY) {
            val d = (a[x + k].toInt() and 0xff) - (a[y + k].toInt() and 0xff)
            if (d != 0) return d
        }
        return 0
    }

    private const val KEY = 32
}
