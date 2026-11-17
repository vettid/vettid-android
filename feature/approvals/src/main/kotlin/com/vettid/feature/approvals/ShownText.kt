package com.vettid.feature.approvals

import android.icu.lang.UCharacter
import android.icu.lang.UCharacterCategory
import android.icu.lang.UProperty
import android.icu.text.BreakIterator

/**
 * A string of an approval's document as the screen shows it (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents", "Text"):
 * plain text, never markup or links, in which every character that could reorder, hide or imitate other text is a visible
 * marker naming its code point (`⟨U+202E⟩`), never applied. These rules replace §10.8 "Showing received text" on this
 * screen. As vettid-ios #300's `ShownText`; the Unicode data is Android's (`android.icu`), so a character newer than the
 * phone's data is unassigned to it, and a marker.
 */
class ShownText internal constructor(val runs: List<Run>) {
    sealed interface Run {
        /** Characters shown as themselves. */
        data class Text(val text: String) : Run

        /** A kept line break (LF), only where the field keeps them. */
        data object LineBreak : Run

        /** A character shown as its code point, never applied. */
        data class Marker(val codePoint: Int) : Run
    }

    /** The text as one string, markers written out and kept line breaks as LF: what the screen draws. */
    val plain: String
        get() = buildString {
            for (r in runs) {
                when (r) {
                    is Run.Text -> append(r.text)
                    Run.LineBreak -> append('\n')
                    is Run.Marker -> append(marker(r.codePoint))
                }
            }
        }

    /** Whether nothing is shown (an empty string). */
    val isEmpty: Boolean get() = runs.isEmpty()

    override fun equals(other: Any?): Boolean = other is ShownText && runs == other.runs

    override fun hashCode(): Int = runs.hashCode()

    override fun toString(): String = "ShownText(${runs.size} runs)"

    /**
     * A piece of a value: its text, and whether it goes on from the piece before (cut inside a paragraph) rather than
     * starting a paragraph of its own (after a kept line break) or the value.
     */
    data class Piece(val text: ShownText, val continues: Boolean)

