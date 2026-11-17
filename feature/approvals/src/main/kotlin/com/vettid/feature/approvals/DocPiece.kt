package com.vettid.feature.approvals

/**
 * One row of a document on its screen (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents"), as tokens drawn and read the
 * same way: the app's own punctuation, the document's text, markers. As vettid-ios #300's `DocPiece`: a value or a raw
 * view's line is cut into pieces of at most [ShownText.PIECE_CHARACTERS] characters, each drawn as a row of its own (so a
 * 65 KB document is drawn a few rows at a time); a cut never falls inside a marker, an escape or a character, and a piece
 * after the first starts with ↳ (the piece cut ends with it), which no text can draw (it is a marker there).
 */
data class DocPiece(
    val tokens: List<Token>,
    /** A raw view's line's level (§10.4: the document object is level 1), on its first piece only. */
    val level: Int?,
    /** A piece after the first of its value or line. */
    val continuation: Boolean,
) {
    sealed interface Token {
        /** The app's punctuation (quotation marks, `: `, `,`, brackets, ↳). */
        data class Punct(val text: String) : Token

        /** A raw view's indent: this many levels, drawn as spaces, not read. */
        data class Indent(val levels: Int) : Token

        /** A key's ([key]) or string's isolate (U+2068 … U+2069): drawn, not read. */
        data class Open(val key: Boolean) : Token

        data class Close(val key: Boolean) : Token

        /** The document's text, as drawn (a raw view's with its `"` and `\` escaped). */
        data class Content(val text: String) : Token

        /** A number, `true`, `false` or `null` of the raw view: drawn and read as it is, never between quotation marks. */
        data class Literal(val text: String) : Token

        data class Marker(val codePoint: Int) : Token

        /** A kept line break (Motion, Quorum's text). */
        data object LineBreak : Token
    }

    /** How many characters the piece draws. */
    val length: Int get() = tokens.sumOf { length(it) }

    companion object {
        /** The continuation marks, drawn: at the start of a piece after a cut, and at the end of the piece cut. */
        const val CONTINUED = "\u21B3 "
        const val CONTINUES = " \u21B3"

        /** The raw view's closing punctuation, kept with what it closes past the bound. */
        private val CLOSING = setOf("\"", "]", "}", ",")

        internal fun length(t: Token): Int = when (t) {
            is Token.Punct -> Graphemes(t.text).count
            is Token.Content -> Graphemes(t.text).count
            is Token.Literal -> t.text.length
            is Token.Indent -> 2 * t.levels
            is Token.Marker -> ShownText.marker(t.codePoint).length
            Token.LineBreak -> 1
            is Token.Open, is Token.Close -> 0
        }

        /**
         * A value under a form's label, in pieces: where it is cut inside a paragraph, the piece ends with ↳ and the next
         * begins with it (a piece that starts a paragraph does not).
         */
        fun value(t: ShownText, max: Int = ShownText.PIECE_CHARACTERS, maxBreaks: Int = Int.MAX_VALUE): List<DocPiece> {
            val pieces = t.pieces(max - CONTINUED.length - CONTINUES.length, maxBreaks)
            return pieces.indices.map { i ->
                val piece = pieces[i]
                val cut = i + 1 < pieces.size && pieces[i + 1].continues
                val tokens = mutableListOf<Token>()
                if (piece.continues) tokens += Token.Punct(CONTINUED)
                for (run in piece.text.runs) {
                    tokens += when (run) {
                        is ShownText.Run.Text -> Token.Content(run.text)
                        is ShownText.Run.Marker -> Token.Marker(run.codePoint)
                        ShownText.Run.LineBreak -> Token.LineBreak
                    }
                }
                if (cut) tokens += Token.Punct(CONTINUES)
                DocPiece(tokens, level = null, continuation = piece.continues)
            }
        }

        /** A raw view's line as tokens: its indent, its key and value, brackets and comma. */
        fun tokens(line: DocumentPresentation.RawLine): List<Token> {
            val t = mutableListOf<Token>(Token.Indent(line.depth))
            line.key?.let {
                t += Token.Punct("\"")
                t += isolated(it, key = true)
                t += Token.Punct("\": ")
            }
            when (val v = line.value) {
                is DocumentPresentation.RawValue.Str -> {
                    t += Token.Punct("\"")
                    t += isolated(v.text, key = false)
                    t += Token.Punct("\"")
                }
                is DocumentPresentation.RawValue.Number -> t += Token.Literal(v.raw)
                is DocumentPresentation.RawValue.Bool -> t += Token.Literal(if (v.value) "true" else "false")
                DocumentPresentation.RawValue.Null -> t += Token.Literal("null")
                is DocumentPresentation.RawValue.Open -> t += Token.Punct(if (v.container == Container.OBJECT) "{" else "[")
                is DocumentPresentation.RawValue.Close -> t += Token.Punct(if (v.container == Container.OBJECT) "}" else "]")
                is DocumentPresentation.RawValue.Empty -> t += Token.Punct(if (v.container == Container.OBJECT) "{}" else "[]")
            }
            if (line.comma) t += Token.Punct(",")
            return t
        }

        private fun isolated(s: ShownText, key: Boolean): List<Token> = buildList {
            add(Token.Open(key))
            for (run in s.runs) {
                add(
                    when (run) {
                        is ShownText.Run.Text -> Token.Content(run.text)
                        is ShownText.Run.Marker -> Token.Marker(run.codePoint)
                        ShownText.Run.LineBreak -> Token.Marker('\n'.code)
                    },
                )
            }
            add(Token.Close(key))
        }

        /**
         * A raw view's line in pieces of at most [max] characters (closing punctuation of up to 3 may go past it). A piece
         * cut inside a key or string closes its isolate and ends with ↳; the next starts with ↳ and reopens the isolate. A
         * cut never leaves an isolate empty: an opening quotation mark and isolate with nothing after them go on to the
         * next piece.
         */
        fun raw(line: DocumentPresentation.RawLine, max: Int = ShownText.PIECE_CHARACTERS): List<DocPiece> {
            val cutter = Cutter(line.depth + 1, max)
            for (token in tokens(line)) cutter.add(token)
            return cutter.finish()
        }

        /** Whether [i] falls between a `\` and the character it escapes (in a raw view's text every `\` starts an escape). */
        internal fun insideEscape(g: Graphemes, i: Int): Boolean {
            var slashes = 0
            var j = i
            while (j > 0 && g.at(j - 1) == "\\") {
                slashes++
                j--
            }
            return slashes % 2 == 1
        }
    }

    /** Cuts a line's tokens into pieces. */
    private class Cutter(val level: Int, val max: Int) {
        val pieces = mutableListOf<DocPiece>()
        var cur = mutableListOf<Token>()
        var n = 0
        /** The isolate the piece is inside, if any. */
        var isolate: Token.Open? = null

        /** Nothing but the continuation mark and a carried opening (or nothing) in the piece yet. */
        val fresh: Boolean get() = cur.none { it is Token.Content || it is Token.Literal || it is Token.Marker }

        fun close() {
            // An isolate opened with nothing in it yet goes on to the next piece, with its quotation mark.
            val carry = mutableListOf<Token>()
            val last = cur.lastOrNull()
            if (last is Token.Open) {
                cur.removeAt(cur.lastIndex)
                carry += last
                if (cur.lastOrNull() == Token.Punct("\"")) {
                    cur.removeAt(cur.lastIndex)
                    carry.add(0, Token.Punct("\""))
                }
            }
            val open = isolate
            if (open != null && carry.isEmpty()) cur += Token.Close(open.key)
            cur += Token.Punct(CONTINUES)
            pieces += DocPiece(cur, if (pieces.isEmpty()) level else null, continuation = pieces.isNotEmpty())
            cur = mutableListOf<Token>(Token.Punct(CONTINUED)).apply {
                addAll(carry.ifEmpty { listOfNotNull(open) })
            }
            n = cur.sumOf { length(it) }
        }

        @Suppress("CyclomaticComplexMethod", "ComplexCondition") // one case per kind of token, and the cut of a text in place
        fun add(token: Token) {
            when (token) {
                is Token.Open -> isolate = token
                is Token.Close -> isolate = null
                else -> Unit
            }
            if (token !is Token.Content) {
                val len = length(token)
                // Closing punctuation stays with what it closes.
                val closing = token is Token.Punct && token.text in CLOSING
                if (len > 0 && n + len > max && !fresh && !closing) close()
                cur += token
                n += len
                return
            }
            var rest = Graphemes(token.text)
            while (!rest.isEmpty) {
                val room = maxOf(max - n - CONTINUES.length, 1)
                if (rest.count <= room) {
                    n += rest.count
                    cur += Token.Content(rest.text)
                    return
                }
                var cut = cutBefore(rest, room)
                if (cut == 0) {
                    if (!fresh) {
                        close()
                        continue
                    }
                    // Nothing fits before the bound in a piece of its own: cut at the bound, an escape kept whole.
                    cut = if (insideEscape(rest, room)) room + 1 else room
                }
                cur += Token.Content(rest.prefix(cut))
                n += cut
                rest = rest.drop(cut)
                // Nothing left of the string after a forced cut: the piece goes on (its isolate closes as it ends).
                if (!rest.isEmpty) close()
            }
        }

        /** Where to cut [g] no later than [limit]: after its last white space, else at [limit]; never inside an escape. */
        private fun cutBefore(g: Graphemes, limit: Int): Int {
            var c = g.lastWhitespaceBefore(limit)?.let { it + 1 } ?: limit
            if (insideEscape(g, c)) c -= 1
            return c
        }

        fun finish(): List<DocPiece> {
            val more = cur.any { it != Token.Punct(CONTINUED) }
            if (cur.isNotEmpty() && (more || pieces.isEmpty())) {
                pieces += DocPiece(cur, if (pieces.isEmpty()) level else null, continuation = pieces.isNotEmpty())
            }
            return pieces
        }
    }
}
