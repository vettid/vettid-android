package com.vettid.core.data.account

import android.icu.lang.UCharacter
import android.icu.lang.UProperty

/**
 * The account's first and last name (VAULT-MESSAGING 0.18.0 §10.8, §11.13): the names the member gave VettID at
 * registration or later changed from this app (`account.name.set`), which every connection sees in the shared
 * profile's core. VettID does not verify them: apps may call them the name on the VettID account, never verified,
 * legal or checked.
 */
@Suppress("TooManyFunctions") // the one cleaning step and its callers' shapes
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

    /**
     * A received name made safe to sit in a sentence or next to fixed wording, or alone (VAULT-MESSAGING 0.76.0 §10.8
     * "Showing received text"): [cleaned], then in bidirectional isolation (FSI ... PDI). Null when it is empty (step 4).
     * The one cleaning step for every string another party controls.
     */
    fun isolateOrNull(s: String?): String? = cleaned(s)?.let { FSI + it + PDI }

    /**
     * [isolateOrNull] without the isolation (an avatar's initial, a search key): steps 1-4 as one line (every break and
     * tab a space, Cc and Cf removed but ZWJ and ZWNJ, lone surrogates as U+FFFD, White_Space trimmed); null when nothing
     * is left once Default_Ignorable_Code_Point and White_Space code points are set aside. Also strips an isolate it is given.
     */
    fun cleaned(s: String?): String? = s?.let { scrub(it, keepLines = false, keepMarks = false) }?.takeUnless(::isEmptyText)

    /**
     * Steps 1-3 for text whose lines are kept (a context, a note): every break one LF, trimmed on each line and with no
     * empty line at either end. Null when empty (step 4). The result is not isolated: see [isolateLines].
     */
    fun cleanedLines(s: String?): String? =
        s?.let { scrub(it, keepLines = true, keepMarks = false) }?.takeUnless(::isEmptyText)

    /**
     * A message's full text for its own bubble: as [cleanedLines] but LRM, RLM and ALM are kept and only the ends of the
     * whole text are trimmed (a line's indentation stays). Not isolated: see [isolateLines]. Empty string when nothing shows.
     */
    fun cleanedMessage(s: String?): String =
        s?.let { scrub(it, keepLines = false, keepMarks = true, wholeText = true) }?.takeUnless(::isEmptyText).orEmpty()

    /** [s] (already cleaned) with every line in its own isolate (an isolate ends at a line break, §10.8 step 5). */
    fun isolateLines(s: String): String = s.split('\n').joinToString("\n") { FSI + it + PDI }

    /** True when [s] shows nothing: only Default_Ignorable_Code_Point and White_Space code points remain (step 4). */
    fun isEmptyText(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isWhiteSpace(cp) && !UCharacter.hasBinaryProperty(cp, UProperty.DEFAULT_IGNORABLE_CODE_POINT)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /**
     * The first line of [s] (steps 1-3, lines kept) that is not empty by step 4, isolated; null when there is none
     * (step 6: a one-line summary of a multi-line text).
     */
    fun firstLineIsolated(s: String?): String? =
        s?.let { scrub(it, keepLines = true, keepMarks = false) }?.split('\n')?.firstOrNull { !isEmptyText(it) }
            ?.let { FSI + it + PDI }

    /** Steps 1-3 (and the lone-surrogate rule of step 2). */
    @Suppress("CyclomaticComplexMethod", "MagicNumber")
    private fun scrub(s: String, keepLines: Boolean, keepMarks: Boolean, wholeText: Boolean = false): String {
        val brk = if (keepLines || wholeText) '\n' else ' '
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp in 0xD800..0xDFFF -> sb.append(REPLACEMENT) // a lone surrogate (Cs)
                cp == CR -> {
                    if (i < s.length && s[i] == '\n') i++ // CR immediately followed by LF is one break
                    sb.append(brk)
                }
                cp == '\n'.code || cp == VT || cp == FF || cp in LINE_BREAKS -> sb.append(brk)
                cp == '\t'.code -> sb.append(' ')
                cp == ZWNJ || cp == ZWJ -> sb.appendCodePoint(cp)
                keepMarks && (cp == LRM || cp == RLM || cp == ALM) -> sb.appendCodePoint(cp)
                isInvisible(cp) -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        val whole = sb.toString().trim(::isWhiteSpaceChar)
        if (!keepLines) return whole
        return whole.split('\n').map { it.trim(::isWhiteSpaceChar) }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
            .joinToString("\n")
    }

    private fun isWhiteSpaceChar(c: Char) = isWhiteSpace(c.code)

    /** The Unicode White_Space property (a fixed list). */
    @Suppress("MagicNumber")
    private fun isWhiteSpace(cp: Int): Boolean = when (cp) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** Control and format characters (bidi, zero-width, BOM, tags), except ZWJ and ZWNJ. */
    private fun isInvisible(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
    }

    /** [s], already cleaned by the caller, in isolation as it is. */
    fun wrapIsolated(s: String): String = FSI + s + PDI

    /** [isolateOrNull], for a name that must be shown: an empty result is an empty isolate, never the raw text. */
    fun isolate(s: String): String = isolateOrNull(s) ?: (FSI.toString() + PDI)

    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D
    private const val CR = 0x0D
    private const val VT = 0x0B
    private const val FF = 0x0C
    private const val LRM = 0x200E
    private const val RLM = 0x200F
    private const val ALM = 0x061C
    private const val REPLACEMENT = '\uFFFD'
    private val LINE_BREAKS = setOf(0x2028, 0x2029, 0x85)

    /** FIRST STRONG ISOLATE and POP DIRECTIONAL ISOLATE, by code point: lint rightly flags them as literals. */
    private val FSI = Char(0x2068)
    private val PDI = Char(0x2069)
}
