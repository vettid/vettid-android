// Test data: documents and expected lines stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import com.vettid.feature.approvals.DocumentPresentation.Asked
import com.vettid.feature.approvals.DocumentPresentation.KnownAction
import com.vettid.feature.approvals.DocumentPresentation.Request
import com.vettid.feature.approvals.ShownText.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.util.Locale

/**
 * VAULT-MESSAGING 0.89.0 §10.4 "Approval documents": what the screen shows of a document, in pure rules. Robolectric, for
 * Android's Unicode data (android.icu answers defaults on a plain JVM, which would pass nothing).
 */
@RunWith(RobolectricTestRunner::class)
class DocumentPresentationTest {
    private fun markers(s: String, lineBreaks: Boolean = false) = ShownText.of(s, lineBreaks).runs.filterIsInstance<Run.Marker>().map { it.codePoint }

    private fun present(json: String) = DocumentPresentation.of(DocumentSamples.document(json), "Approve this")!!

    @Test
    fun everyCategoryOfSection104IsAMarker() {
        val cases = mapOf(
            "Cc" to 0x0007, "U+0085" to 0x0085, "Cf (RLO)" to 0x202E, "Cf (FSI)" to 0x2068, "Cf (LRM)" to 0x200E, "Zl" to 0x2028, "Zp" to 0x2029,
            "Co" to 0xE000, "Cn" to 0x0378, "Hangul filler" to 0x3164, "variation selector" to 0xFE0F, "ZWJ" to 0x200D, "ZWNJ" to 0x200C,
            "Zs NBSP" to 0x00A0, "Zs ideographic" to 0x3000, "tag" to 0xE0041,
            // Chosen, as iOS: the marker's brackets and a look-alike, the blank glyphs, the continuation mark.
            "marker bracket" to 0x27E8, "closing bracket" to 0x27E9, "look-alike" to 0x2329, "braille blank" to 0x2800, "object replacement" to 0xFFFC, "continuation" to 0x21B3,
        )
        for ((name, cp) in cases) {
            val s = "a" + String(Character.toChars(cp)) + "b"
            assertEquals(name, listOf(cp), markers(s))
            assertEquals(name, "a" + ShownText.marker(cp) + "b", ShownText.of(s).plain)
        }
        assertEquals("\u27E8U+202E\u27E9", ShownText.marker(0x202E))
        assertEquals("\u27E8U+E0041\u27E9", ShownText.marker(0xE0041))
        // A plain space and ordinary letters, digits and punctuation are themselves.
        assertTrue(markers("Pay 40, now: \"ok\" é ü 日本 שלום").isEmpty())
    }

    @Test
    fun marksPastTheSecondAndMarksWithoutABaseAreMarkers() {
        assertTrue(markers("e\u0301\u0302").isEmpty())
        assertEquals(listOf(0x0303, 0x0304), markers("e\u0301\u0302\u0303\u0304"))
        // A mark at the start of a run, or after a marker, has no base of its own.
        assertEquals(listOf(0x0301), markers("\u0301a"))
        assertEquals(listOf(0x202E, 0x0301), markers("a\u202E\u0301"))
        // An emoji modifier with no base.
        assertEquals(listOf(0x1F3FD), markers(String(Character.toChars(0x1F3FD))))
    }

    @Test
    fun lineBreaksAreKeptOnlyWhereTheFieldKeepsThemAtMostTwoInARow() {
        assertEquals(listOf(0x0A), markers("a\nb"))
        val kept = ShownText.of("a\n\n\nb", lineBreaks = true).runs
        assertEquals(listOf(Run.Text("a"), Run.LineBreak, Run.LineBreak, Run.Marker(0x0A), Run.Text("b")), kept)
    }

    @Test
    fun theRawViewEscapesQuotesAndBackslashes() {
        assertEquals("say \\\"hi\\\" \\\\ ok", ShownText.of("say \"hi\" \\ ok", escaped = true).plain)
        assertEquals("say \"hi\"", ShownText.of("say \"hi\"").plain)
    }

    @Test
    fun coverageCountsOnlyWhatJoinsFromTheTop() {
        var c = SeenCoverage().saw(0f..100f, 300f)
        assertFalse(c.whole)
        // A jump to the end leaves a gap.
        c = c.saw(200f..300f, 300f)
        assertFalse(c.whole)
        assertEquals(100f, c.seenTo)
        c = c.saw(90f..210f, 300f)
        assertTrue(c.whole)
        // Another height (another text size) starts again.
        assertFalse(c.saw(0f..100f, 400f).whole)
        assertFalse("nothing seen of a row of no height", SeenCoverage().saw(null, 0f).whole)
    }

