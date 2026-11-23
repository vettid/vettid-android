package com.vettid.core.ui.text

import android.icu.lang.UCharacter
import android.icu.lang.UProperty

/**
 * A piece of a string as ANDROID-PLAN 0.1.137 AA8 "How a string is shown" draws it: plain printable ASCII ([Text] with
 * `plain`), other characters highlighted ([Text] without), an escaped code point ([Escape]) or a marked space ([Space]).
 */
sealed interface ShownPiece {
    data class Text(val text: String, val plain: Boolean) : ShownPiece

    data class Escape(val codePoint: Int) : ShownPiece {
        /** `U+202E`: at least four hex digits, upper case. */
        val label: String get() = "U+%04X".format(codePoint)
    }

    data object Space : ShownPiece
}

/** AA8 "How a string is shown": what is escaped, what is marked and what is highlighted. Pure. */
object ShownString {
    private const val SPACE = 0x20
    private const val TILDE = 0x7E
    private const val BRAILLE_BLANK = 0x2800

    private val ESCAPED_TYPES = setOf(
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(), Character.PRIVATE_USE.toInt(), Character.SURROGATE.toInt(),
        Character.UNASSIGNED.toInt(),
    )

    /**
     * Escaped on its own: Cc, Cf, Zl, Zp, Co, Cs, Cn; every White_Space code point but U+0020; every
     * Default_Ignorable_Code_Point; U+2800 BRAILLE PATTERN BLANK.
     */
    fun escaped(cp: Int): Boolean = Character.getType(cp) in ESCAPED_TYPES ||
        cp != SPACE && UCharacter.hasBinaryProperty(cp, UProperty.WHITE_SPACE) ||
        UCharacter.hasBinaryProperty(cp, UProperty.DEFAULT_IGNORABLE_CODE_POINT) ||
        cp == BRAILLE_BLANK

    private fun combining(cp: Int): Boolean {
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt()
    }

    /**
     * [s] by the string rule as plain text, for a string inside a sentence (a dialog's text, a line of advice): isolated
     * and forced left to right (LRI LRO … PDF PDI), each space as `·`, and every code point outside printable ASCII as
     * `⟨U+XXXX⟩` (the approval documents' markers), since nothing here is highlighted: so a look-alike, or a literal `·`,
     * `⟨` or `⟩`, cannot pass for something else. An empty string is [empty] between markers. The box ([pieces]) is the full form.
     */
    fun inline(s: String, empty: String): String = buildString {
        append('\u2066').append('\u202D')
        if (s.isEmpty()) append('⟨').append(empty).append('⟩')
        pieces(s).forEach { p ->
            when (p) {
                is ShownPiece.Text -> if (p.plain) append(p.text) else p.text.codePoints().forEach { marker(this, it) }
                is ShownPiece.Escape -> marker(this, p.codePoint)
                ShownPiece.Space -> append('·')
            }
        }
        append('\u202C').append('\u2069')
    }

    private fun marker(b: StringBuilder, cp: Int) {
        b.append('⟨').append("U+%04X".format(cp)).append('⟩')
    }

    /**
     * [s] as pieces, in order. A combining mark at the start, right after a `/` or `\`, an escape or a marked space is
     * escaped too, so it cannot join the box's edge, a separator, an escape or a space mark.
     */
    fun pieces(s: String): List<ShownPiece> {
        val out = mutableListOf<ShownPiece>()
        val run = StringBuilder()
        var runPlain = true
        fun flush() {
            if (run.isNotEmpty()) out += ShownPiece.Text(run.toString(), runPlain)
            run.clear()
        }
        var prev: Int? = null
        var prevBreaks = true // the start counts as an edge
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val edge = prevBreaks || prev == '/'.code || prev == '\\'.code
            when {
                escaped(cp) || combining(cp) && edge -> {
                    flush()
                    out += ShownPiece.Escape(cp)
                    prevBreaks = true
                }
                cp == SPACE -> {
                    flush()
                    out += ShownPiece.Space
                    prevBreaks = true
                }
                else -> {
                    val plain = cp in SPACE..TILDE
                    if (run.isNotEmpty() && plain != runPlain) flush()
                    runPlain = plain
                    run.appendCodePoint(cp)
                    prevBreaks = false
                }
            }
            prev = cp
        }
        flush()
        return out
    }

    /** "Some characters here may look like others.": a character outside printable ASCII is shown, not escaped. */
    fun lookalike(pieces: List<ShownPiece>): Boolean = pieces.any { it is ShownPiece.Text && !it.plain }
}

/** A recorded fingerprint, as AA7 and AA8 show it. */
object Fingerprints {
    private const val SHORT = 16
    private const val GROUP = 4

    /** The first 16 hex digits in groups of four: "1a2b 3c4d 5e6f 7a8b". */
    fun short(sha256: String): String = grouped(sha256.take(SHORT))

    /** All 64 digits in groups of four. */
    fun full(sha256: String): String = grouped(sha256)

    private fun grouped(s: String): String = s.chunked(GROUP).joinToString(" ")
}