    /**
     * The text in pieces of at most [max] characters (grapheme clusters) and [maxBreaks] kept line breaks, each drawn as
     * a row of its own, so that a value of tens of kilobytes is drawn a few rows at a time. A piece ends after a white
     * space where there is one, at a kept line break once it is half full (the next piece then starts a paragraph), never
     * inside a marker or a character, else at the bound.
     */
    // One case per kind of run, and the cut of a text in place.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    fun pieces(max: Int = PIECE_CHARACTERS, maxBreaks: Int = Int.MAX_VALUE): List<Piece> {
        val out = mutableListOf<Piece>()
        var cur = mutableListOf<Run>()
        var n = 0
        var breaks = 0
        var continues = false
        fun close(paragraph: Boolean) {
            out += Piece(ShownText(cur), continues)
            cur = mutableListOf()
            n = 0
            breaks = 0
            continues = !paragraph
        }
        for (run in runs) {
            when (run) {
                is Run.Marker -> {
                    val len = marker(run.codePoint).length
                    if (n > 0 && n + len > max) close(paragraph = false)
                    cur += run
                    n += len
                }
                Run.LineBreak -> if (n >= max / 2 || breaks >= maxBreaks) {
                    close(paragraph = true)
                } else {
                    cur += run
                    n += 1
                    breaks += 1
                }
                is Run.Text -> {
                    var rest = Graphemes(run.text)
                    while (!rest.isEmpty) {
                        val room = max - n
                        if (rest.count <= room) {
                            n += rest.count
                            cur += Run.Text(rest.text)
                            break
                        }
                        val ws = rest.lastWhitespaceBefore(room)
                        if (ws == null && n > 0) {
                            close(paragraph = false)
                            continue
                        }
                        // After the last white space before the bound, else (a piece of its own) at the bound.
                        val cut = if (ws != null) ws + 1 else room
                        cur += Run.Text(rest.prefix(cut))
                        rest = rest.drop(cut)
                        close(paragraph = false)
                    }
                }
            }
        }
        if (cur.isNotEmpty() || out.isEmpty()) close(paragraph = true)
        return out
    }

    companion object {
        /** At most this many characters to a piece ([pieces]). */
        const val PIECE_CHARACTERS = 120

        /** At most this many code points to a character drawn as itself (chosen, as iOS; not in the spec). */
        const val MAX_CLUSTER_CODE_POINTS = 8

        private const val LF = 0x0A
        private const val QUOTE = '"'.code
        private const val BACKSLASH = '\\'.code
        private const val SPACE = 0x20

        /** The marker of [codePoint]: `⟨U+202E⟩` (at least four hex digits, upper case). */
        fun marker(codePoint: Int): String = "\u27E8U+%04X\u27E9".format(codePoint)

        /**
         * [s] cleaned. [lineBreaks]: the field keeps line breaks (Motion and Quorum's text): at most two LF in a row are
         * kept, a third and later in a row are markers; elsewhere every LF is a marker. [escaped]: a string of the raw view,
         * whose `"` and `\` are shown escaped as JSON writes them (`\"`, `\\`), so that no string can close its own quotes
         * and draw what looks like another member (chosen, as iOS; not in the spec).
         */
        @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth") // one test per rule of §10.4 "Text"
        fun of(s: String, lineBreaks: Boolean = false, escaped: Boolean = false): ShownText {
            val runs = mutableListOf<Run>()
            val text = StringBuilder()
            var breaks = 0
            var marks = 0
            fun flush() {
                if (text.isNotEmpty()) runs += Run.Text(text.toString())
                text.setLength(0)
            }
            fun marker(cp: Int) {
                flush()
                runs += Run.Marker(cp)
            }
            // Character by character, so that no character (grapheme cluster) is drawn with more than
            // MAX_CLUSTER_CODE_POINTS code points: past them, each is a marker (chosen, as iOS; not in the spec).
            val bi = BreakIterator.getCharacterInstance()
            bi.setText(s)
            var start = bi.first()
            var end = bi.next()
            while (end != BreakIterator.DONE) {
                var i = start
                var k = 0
                while (i < end) {
                    val cp = s.codePointAt(i)
                    val isBreak = cp == LF
                    breaks = if (isBreak) breaks + 1 else 0
                    val type = UCharacter.getType(cp)
                    // The row §10.4 counts: combining and enclosing marks (Mn, Me). Spacing marks (Mc) do not stack.
                    val isMark = type == UCharacterCategory.NON_SPACING_MARK.toInt() || type == UCharacterCategory.ENCLOSING_MARK.toInt()
                    marks = if (isMark) marks + 1 else 0
                    // A mark of any kind with no base of its own in this run (at the start, or after a marker or a line
                    // break) would combine with what the app draws before it (a quotation mark, a marker): a marker
                    // itself; and an emoji skin-tone modifier with no base would tint it.
                    val extender = isMark || type == UCharacterCategory.COMBINING_SPACING_MARK.toInt() ||
                        UCharacter.hasBinaryProperty(cp, UProperty.GRAPHEME_EXTEND)
                    val baseless = (extender || UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_MODIFIER)) && text.isEmpty()
                    when {
                        isBreak && lineBreaks && breaks <= 2 -> {
                            flush()
                            runs += Run.LineBreak
                        }
                        isBreak || marked(cp) || (isMark && marks > 2) || baseless || k >= MAX_CLUSTER_CODE_POINTS -> marker(cp)
                        escaped && (cp == QUOTE || cp == BACKSLASH) -> text.append('\\').appendCodePoint(cp)
                        else -> text.appendCodePoint(cp)
                    }
                    i += Character.charCount(cp)
                    k++
                }
                start = end
                end = bi.next()
            }
            flush()
            return ShownText(runs)
        }

        /**
         * Always markers (chosen, as iOS; not in the spec), so that no text can spell a marker or the app's continuation
         * mark, however it is styled: the marker's brackets and the angle brackets that look like them, the blank glyphs
         * that are not default-ignorable (U+2800 BRAILLE PATTERN BLANK, U+FFFC OBJECT REPLACEMENT CHARACTER), the
         * continuation mark the screen draws (U+21B3) and its look-alikes, and the Prepend characters that are not format
         * characters (by value as well as by [gluesForward]).
         */
        internal val MARKER_BRACKETS: Set<Int> = setOf(
            0x27E8, 0x27E9, 0x2329, 0x232A, 0x3008, 0x3009, 0x276C, 0x276D, 0x2039, 0x203A, 0x276E, 0x276F, 0x27EA, 0x27EB,
            0xFF1C, 0xFF1E, 0x29FC, 0x29FD, 0x2991, 0x2992, 0x2770, 0x2771, 0x300A, 0x300B, 0xFE64, 0xFE65, 0x02C2, 0x02C3,
            0x1438, 0x1433, 0x2800, 0xFFFC,
            0x21B3, 0x21AA, 0x2937, 0x21B4, 0x2BA1, 0x2514, 0x2570, 0x2936, 0x21B2, 0x21B5, 0x23CE, 0x27A5, 0x2B91, 0x2BA9,
            0x2935, 0x221F, 0x2517, 0x2515, 0x2516, 0x255A, 0x2558, 0x2559, 0x231E, 0x23A3, 0x2B0E, 0x2BA7,
            0x0D4E, 0x111C2, 0x111C3, 0x1193F, 0x11941, 0x11A3A, 0x11A84, 0x11A85, 0x11A86, 0x11A87, 0x11A88, 0x11A89,
            0x11D46, 0x11F02,
        )

        /**
         * Whether [cp] forms one character with a `\` after it (a Prepend character, of whatever Unicode version this
         * phone knows): it would cluster with an escape the app inserts, or with the app's own quotation mark or marker.
         */
        internal fun gluesForward(cp: Int): Boolean {
            val s = StringBuilder().appendCodePoint(cp).append('\\').toString()
            val bi = BreakIterator.getCharacterInstance()
            bi.setText(s)
            bi.first()
            return bi.next() == s.length
        }

        /**
         * The characters always shown as markers: control (Cc, U+0085 included) and format (Cf, the bidirectional
         * controls among them) characters, line and paragraph separators (Zl, Zp), private-use and unassigned code points
         * (Co, Cn), any other Default_Ignorable_Code_Point (the Hangul fillers, the variation selectors, U+FE0F and the
         * joiners among them), and every space separator (Zs) but U+0020. Also (chosen, as iOS) [MARKER_BRACKETS] and any
         * character that [gluesForward].
         */
        internal fun marked(cp: Int): Boolean {
            if (cp in MARKER_BRACKETS || gluesForward(cp)) return true
            return when (UCharacter.getType(cp).toByte()) {
                UCharacterCategory.CONTROL, UCharacterCategory.FORMAT, UCharacterCategory.LINE_SEPARATOR,
                UCharacterCategory.PARAGRAPH_SEPARATOR, UCharacterCategory.PRIVATE_USE, UCharacterCategory.UNASSIGNED,
                UCharacterCategory.SURROGATE,
                -> true
                UCharacterCategory.SPACE_SEPARATOR -> cp != SPACE
                else -> UCharacter.hasBinaryProperty(cp, UProperty.DEFAULT_IGNORABLE_CODE_POINT)
            }
        }
    }
}