    @Test
    fun aMatchingPayloadIsItsForm() {
        val p = present(DocumentSamples.EXTEND)
        assertEquals(Asked.Known(KnownAction.VOTE_EXTEND), p.asked)
        val f = (p.request as Request.Extend).form
        assertEquals("p-17", f.proposalId.plain)
        assertEquals(1796720400L, f.closesAt)
        assertEquals("relay", f.outageSource.plain)
        val q = (present(DocumentSamples.PROPOSAL).request as Request.Proposal).form
        assertEquals(listOf("Yes", "No"), q.choices.map { it.plain })
        assertEquals("Line one\nLine two", q.text!!.plain)
        assertNull("an empty quorum text is not shown", q.quorumText)
        assertEquals(1L, q.quorumBallots)
    }

    @Test
    fun anyDifferenceAtAnyDepthIsTheRawView() {
        val e = DocumentSamples.EXTEND
        val variants = listOf(
            "extra member" to e.replace("\"proposal_id\":\"p-17\"", "\"proposal_id\":\"p-17\",\"x\":1"),
            "missing member" to e.replace("\"proposal_id\":\"p-17\",", ""),
            "wrong type" to e.replace("\"proposal_id\":\"p-17\"", "\"proposal_id\":17"),
            "extra member at depth" to e.replace("\"source\":\"relay\"", "\"source\":\"relay\",\"y\":true"),
            "missing member at depth" to e.replace(",\"to\":1796703600", ""),
            "wrong type at depth" to e.replace("\"from\":1796700000", "\"from\":\"1796700000\""),
            "time past 9999" to e.replace("1796720400", "253402300800"),
        )
        for ((name, json) in variants) {
            val p = present(json)
            assertEquals(name, Asked.Known(KnownAction.VOTE_EXTEND), p.asked)
            assertTrue(name, p.request is Request.Raw)
        }
        val pr = DocumentSamples.PROPOSAL
        for (json in listOf(pr.replace("{\"label\":\"No\"}", "{\"label\":\"No\",\"x\":1}"), pr.replace("\"min_ballots\":1", "\"min_ballots\":true"), pr.replace("\"binding\":true", "\"binding\":1"))) {
            assertTrue(json, present(json).request is Request.Raw)
        }
        // A known action without a form, and an unknown one: the raw view, the unknown named.
        assertTrue(present(DocumentSamples.big(10)).request is Request.Raw)
        val u = present("""{"action":"pay.out","payload":{},"summary":""}""")
        assertEquals(Asked.Unknown("pay.out"), u.asked)
        assertTrue(u.request is Request.Raw)
    }

    @Test
    fun theRawViewIsTheWholeDocumentALineToAMember() {
        val p = present("""{"action":"pay.out","payload":{"a":[1,"10",true,null,{},[]],"k\"":"v"},"summary":"S"}""")
        val lines = (p.request as Request.Raw).lines.flatMap { DocPiece.raw(it) }.map { piece ->
            piece.tokens.joinToString("") {
                when (it) {
                    is DocPiece.Token.Punct -> it.text
                    is DocPiece.Token.Content -> it.text
                    is DocPiece.Token.Literal -> it.text
                    is DocPiece.Token.Indent -> "  ".repeat(it.levels)
                    is DocPiece.Token.Marker -> ShownText.marker(it.codePoint)
                    is DocPiece.Token.Open -> "<"
                    is DocPiece.Token.Close -> ">"
                    DocPiece.Token.LineBreak -> "\n"
                }
            }
        }
        assertEquals(
            listOf(
                "{",
                "  \"<action>\": \"<pay.out>\",",
                "  \"<payload>\": {",
                "    \"<a>\": [",
                "      1,", "      \"<10>\",", "      true,", "      null,", "      {},", "      []",
                "    ],",
                "    \"<k\\\">\": \"<v>\"",
                "  },",
                "  \"<summary>\": \"<S>\"",
                "}",
            ),
            lines,
        )
        assertEquals(listOf(1, 2, 2, 3, 4), (p.request as Request.Raw).lines.take(5).map { DocPiece.raw(it).first().level })
    }

