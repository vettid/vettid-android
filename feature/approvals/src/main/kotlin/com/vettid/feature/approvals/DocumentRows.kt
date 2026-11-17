package com.vettid.feature.approvals

import androidx.annotation.StringRes
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.buildAnnotatedString

/**
 * One row of an approval's document on its screen (VAULT-MESSAGING 0.89.0 §10.4 "What the app shows"), drawn as one item of
 * a lazy list: the app's own labels and words, or a piece of the document's text in a value block. Every value of any
 * length is cut into pieces ([DocPiece]), so that the largest document is drawn a few rows at a time.
 */
sealed interface DocRow {
    /** The row holds the document's own text (a piece of a value or of the raw view), not the app's words. */
    val isContent: Boolean get() = this is Value || this is Raw

    /** A part's label, as a heading. */
    data class Heading(@StringRes val label: Int) : DocRow

    /** A label inside a form. */
    data class Label(@StringRes val label: Int) : DocRow

    /** The app's own line under a value. */
    data class Note(@StringRes val text: Int) : DocRow

    /** The app's title for a known action, in a block of its own. */
    data class Title(val action: DocumentPresentation.KnownAction) : DocRow

    /** "<name> asks for something this app doesn't know. Read all of it before you approve." */
    data object UnknownWarning : DocRow

    /** A piece of a value (the document's text) in its block: [first] and [last] of its block. */
    data class Value(val piece: DocPiece, val first: Boolean, val last: Boolean) : DocRow

    /** A piece of a raw view's line (left-to-right) in the raw view's block. */
    data class Raw(val piece: DocPiece, val first: Boolean, val last: Boolean) : DocRow

    /** "None", in the label style, outside any value block (an empty Motion). */
    data object NoneLabel : DocRow

    /** "Abstain", with "added by the service". */
    data object Abstain : DocRow

    /** Quorum's ballots, in a block. */
    data class Ballots(val count: Long) : DocRow

    /** A word of the app's (Binding, Advisory, Yes, No), in a block. */
    data class Word(@StringRes val text: Int) : DocRow

    /** A time of a form, in a block: the member's zone, then UTC. */
    data class Time(val seconds: Long) : DocRow
}

/**
 * How a document is cut into pieces: at most [max] characters and [maxBreaks] kept line breaks to a piece. As vettid-ios
 * #300: the usual cutting; with a screen reader (TalkBack's touch exploration) 30 characters and 4 kept line breaks, so
 * that moving from one node to the next brings every part on screen, halved for each row still taller than the list,
 * down to [SMALLEST] characters and 1 break, below which the screen says it can't show the request at this text size.
 */
data class DocCutting(val max: Int, val maxBreaks: Int) {
    /** No smaller cutting is made. */
    val smallest: Boolean get() = max <= SMALLEST

    companion object {
        val USUAL = DocCutting(ShownText.PIECE_CHARACTERS, Int.MAX_VALUE)

        /** The fewest characters a piece is cut to. */
        const val SMALLEST = 10
        private const val READER_MAX = 30
        private const val READER_BREAKS = 4
        private const val MAX_BREAKS_SHRUNK = 8

        /** The cutting with a screen reader on or not, halved [shrink] times. */
        fun of(screenReader: Boolean, shrink: Int): DocCutting = when {
            !screenReader -> USUAL
            shrink <= 0 -> DocCutting(READER_MAX, READER_BREAKS)
            else -> DocCutting(
                maxOf(READER_MAX shr shrink, SMALLEST),
                maxOf(minOf(READER_BREAKS, MAX_BREAKS_SHRUNK) shr shrink, 1),
            )
        }
    }
}

/** The rows of a document, in the order of §10.4 "What the app shows". */
object DocumentRows {
    fun of(p: DocumentPresentation, c: DocCutting = DocCutting.USUAL): List<DocRow> {
        val r = mutableListOf<DocRow>(DocRow.Heading(R.string.approvals_doc_asked))
        when (val a = p.asked) {
            is DocumentPresentation.Asked.Known -> r += DocRow.Title(a.action)
            is DocumentPresentation.Asked.Unknown -> {
                r += value(ShownText.of(a.action), c)
                r += DocRow.UnknownWarning
            }
        }
        p.context?.let {
            r += DocRow.Heading(R.string.approvals_doc_description)
            r += value(it, c)
            r += DocRow.Note(R.string.approvals_doc_description_note)
        }
        r += DocRow.Heading(R.string.approvals_doc_summary)
        r += value(p.summary, c)
        when (val q = p.request) {
            is DocumentPresentation.Request.Proposal -> {
                r += DocRow.Heading(R.string.approvals_doc_request)
                r += proposal(q.form, c)
            }
            is DocumentPresentation.Request.Extend -> {
                r += DocRow.Heading(R.string.approvals_doc_request)
                r += extend(q.form, c)
            }
            is DocumentPresentation.Request.Raw -> {
                r += DocRow.Heading(R.string.approvals_doc_details)
                r += raw(q.lines, c.max)
            }
        }
        return r
    }

    /** A value as rows of pieces that join into one block. */
    fun value(t: ShownText, c: DocCutting = DocCutting.USUAL): List<DocRow> {
        val pieces = DocPiece.value(t, c.max, c.maxBreaks)
        return pieces.mapIndexed { i, p -> DocRow.Value(p, first = i == 0, last = i == pieces.lastIndex) }
    }

