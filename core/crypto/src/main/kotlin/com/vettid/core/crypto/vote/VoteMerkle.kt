package com.vettid.core.crypto.vote

import com.vettid.core.crypto.Bytes

/**
 * The Merkle tree of a vote's rows (BALLOT-PROTOCOL §8.2, RFC 6962 §2.1 with SHA-256): leaf hash `H(0x00 || leaf)`, node
 * hash `H(0x01 || left || right)`, split at the largest power of two below n, the empty tree `H("")`. Proofs are audit
 * paths, nearest sibling first, verified per RFC 9162 §2.1.3.2. The app builds its own row's proof from the site's
 * `rows.json` for `vote.results.submit` (VAULT-MESSAGING §10.19.6); the vault verifies it again.
 */
object VoteMerkle {
    const val HASH = 32

    fun leafHash(leaf: ByteArray): ByteArray = Bytes.sha256(byteArrayOf(0), leaf)

    private fun nodeHash(l: ByteArray, r: ByteArray): ByteArray = Bytes.sha256(byteArrayOf(1), l, r)

    /** The largest power of two below [n] (n >= 2). */
    private fun split(n: Int): Int {
        var k = 1
        while (k shl 1 < n) k = k shl 1
        return k
    }

    /** The tree head over [leaves], in order. */
    fun root(leaves: List<ByteArray>): ByteArray =
        if (leaves.isEmpty()) Bytes.sha256() else rootOf(leaves.map(::leafHash))

    private fun rootOf(h: List<ByteArray>): ByteArray {
        if (h.size == 1) return h[0]
        val k = split(h.size)
        return nodeHash(rootOf(h.subList(0, k)), rootOf(h.subList(k, h.size)))
    }

    /** The audit path of the leaf at [index]; null when [index] is outside [leaves]. */
    fun proof(leaves: List<ByteArray>, index: Int): List<ByteArray>? =
        if (index < 0 || index >= leaves.size) null else path(leaves.map(::leafHash), index)

    private fun path(h: List<ByteArray>, m: Int): List<ByteArray> {
        if (h.size == 1) return emptyList()
        val k = split(h.size)
        return if (m < k) {
            path(h.subList(0, k), m) + rootOf(h.subList(k, h.size))
        } else {
            path(h.subList(k, h.size), m - k) + rootOf(h.subList(0, k))
        }
    }

    /** Whether [leaf] is at [index] of a tree of [size] leaves with head [root]. A path entry that is not 32 bytes fails. */
    fun verify(root: ByteArray, leaf: ByteArray, index: Long, size: Long, path: List<ByteArray>): Boolean {
        if (index < 0 || index >= size || root.size != HASH) return false
        var fn = index
        var sn = size - 1
        var r = leafHash(leaf)
        for (p in path) {
            if (sn == 0L || p.size != HASH) return false
            if (fn and 1L == 1L || fn == sn) {
                r = nodeHash(p, r)
                while (fn and 1L == 0L && fn != 0L) {
                    fn = fn shr 1
                    sn = sn shr 1
                }
            } else {
                r = nodeHash(r, p)
            }
            fn = fn shr 1
            sn = sn shr 1
        }
        return sn == 0L && Bytes.constantTimeEquals(r, root)
    }
}