    @Test
    fun longValuesAreCutIntoPiecesThatJoinBackWhole() {
        val s = "word ".repeat(200) + "end"
        val pieces = DocPiece.value(ShownText.of(s))
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= ShownText.PIECE_CHARACTERS })
        val joined = pieces.joinToString("") { p -> p.tokens.filterIsInstance<DocPiece.Token.Content>().joinToString("") { it.text } }
        assertEquals(s, joined)
        assertTrue(pieces.drop(1).all { it.continuation && it.tokens.first() == DocPiece.Token.Punct(DocPiece.CONTINUED) })
        // A raw line with a long string: every piece but the last ends with the mark, inside its own isolate.
        val raw = DocPiece.raw(DocumentPresentation.RawLine(1, ShownText.of("k"), DocumentPresentation.RawValue.Str(ShownText.of("x".repeat(500), escaped = true)), false))
        assertTrue(raw.size > 3)
        assertEquals(2, raw.first().level)
        assertTrue(raw.dropLast(1).all { it.tokens.last() == DocPiece.Token.Punct(DocPiece.CONTINUES) })
        assertEquals("k" + "x".repeat(500), raw.joinToString("") { p -> p.tokens.filterIsInstance<DocPiece.Token.Content>().joinToString("") { it.text } })
        // Never between a backslash and what it escapes.
        val esc = DocPiece.raw(DocumentPresentation.RawLine(1, null, DocumentPresentation.RawValue.Str(ShownText.of("\"".repeat(300), escaped = true)), false))
        for (p in esc) for (c in p.tokens.filterIsInstance<DocPiece.Token.Content>()) assertEquals(0, c.text.length % 2)
    }

    @Test
    fun spokenLinesNameTheirLevelAndTellStringsFromNumbers() {
        val t = DocSpoken.Texts(level = { "level $it" }, marker = { "code point $it" }, string = { "$it, text" })
        fun spoken(v: DocumentPresentation.RawValue) = DocSpoken.of(DocPiece.raw(DocumentPresentation.RawLine(1, ShownText.of("amount"), v, true)).single(), t)
        assertEquals("level 2, amount: 10, text", spoken(DocumentPresentation.RawValue.Str(ShownText.of("10"))))
        assertEquals("level 2, amount: 10", spoken(DocumentPresentation.RawValue.Number("10")))
        assertEquals("level 2, amount: a code point U+202E b, text", spoken(DocumentPresentation.RawValue.Str(ShownText.of("a\u202Eb"))))
        // The string's own spaces are kept, never trimmed.
        assertEquals("level 2, amount:  10 , text", spoken(DocumentPresentation.RawValue.Str(ShownText.of(" 10 "))))
    }

    @Test
    fun thePluralQuantityKeepsTheCountsLastDigits() {
        assertEquals(1, pluralQuantity(1))
        assertEquals(12, pluralQuantity(12))
        assertEquals(Int.MAX_VALUE, pluralQuantity(Int.MAX_VALUE.toLong()))
        val q = pluralQuantity(3_000_000_001)
        assertEquals(1, q % 1_000_000_000)
        assertTrue(q > 1)
        assertEquals(1_000_000_000, pluralQuantity(3_000_000_000))
    }

    @Test
    fun timesAreTheMembersZoneNamedThenUtcWithSecondsOnlyWhenNotZero() {
        val t = DocumentTime.shown(1796720400, ZoneId.of("America/New_York"), Locale.US)
        assertTrue(t.local, t.local.contains("2026") && (t.local.contains("EST") || t.local.contains("GMT-5")))
        assertFalse(t.local, t.local.contains(":00:00"))
        assertTrue(t.utc, t.utc.endsWith(" UTC") && t.utc.contains("2026"))
        val s = DocumentTime.shown(1796720407, ZoneId.of("UTC"), Locale.US)
        assertTrue(s.utc, s.utc.contains(":07"))
        assertEquals("9999", Regex("9999").find(DocumentTime.shown(DocumentPresentation.MAX_TIME, ZoneId.of("UTC"), Locale.US).utc)?.value)
    }

    @Test
    fun rowsFollowTheSpecsOrder() {
        val rows = DocumentRows.of(present(DocumentSamples.EXTEND))
        val headings = rows.filterIsInstance<DocRow.Heading>().map { it.label }
        assertEquals(listOf(R.string.approvals_doc_asked, R.string.approvals_doc_description, R.string.approvals_doc_summary, R.string.approvals_doc_request), headings)
        assertEquals(DocRow.Title(KnownAction.VOTE_EXTEND), rows[1])
        val raw = DocumentRows.of(present("""{"action":"pay.out","payload":{},"summary":""}"""))
        assertTrue(DocRow.UnknownWarning in raw)
        assertEquals(R.string.approvals_doc_details, raw.filterIsInstance<DocRow.Heading>().last().label)
        assertTrue("no label in a raw view", raw.none { it is DocRow.Label })
    }
}
