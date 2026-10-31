package com.vettid.core.crypto.vote

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.json.JsonNull
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject

/**
 * A member's row of a vote's published results (BALLOT-PROTOCOL §8.2, §8.5): the row `rows.json` holds under the
 * member's record tag, with its inclusion proof to the results' `rows_root`. [ballot] is the row's `ballot` object as
 * the site has it (`vk`, `choice_id`, `ballot_sig`), or null for an empty row.
 */
class OwnRow(val ballot: JsonObject?, val index: Long, val treeSize: Long, val path: List<ByteArray>)

/** Why the site's results could not be taken as they are: said to the member as a problem with the published results. */
class ResultsProblem(val reason: String) : Exception(reason)

/**
 * The part of the observer's check (BALLOT-PROTOCOL §8.4) the app does from the site's bytes for `vote.results.submit`
 * (VAULT-MESSAGING §10.19.6 step 1): the hash of `rows.json`, the rows in tag order, the tree they make against
 * `rows_root`, and the member's row. The signature, the anchor and the verdict are the vault's; it never relies on this.
 */
object VoteRows {
    /** The most rows read (the site's results are at most 512 KiB; a row is about 250 bytes). */
    const val MAX_ROWS = 100_000

    /**
     * Checks [rowsBytes] against [resultsBytes] and returns the row of [recordTag] with its proof. Throws [ResultsProblem]
     * for anything that does not hold, including a tag that has no row.
     */
    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    fun ownRow(resultsBytes: ByteArray, rowsBytes: ByteArray, recordTag: ByteArray): OwnRow {
        val rowsHash: ByteArray
        val rowsRoot: ByteArray
        val eligible: Long
        try {
            val results = StrictJson.parseObject(resultsBytes)
            rowsHash = results.base64("rows_hash", VoteMerkle.HASH)
            rowsRoot = results.base64("rows_root", VoteMerkle.HASH)
            eligible = results.obj("counts").uint("eligible", 1, MAX_ROWS.toLong())
        } catch (_: CryptoException) {
            throw ResultsProblem("results")
        }
        if (!Bytes.constantTimeEquals(Bytes.sha256(rowsBytes), rowsHash)) throw ResultsProblem("rows_hash")
        val leaves = ArrayList<ByteArray>()
        var found = -1
        var foundBallot: JsonObject? = null
        try {
            val rows = StrictJson.parseObject(rowsBytes).array("rows")
            if (rows.size.toLong() != eligible) throw ResultsProblem("rows_count")
            var previous: ByteArray? = null
            for ((i, e) in rows.withIndex()) {
                val row = e.asObject()
                val tag = row.base64("record_tag", VoteMerkle.HASH)
                // Sorted by tag, each once (§8.2): another order is not the tree the root was made over.
                if (previous != null && compare(previous, tag) >= 0) throw ResultsProblem("rows_order")
                previous = tag
                val b = row["ballot"]
                val ballot = if (b == null || b is JsonNull) null else b.asObject()
                leaves += if (ballot == null) tag else leafOf(tag, ballot)
                if (Bytes.constantTimeEquals(tag, recordTag)) {
                    found = i
                    foundBallot = ballot
                }
            }
        } catch (_: CryptoException) {
            throw ResultsProblem("rows")
        }
        if (!Bytes.constantTimeEquals(VoteMerkle.root(leaves), rowsRoot)) throw ResultsProblem("rows_root")
        if (found < 0) throw ResultsProblem("no_row")
        val path = VoteMerkle.proof(leaves, found) ?: throw ResultsProblem("rows")
        return OwnRow(foundBallot, found.toLong(), leaves.size.toLong(), path)
    }

    /** `ballot_leaf` (§5): `record_tag || lp(vk) || lp(choice_id) || lp(ballot_sig)`, `lp(x) = u16(len x) || x`. */
    private fun leafOf(tag: ByteArray, b: JsonObject): ByteArray {
        val vk = b.base64("vk", KEY)
        val sig = b.base64("ballot_sig", SIG)
        val choice = b.string("choice_id").toByteArray(Charsets.UTF_8)
        if (choice.isEmpty() || choice.size > MAX_ID) throw ResultsProblem("rows")
        return Bytes.concat(tag, lp(vk), lp(choice), lp(sig))
    }

    private fun lp(x: ByteArray) = Bytes.concat(Bytes.uintBE(x.size.toLong(), 2), x)

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
