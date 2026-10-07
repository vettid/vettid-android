package com.vettid.core.data.account

/**
 * The account's first and last name (VAULT-MESSAGING 0.18.0 §10.8, §11.13): the names the member gave VettID at
 * registration or later changed from this app (`account.name.set`), which every connection sees in the shared
 * profile's core. VettID does not verify them: apps may call them the name on the VettID account, never verified,
 * legal or checked.
 */
object AccountNames {
    /** The registration rule's length (MEMBER-API `/api/public/request`), in UTF-16 code units. */
    const val MAX_REQUESTED = 40

    /** The most a stored or received name may take, in UTF-8 bytes (§10.8, §11.13). */
    const val MAX_BYTES = 160

    private val RULE = Regex("[\\p{L}\\p{M}][\\p{L}\\p{M} '’.-]*")

    /** Why a requested name fails the registration rule, or [OK]. */
    enum class Check { OK, EMPTY, TOO_LONG, INVALID }

    /**
     * A name the member typed, trimmed of leading and trailing spaces (U+0020 only, 0.19.0: other white space at
     * either end fails the pattern), as `account.name.set` sends it; null when it fails [check].
     */
    fun normalize(s: String): String? = s.trim(' ').takeIf { check(s) == Check.OK }

    /**
     * The registration rule (§10.8): after trimming U+0020, `^[\p{L}\p{M}][\p{L}\p{M} '’.-]*$`, at most 40 UTF-16
     * code units. Checked before the PIN and the password are asked for.
     */
    fun check(s: String): Check {
        val t = s.trim(' ')
        return when {
            t.isEmpty() -> Check.EMPTY
            t.length > MAX_REQUESTED -> Check.TOO_LONG
            !RULE.matches(t) -> Check.INVALID
            else -> Check.OK
        }
    }

    /**
     * A name as the snapshot and the shared profile's core carry it (§10.8 "Receiving", §11.13): a string of
     * 1–160 UTF-8 bytes without control characters (C0, C1, U+2028, U+2029) and without lone surrogates.
     */
    @Suppress("MagicNumber", "ReturnCount") // the spec's code points
    fun isValidCore(s: String?): Boolean {
        if (s.isNullOrEmpty()) return false
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp in 0xD800..0xDFFF || isControl(cp)) return false
            bytes += when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes > MAX_BYTES) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /** C0, C1, U+2028 and U+2029 (§10.8 "Receiving"). */
    @Suppress("MagicNumber")
    private fun isControl(cp: Int): Boolean = cp < 0x20 || cp in 0x80..0x9F || cp == 0x2028 || cp == 0x2029

    /** "First Last" (§10.8: first name, one space, last name); null unless both are valid core names. */
    fun full(first: String?, last: String?): String? =
        if (isValidCore(first) && isValidCore(last)) "$first $last" else null

    /** [s] in Unicode bidirectional isolation (FSI … PDI), for names shown next to other text (§10.8). */
    fun isolate(s: String): String = "⁨$s⁩"
}
