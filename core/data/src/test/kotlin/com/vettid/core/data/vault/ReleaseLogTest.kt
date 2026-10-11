package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What's new (ANDROID-PLAN 0.1.31): which URL may be fetched, and what of `index.json` is used. */
class ReleaseLogTest {
    private val prod = "https://vettid.org/.well-known/vettid/pcr-manifest.json"
    private val staging = "https://staging.vettid.org/.well-known/vettid/pcr-manifest.json"

    private fun pcr(n: Long) = "%02x".format(n).repeat(48)

    private fun rel(n: Long, notes: String = "https://vettid.org/security/releases/$n/", pcr0: String = pcr(n)) =
        ReleaseView(n, pcr0, "active", null, notes)

    // ---- the URL rules ----

    @Test
    fun theLogUrlIsTheOnlyNotesThatFetch() {
        assertEquals("https://vettid.org/security/releases/index.json", ReleaseLog.indexUrl(prod, rel(5)))
        assertEquals(
            "https://staging.vettid.org/security/releases/index.json",
            ReleaseLog.indexUrl(staging, rel(9, "https://staging.vettid.org/security/releases/9/")),
        )
    }

    @Test
    fun anyOtherNotesMeanNoFetch() {
        val other = listOf(
            "https://github.com/vettid/vettid-vault/releases/tag/staging-s8", // staging S1–S8 today
            "https://vettid.org/security/releases/5", // no trailing slash
            "https://vettid.org/security/releases/6/", // another release's entry
            "https://vettid.org/security/releases/05/",
            "https://vettid.org/security/releases/5/index.html",
            "https://vettid.org/security/releases/5/?x=1",
            "https://vettid.org/security/releases/5/#top",
            "http://vettid.org/security/releases/5/", // not https
            "https://VETTID.org/security/releases/5/",
            "https://vettid.org:8443/security/releases/5/",
            "https://evil.example/security/releases/5/",
            "https://vettid.org.evil.example/security/releases/5/",
            "https://user@vettid.org/security/releases/5/",
            "https://staging.vettid.org/security/releases/5/", // another channel's log
            " https://vettid.org/security/releases/5/",
            "",
        )
        for (n in other) assertNull(n, ReleaseLog.indexUrl(prod, rel(5, n)))
        // The staging build only fetches from staging.vettid.org.
        assertNull(ReleaseLog.indexUrl(staging, rel(5)))
        assertNull(ReleaseLog.indexUrl("not a url", rel(5)))
        assertNull(ReleaseLog.indexUrl(prod, rel(0, "https://vettid.org/security/releases/0/")))
    }

    @Test
    fun theHostIsThePinnedManifests() {
        assertEquals("vettid.org", ReleaseLog.host(prod))
        assertEquals("staging.vettid.org", ReleaseLog.host(staging))
        assertEquals("http://localhost:18080", ReleaseLog.origin("http://localhost:18080/.well-known/vettid/pcr-manifest.json"))
        assertEquals("https://vettid.org", ReleaseLog.origin(prod))
    }

    @Test
    fun onlyHttpsNotesOpen() {
        assertTrue(ReleaseLog.openable("https://vettid.org/security/releases/5/"))
        assertTrue(ReleaseLog.openable("https://github.com/vettid/vettid-vault/releases/tag/staging-s8"))
        assertFalse(ReleaseLog.openable("http://vettid.org/security/releases/5/"))
        assertFalse(ReleaseLog.openable("javascript:alert(1)"))
        assertFalse(ReleaseLog.openable(""))
    }

    // ---- index.json ----

    private fun entry(
        n: Long,
        pcr0: String = pcr(n),
        summary: String = "\"Release $n summary\"",
        changes: String = "[\"one\", \"two\"]",
        security: String = "\"none\"",
        extra: String = "",
    ) = """{"release": $n, "status": "active", "published_at": "2026-10-01T00:00:00Z", "pcr0": "$pcr0", "pcr1": "aa", "pcr2": "bb",
        "seal_key": "arn:aws:kms:x", "notes": "https://vettid.org/security/releases/$n/", "summary": $summary,
        "changes": $changes, "security": $security, "listed": true$extra}"""

    private fun index(vararg entries: String) = """{"serial": 12, "releases": [${entries.joinToString(",")}]}"""

    @Test
    fun theEntryMatchesNumberAndPcr0() {
        val (e, earlier) = ReleaseLog.parse(index(entry(6), entry(5), entry(4)), rel(5), emptyList())!!
        assertEquals(5L, e.release)
        assertEquals("Release 5 summary", e.summary)
        assertEquals(listOf("one", "two"), e.changes)
        assertEquals(ReleaseSecurity.NONE, e.security)
        assertNull(e.securityText)
        assertTrue(earlier.isEmpty())
    }

