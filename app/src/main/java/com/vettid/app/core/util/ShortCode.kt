package com.vettid.app.core.util

/**
 * Short-code formatting + parsing helpers.
 *
 * The vault generates 12-character ambiguity-safe codes for every
 * short-lived pairing flow (peer invitations, device pairing, agent
 * registration). The same shape is reused across all three so users
 * never have to wonder "what kind of code is this?".
 *
 * Display format: three 4-character blocks separated by hyphens
 * (`ABCD-EFGH-JKLM`). On manual entry we accept any whitespace or
 * hyphen layout and uppercase the result.
 */
object ShortCode {
    const val LENGTH = 12
    const val BLOCK_SIZE = 4
    private val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray().toSet()

    /**
     * Insert hyphens every 4 characters. If the input is empty or
     * already contains separators, normalize first.
     */
    fun format(code: String): String {
        val raw = normalize(code)
        if (raw.isEmpty()) return ""
        return raw.chunked(BLOCK_SIZE).joinToString("-")
    }

    /** Strip whitespace + hyphens, uppercase everything. */
    fun normalize(input: String): String =
        input.uppercase().filter { it !in " \t\n-_." }

    /**
     * Returns true if the (already-normalized or unformatted) input is
     * a valid 12-character code drawn from the ambiguity-safe alphabet.
     */
    fun isValid(input: String): Boolean {
        val raw = normalize(input)
        if (raw.length != LENGTH) return false
        return raw.all { it in ALPHABET }
    }
}

/** Convenience top-level for callsites that just want display formatting. */
fun formatShortCode(code: String): String = ShortCode.format(code)