    private fun proposal(f: DocumentPresentation.ProposalForm, c: DocCutting): List<DocRow> = buildList {
        add(DocRow.Label(R.string.approvals_doc_field_title))
        addAll(value(f.title, c))
        add(DocRow.Label(R.string.approvals_doc_field_motion))
        f.text?.let { addAll(value(it, c)) } ?: add(DocRow.NoneLabel)
        add(DocRow.Label(R.string.approvals_doc_field_choices))
        for (choice in f.choices) addAll(value(choice, c))
        add(DocRow.Abstain)
        add(DocRow.Label(R.string.approvals_doc_field_quorum))
        add(DocRow.Ballots(f.quorumBallots))
        f.quorumText?.let { addAll(value(it, c)) }
        add(DocRow.Word(if (f.binding) R.string.approvals_doc_binding else R.string.approvals_doc_advisory))
        add(DocRow.Label(R.string.approvals_doc_field_removed_may_vote))
        add(DocRow.Word(if (f.removedMayVote) R.string.approvals_doc_yes else R.string.approvals_doc_no))
        add(DocRow.Label(R.string.approvals_doc_field_opens))
        add(DocRow.Time(f.opensAt))
        add(DocRow.Label(R.string.approvals_doc_field_closes))
        add(DocRow.Time(f.closesAt))
        add(DocRow.Label(R.string.approvals_doc_field_outage_rule))
        addAll(value(f.outageRule, c))
    }

    private fun extend(f: DocumentPresentation.ExtendForm, c: DocCutting): List<DocRow> = buildList {
        add(DocRow.Label(R.string.approvals_doc_field_vote))
        addAll(value(f.proposalId, c))
        add(DocRow.Label(R.string.approvals_doc_field_new_close))
        add(DocRow.Time(f.closesAt))
        add(DocRow.Label(R.string.approvals_doc_field_outage_from))
        add(DocRow.Time(f.outageFrom))
        add(DocRow.Label(R.string.approvals_doc_field_outage_to))
        add(DocRow.Time(f.outageTo))
        add(DocRow.Label(R.string.approvals_doc_field_reported_by))
        addAll(value(f.outageSource, c))
    }

    /** The raw view: one row per piece of a line (one per line unless a line is longer than a piece), as one block. */
    fun raw(lines: List<DocumentPresentation.RawLine>, max: Int = ShownText.PIECE_CHARACTERS): List<DocRow> {
        val pieces = lines.flatMap { DocPiece.raw(it, max) }
        return pieces.mapIndexed { i, p -> DocRow.Raw(p, first = i == 0, last = i == pieces.lastIndex) }
    }
}

/**
 * What TalkBack reads of a piece of the document, as the iPhone reads it (owner decision 2026-11-17, as vettid-ios #300's
 * `Spoken`): the piece as drawn, so a marker is read as drawn ("⟨U+202E⟩"); every string of the document (a raw view's key
 * or string, a value the service wrote under a form's label) between quotation marks, the drawn ones or, for a value, ones
 * added for speech, with its own `"` and `\` escaped as JSON writes them (a raw view's text already is); a raw view's line
 * with its level first ("level 2, "). The quotation marks, the markers and the app's punctuation (`: `, `,`, brackets, the
 * continuation marks) are [VerbatimTtsAnnotation]s, so TalkBack voices them at any punctuation level and no text can be
 * heard as the app's words or as another line. Numbers, `true`, `false`, `null` and the app's own words are read plainly.
 * Android has no counterpart of iOS's separate pitch for the document's text, so none is set.
 */
object DocSpoken {
    private const val QUOTE = "\""

    /** [p] as read: a raw view's piece when [raw], else a piece of a value the service wrote. [level]: "level N". */
    @Suppress("CyclomaticComplexMethod") // one case per kind of token
    fun of(p: DocPiece, raw: Boolean, level: (Int) -> String): AnnotatedString = buildAnnotatedString {
        p.level?.let {
            append(level(it))
            append(", ")
        }
        fun verbatim(s: String) {
            pushTtsAnnotation(VerbatimTtsAnnotation(s))
            append(s)
            pop()
        }
        var previous: DocPiece.Token? = null
        // A value's piece: opened by a spoken quotation mark (after a continuation mark), closed by one unless it is cut.
        val tokens = p.tokens
        if (!raw && tokens.firstOrNull() != DocPiece.Token.Punct(DocPiece.CONTINUED)) verbatim(QUOTE)
        for (t in tokens) {
            when (t) {
                is DocPiece.Token.Punct -> {
                    verbatim(t.text)
                    if (!raw && t.text == DocPiece.CONTINUED) verbatim(QUOTE)
                }
                // A raw piece that goes on inside a string reopens its quotation mark (no drawn one there).
                is DocPiece.Token.Open -> if (previous == DocPiece.Token.Punct(DocPiece.CONTINUED)) verbatim(QUOTE)
                is DocPiece.Token.Close, is DocPiece.Token.Indent -> Unit
                is DocPiece.Token.Content -> append(if (raw) t.text else escaped(t.text))
                is DocPiece.Token.Literal -> append(t.text)
                is DocPiece.Token.Marker -> verbatim(ShownText.marker(t.codePoint))
                DocPiece.Token.LineBreak -> append('\n')
            }
            previous = t
        }
        if (!raw && tokens.lastOrNull() != DocPiece.Token.Punct(DocPiece.CONTINUES)) verbatim(QUOTE)
    }

    /** Code point by code point, as [ShownText] escapes the raw view: `"` and `\` get a `\` before them. */
    fun escaped(s: String): String = buildString {
        for (ch in s) {
            if (ch == '"' || ch == '\\') append('\\')
            append(ch)
        }
    }
}
