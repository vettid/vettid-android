package com.vettid.core.crypto.vote

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.json.JsonNull
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import com.vettid.core.crypto.json.asUint

/**
 * A member's row of a vote's published results (BALLOT-PROTOCOL §8.2, §8.5): the row `rows.json` holds under the
 * member's record tag, with its inclusion proof to the results' `rows_root`. [ballot] is the row's `ballot` object as
 * the site has it (`vk`, `choice_id`, `ballot_sig`), or null for an empty row.
 */
class OwnRow(val ballot: JsonObject?, val index: Long, val treeSize: Long, val path: List<ByteArray>)

/** Why the site's results could not be taken as they are: said to the member as a problem with the published results. */
class ResultsProblem(val reason: String) : Exception(reason)

/**
 * A vote's published results read from the site's bytes ([VoteRows.read]): `results.json` and `rows.json`, with the hash
 * of `rows.json`, the rows in tag order and the tree they make against `rows_root` already checked. [ownRow] is the
 * member's row for `vote.results.submit`; [observe] is the rest of the observer's check.
 */
class PublishedResults internal constructor(
    private val results: JsonObject,
    private val tags: List<ByteArray>,
    private val ballots: List<JsonObject?>,
    private val leaves: List<ByteArray>,
) {
    /** The row of [recordTag] with its proof. Throws [ResultsProblem] (`no_row`) when the tag has no row. */
    fun ownRow(recordTag: ByteArray): OwnRow {
        val found = tags.indexOfFirst { Bytes.constantTimeEquals(it, recordTag) }
        if (found < 0) throw ResultsProblem("no_row")
        val path = VoteMerkle.proof(leaves, found) ?: throw ResultsProblem("rows")
        return OwnRow(ballots[found], found.toLong(), leaves.size.toLong(), path)
    }

    /**
     * The observer's check of the rows against the proposal the results carry (BALLOT-PROTOCOL §8.4 items 2 to 4; VAULT-
     * MESSAGING 0.86.4 §10.19.6 step 1): the tags number the proposal's `eligible` and reach its `record_root`; every
     * ballot's `choice_id` is one of the proposal's choices and its `ballot_sig` verifies under its `vk` by the four rules
     * of BALLOT-PROTOCOL §2 ([Ed25519.verifyStrict]); the `tally` per choice and `counts.ballots` recompute. Throws
     * [ResultsProblem] at the first difference, cheapest first and the signatures last. The signatures, the anchors and the
     * proposal's own checks are the vault's (§10.19.6 step 2); this never decides anything for it.
     */
    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    fun observe() {
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
        if (tags.size.toLong() != eligible) throw ResultsProblem("eligible")
        // The choices and the counts, from the rows alone.
        val counted = HashMap<String, Long>()
        for (b in ballots) {
            if (b == null) continue
            val id = b.string("choice_id")
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
        if (!Bytes.constantTimeEquals(VoteMerkle.root(tags), recordRoot)) throw ResultsProblem("record_root")
        // Every ballot under its own key (§5): ballot_msg = proposal_hash || record_tag || lp(choice_id).
        val proposalHash = Bytes.sha256(proposalBytes)
        for ((i, b) in ballots.withIndex()) {
            if (b == null) continue
            val choice = b.string("choice_id").toByteArray(Charsets.UTF_8)
            val msg = Bytes.concat(proposalHash, tags[i], VoteRows.lp(choice))
            if (!Ed25519.verifyStrict(b.base64("vk"), VoteRows.BALLOT_LABEL, msg, b.base64("ballot_sig"))) {
                throw ResultsProblem("ballot_sig")
            }
        }
    }
}

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

    /** `"vettid/vote/1/ballot\x00"` (BALLOT-PROTOCOL §5). */
    internal const val BALLOT_LABEL = "vettid/vote/1/ballot\u0000"

    /**
     * Checks [rowsBytes] against [resultsBytes] and returns the row of [recordTag] with its proof. Throws [ResultsProblem]
     * for anything that does not hold, including a tag that has no row.
     */
    fun ownRow(resultsBytes: ByteArray, rowsBytes: ByteArray, recordTag: ByteArray): OwnRow =
        read(resultsBytes, rowsBytes).ownRow(recordTag)

    /**
     * Reads the published results: `results.json` names `rows_hash`, `rows_root` and `counts.eligible`; `rows.json` hashes
     * to `rows_hash`, has `counts.eligible` rows sorted by tag, each once and each one that can be made into a leaf, and
     * its leaves reach `rows_root`. Throws [ResultsProblem] for anything that does not hold.
     */
    @Suppress("ThrowsCount")
    fun read(resultsBytes: ByteArray, rowsBytes: ByteArray): PublishedResults {
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
        if (!Bytes.constantTimeEquals(Bytes.sha256(rowsBytes), rowsHash)) throw ResultsProblem("rows_hash")
        val tags = ArrayList<ByteArray>()
        val ballots = ArrayList<JsonObject?>()
        val leaves = ArrayList<ByteArray>()
        try {
            val rows = StrictJson.parseObject(rowsBytes).array("rows")
            if (rows.size.toLong() != eligible) throw ResultsProblem("rows_count")
            var previous: ByteArray? = null
            for (e in rows) {
                val row = e.asObject()
                val tag = row.base64("record_tag", VoteMerkle.HASH)
                // Sorted by tag, each once (§8.2): another order is not the tree the root was made over.
                if (previous != null && compare(previous, tag) >= 0) throw ResultsProblem("rows_order")
                previous = tag
                val b = row["ballot"]
                val ballot = if (b == null || b is JsonNull) null else b.asObject()
                tags += tag
                ballots += ballot
                leaves += if (ballot == null) tag else leafOf(tag, ballot)
            }
        } catch (_: CryptoException) {
            throw ResultsProblem("rows")
        }
        if (!Bytes.constantTimeEquals(VoteMerkle.root(leaves), rowsRoot)) throw ResultsProblem("rows_root")
        return PublishedResults(results, tags, ballots, leaves)
    }

    /** `ballot_leaf` (§5): `record_tag || lp(vk) || lp(choice_id) || lp(ballot_sig)`, `lp(x) = u16(len x) || x`. */
    private fun leafOf(tag: ByteArray, b: JsonObject): ByteArray {
        val vk = b.base64("vk", KEY)
        val sig = b.base64("ballot_sig", SIG)
        val choice = b.string("choice_id").toByteArray(Charsets.UTF_8)
        if (choice.isEmpty() || choice.size > MAX_ID) throw ResultsProblem("rows")
        return Bytes.concat(tag, lp(vk), lp(choice), lp(sig))
    }

    internal fun lp(x: ByteArray) = Bytes.concat(Bytes.uintBE(x.size.toLong(), 2), x)

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return 0
    }

    private const val KEY = 32
    private const val SIG = 64
    private const val MAX_ID = 32
}
