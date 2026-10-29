package com.vettid.core.data.social

import com.vettid.core.data.account.AccountNames

/**
 * Why a connection challenges the member (VAULT-MESSAGING 0.65.0 §10.4 "Purpose"): a sign-in to something it runs, an
 * approval of what the challenge's `context` names, or neither. A challenge without a purpose, or with a value this
 * app does not know, is [NONE] and is shown as before 0.65.0. Read only from the vault's `purpose`, never from `context`.
 */
enum class AuthPurpose {
    NONE,
    SIGNIN,
    APPROVAL,
    ;

    companion object {
        /** The wire value's purpose; any other value (or none) is [NONE]. */
        fun of(value: String?): AuthPurpose = when (value) {
            "signin" -> SIGNIN
            "approval" -> APPROVAL
            else -> NONE
        }
    }
}

/** A challenge's `context` is the connection's own words: shown as plain text that cannot reorder or hide anything. */
object AuthContext {
    /**
     * [s] with control and format characters (bidirectional overrides, embeddings, isolates and marks, zero-width ones)
     * removed, tabs as spaces, and line breaks (including U+2028/2029) kept as `\n`, so that a note's lines stay visible.
     */
    fun plain(s: String): String = clean(s)

    /** [plain], in bidirectional isolation (FSI ... PDI, §10.8), for next to other text; null when nothing is left. */
    fun isolated(s: String): String? = clean(s).takeIf { it.isNotBlank() }?.let { AccountNames.isolate(it) }

    /**
     * A received name for a fixed sentence: control and format characters removed (a PDI or RLO in it cannot close the
     * isolation or reverse the words around it), line breaks as spaces, then isolated; null when nothing is left.
     */
    fun isolatedName(s: String): String? =
        clean(s).replace('\n', ' ').trim().takeIf { it.isNotEmpty() }?.let { AccountNames.isolate(it) }

    /** The first line of [plain], isolated, for a list row's second line; null when it has none. */
    fun firstLineIsolated(s: String): String? = isolated(clean(s).trimStart('\n').lineSequence().first())

    /** Kept: they cannot reorder text, and emoji sequences and Persian or Indic shaping need them. */
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    private val LINE_BREAKS = setOf(0x2028, 0x2029, 0x85)

    private fun clean(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\n'.code || cp in LINE_BREAKS -> append('\n')
                cp == '\t'.code -> append(' ')
                cp == '\r'.code -> if (i >= s.length || s[i] != '\n') append('\n')
                cp == ZWNJ || cp == ZWJ -> appendCodePoint(cp)
                Character.getType(cp).let { it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt() } -> Unit
                else -> appendCodePoint(cp)
            }
        }
    }
}