    @Test
    fun anotherPcr0IsNoEntry() {
        assertNull(ReleaseLog.parse(index(entry(5, pcr0 = pcr(7))), rel(5), emptyList()))
        assertNull(ReleaseLog.parse(index(entry(10, pcr0 = pcr(10).uppercase())), rel(10), emptyList()))
        assertNull(ReleaseLog.parse(index(entry(4)), rel(5), emptyList()))
        // The number as a string is not the number.
        assertNull(ReleaseLog.parse(index(entry(5).replace("\"release\": 5", "\"release\": \"5\"")), rel(5), emptyList()))
    }

    @Test
    fun aDocumentThatIsNoLogIsNoEntry() {
        for (body in listOf("", "null", "[]", "{}", "{\"releases\": {}}", "<html>", "{\"releases\": [1, \"x\"]}")) {
            assertNull(body, ReleaseLog.parse(body, rel(5), emptyList()))
        }
        // Malformed fields of the entry: none.
        assertNull(ReleaseLog.parse(index(entry(5, summary = "5")), rel(5), emptyList()))
        assertNull(ReleaseLog.parse(index(entry(5, summary = "\"  \"")), rel(5), emptyList()))
        assertNull(ReleaseLog.parse(index(entry(5, changes = "\"one\"")), rel(5), emptyList()))
        assertNull(ReleaseLog.parse(index(entry(5, changes = "[\"one\", 2]")), rel(5), emptyList()))
        assertNull(ReleaseLog.parse(index(entry(5, security = "\"critical\"")), rel(5), emptyList()))
    }

    @Test
    fun theSecurityLine() {
        val (e, _) = ReleaseLog.parse(
            index(entry(5, security = "\"urgent\"", extra = ", \"security_text\": \"Fixes a flaw in the PIN check.\"")),
            rel(5),
            emptyList(),
        )!!
        assertEquals(ReleaseSecurity.URGENT, e.security)
        assertEquals("Fixes a flaw in the PIN check.", e.securityText)
        val (r, _) = ReleaseLog.parse(index(entry(5, security = "\"recommended\"")), rel(5), emptyList())!!
        assertEquals(ReleaseSecurity.RECOMMENDED, r.security)
    }

    @Test
    fun fieldsAreCutAtTheLimits() {
        val long = "x".repeat(500)
        val changes = (1..25).joinToString(",", "[", "]") { "\"$long\"" }
        val (e, _) = ReleaseLog.parse(
            index(
                entry(
                    5, summary = "\"$long\"", changes = changes, security = "\"urgent\"",
                    extra = ", \"security_text\": \"${"y".repeat(1500)}\"",
                ),
            ),
            rel(5),
            emptyList(),
        )!!
        assertEquals(ReleaseLog.MAX_SUMMARY, e.summary.length)
        assertTrue(e.summary.endsWith("…"))
        assertEquals(ReleaseLog.MAX_CHANGES, e.changes.size)
        assertTrue(e.changes.all { it.length == ReleaseLog.MAX_CHANGE && it.endsWith("…") })
        assertEquals(ReleaseLog.MAX_SECURITY_TEXT, e.securityText!!.length)
        // At the limit nothing is cut.
        assertEquals("a".repeat(160), ReleaseLog.plain("a".repeat(160), 160))
    }

    @Test
    fun textIsPlain() {
        assertEquals("a b c", ReleaseLog.plain("  a\n\tb\u0000‮c  ", 100))
        assertEquals("<b>x</b> https://evil.example", ReleaseLog.plain("<b>x</b> https://evil.example", 100))
        // A cut never splits a surrogate pair.
        val s = ReleaseLog.plain("ab" + "😀".repeat(10), 5)
        assertFalse(Character.isHighSurrogate(s[s.length - 2]))
    }

    @Test
    fun earlierReleasesAreMatchedTheSameWay() {
        val body = index(entry(7), entry(6, summary = "\"Six\""), entry(5, summary = "\"Five\""), entry(4, pcr0 = pcr(9)), entry(3))
        val between = listOf(rel(4), rel(5), rel(6))
        val (e, earlier) = ReleaseLog.parse(body, rel(7), between)!!
        assertEquals(7L, e.release)
        // Newest first; 4's PCR0 differs from the manifest's: left out; 3 is not between.
        assertEquals(listOf(EarlierRelease(6, "Six"), EarlierRelease(5, "Five")), earlier)
    }
}