/** A string seen as its characters (grapheme clusters, by the phone's Unicode data): counted and cut only between them. */
internal class Graphemes private constructor(val text: String, private val bounds: IntArray) {
    constructor(text: String) : this(text, boundsOf(text))

    /** How many characters. */
    val count: Int get() = bounds.size - 1

    val isEmpty: Boolean get() = count == 0

    /** The first [n] characters. */
    fun prefix(n: Int): String = text.substring(0, bounds[n.coerceIn(0, count)])

    /** All but the first [n] characters. */
    fun drop(n: Int): Graphemes {
        val k = n.coerceIn(0, count)
        val base = bounds[k]
        return Graphemes(text.substring(base), IntArray(bounds.size - k) { bounds[it + k] - base })
    }

    /** The character at [i], as a string. */
    fun at(i: Int): String = text.substring(bounds[i], bounds[i + 1])

    /** The index of the last character before [limit] that is a white space, or null. */
    fun lastWhitespaceBefore(limit: Int): Int? {
        for (i in minOf(limit, count) - 1 downTo 0) if (UCharacter.isUWhiteSpace(text.codePointAt(bounds[i]))) return i
        return null
    }

    companion object {
        fun boundsOf(s: String): IntArray {
            if (s.isEmpty()) return intArrayOf(0)
            val bi = BreakIterator.getCharacterInstance()
            bi.setText(s)
            val out = ArrayList<Int>()
            var b = bi.first()
            while (b != BreakIterator.DONE) {
                out += b
                b = bi.next()
            }
            return out.toIntArray()
        }
    }
}
