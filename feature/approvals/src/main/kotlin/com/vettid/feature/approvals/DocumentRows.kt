package com.vettid.feature.approvals

import androidx.annotation.StringRes

/**
 * One row of an approval's document on its screen (VAULT-MESSAGING 0.89.0 §10.4 "What the app shows"), drawn as one item of
 * a lazy list: the app's own labels and words, or a piece of the document's text in a value block. Every value of any
 * length is cut into pieces ([DocPiece]), so that the largest document is drawn a few rows at a time.
 */
sealed interface DocRow {
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

/** The rows of a document, in the order of §10.4 "What the app shows". */
object DocumentRows {
    fun of(p: DocumentPresentation, max: Int = ShownText.PIECE_CHARACTERS): List<DocRow> {
        val r = mutableListOf<DocRow>(DocRow.Heading(R.string.approvals_doc_asked))
        when (val a = p.asked) {
            is DocumentPresentation.Asked.Known -> r += DocRow.Title(a.action)
            is DocumentPresentation.Asked.Unknown -> {
                r += value(ShownText.of(a.action), max)
                r += DocRow.UnknownWarning
            }
        }
        p.context?.let {
            r += DocRow.Heading(R.string.approvals_doc_description)
            r += value(it, max)
            r += DocRow.Note(R.string.approvals_doc_description_note)
        }
        r += DocRow.Heading(R.string.approvals_doc_summary)
        r += value(p.summary, max)
        when (val q = p.request) {
            is DocumentPresentation.Request.Proposal -> {
                r += DocRow.Heading(R.string.approvals_doc_request)
                r += proposal(q.form, max)
            }
            is DocumentPresentation.Request.Extend -> {
                r += DocRow.Heading(R.string.approvals_doc_request)
                r += extend(q.form, max)
            }
            is DocumentPresentation.Request.Raw -> {
                r += DocRow.Heading(R.string.approvals_doc_details)
                r += raw(q.lines, max)
            }
        }
        return r
    }

    /** A value as rows of pieces that join into one block. */
    fun value(t: ShownText, max: Int = ShownText.PIECE_CHARACTERS): List<DocRow> {
        val pieces = DocPiece.value(t, max)
        return pieces.mapIndexed { i, p -> DocRow.Value(p, first = i == 0, last = i == pieces.lastIndex) }
    }

    private fun proposal(f: DocumentPresentation.ProposalForm, max: Int): List<DocRow> = buildList {
        add(DocRow.Label(R.string.approvals_doc_field_title))
        addAll(value(f.title, max))
        add(DocRow.Label(R.string.approvals_doc_field_motion))
        f.text?.let { addAll(value(it, max)) } ?: add(DocRow.NoneLabel)
        add(DocRow.Label(R.string.approvals_doc_field_choices))
        for (c in f.choices) addAll(value(c, max))
        add(DocRow.Abstain)
        add(DocRow.Label(R.string.approvals_doc_field_quorum))
        add(DocRow.Ballots(f.quorumBallots))
        f.quorumText?.let { addAll(value(it, max)) }
        add(DocRow.Word(if (f.binding) R.string.approvals_doc_binding else R.string.approvals_doc_advisory))
        add(DocRow.Label(R.string.approvals_doc_field_removed_may_vote))
        add(DocRow.Word(if (f.removedMayVote) R.string.approvals_doc_yes else R.string.approvals_doc_no))
        add(DocRow.Label(R.string.approvals_doc_field_opens))
        add(DocRow.Time(f.opensAt))
        add(DocRow.Label(R.string.approvals_doc_field_closes))
        add(DocRow.Time(f.closesAt))
        add(DocRow.Label(R.string.approvals_doc_field_outage_rule))
        addAll(value(f.outageRule, max))
    }

    private fun extend(f: DocumentPresentation.ExtendForm, max: Int): List<DocRow> = buildList {
        add(DocRow.Label(R.string.approvals_doc_field_vote))
        addAll(value(f.proposalId, max))
        add(DocRow.Label(R.string.approvals_doc_field_new_close))
        add(DocRow.Time(f.closesAt))
        add(DocRow.Label(R.string.approvals_doc_field_outage_from))
        add(DocRow.Time(f.outageFrom))
        add(DocRow.Label(R.string.approvals_doc_field_outage_to))
        add(DocRow.Time(f.outageTo))
        add(DocRow.Label(R.string.approvals_doc_field_reported_by))
        addAll(value(f.outageSource, max))
    }

    /** The raw view: one row per piece of a line (one per line unless a line is longer than a piece), as one block. */
    fun raw(lines: List<DocumentPresentation.RawLine>, max: Int = ShownText.PIECE_CHARACTERS): List<DocRow> {
        val pieces = lines.flatMap { DocPiece.raw(it, max) }
        return pieces.mapIndexed { i, p -> DocRow.Raw(p, first = i == 0, last = i == pieces.lastIndex) }
    }
}

/**
 * What TalkBack reads of a piece of the document (accessibility labels owner-approved 2026-11-17): a marker as "code point
 * U+202E"; a raw view's line with its level first ("level 2, amount: 10, text"), each string followed by ", text" so that
 * the string "10" and the number 10 sound different; the app's continuation marks, quotation marks and the line's comma are
 * not read. The document's spaces are kept as they are (never trimmed); TalkBack does not voice a space, so a leading or
 * trailing space is in the description but not heard (no words are added for it). Android has no counterpart of iOS's
 * separate pitch for the document's text, so none is set.
 */
object DocSpoken {
    /** The three accessibility texts, as the screen has them from its resources. */
    class Texts(val level: (Int) -> String, val marker: (String) -> String, val string: (String) -> String)

    fun markerName(codePoint: Int): String = "U+%04X".format(codePoint)

    @Suppress("CyclomaticComplexMethod") // one case per kind of token
    fun of(p: DocPiece, t: Texts): String {
        val out = StringBuilder()
        p.level?.let { out.append(t.level(it)).append(", ") }
        var string: StringBuilder? = null
        fun sink(): StringBuilder = string ?: out
        for (token in p.tokens) {
            when (token) {
                is DocPiece.Token.Punct -> when (token.text) {
                    DocPiece.CONTINUED, DocPiece.CONTINUES, "\"", "," -> Unit
                    "\": " -> out.append(": ")
                    else -> out.append(token.text)
                }
                is DocPiece.Token.Open -> if (!token.key) string = StringBuilder()
                is DocPiece.Token.Close -> string?.let {
                    out.append(t.string(it.toString()))
                    string = null
                }
                is DocPiece.Token.Content -> sink().append(token.text)
                is DocPiece.Token.Literal -> out.append(token.text)
                is DocPiece.Token.Marker -> sink().append(' ').append(t.marker(markerName(token.codePoint))).append(' ')
                DocPiece.Token.LineBreak -> sink().append('\n')
                is DocPiece.Token.Indent -> Unit
            }
        }
        // A string cut at the piece's end is read as text too.
        string?.let { out.append(t.string(it.toString())) }
        return out.toString()
    }
}
