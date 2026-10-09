package com.vettid.feature.history

import com.vettid.core.data.vault.AuditCategory
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.AuditOps
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.HistoryManager
import com.vettid.core.vault.AuditEntry
import com.vettid.core.vault.AuditPage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Cursor paging, `partial`, the filter sent, the S4 fallback and the chain check (VAULT-MESSAGING 0.20.0 §10.9). */
class HistoryPagerTest {
    private val t0: Instant = Instant.parse("2026-10-07T12:00:00Z")

    /**
     * A log of [n] entries, seq 1..n, one minute apart (seq n newest), properly chained; kinds cycle through [kinds],
     * even seqs belong to connection c1. [mode]: how the vault treats the search (0.20.0, S4 ignoring it, refusing).
     */
    private class Log(
        n: Int,
        val kinds: List<String>,
        val t0: Instant,
        val mode: Mode = Mode.V020,
        val budget: Int = Int.MAX_VALUE,
    ) : AuditOps {
        enum class Mode { V020, IGNORES, REFUSES }

        var all: List<AuditEntry>
            private set
        val calls = mutableListOf<Map<String, Any?>>()

        /** The vault records one more entry (newest, a minute after the last), chained to the last. */
        fun append(kind: String) {
            val last = all.last()
            val seq = last.seq + 1
            val at = Instant.parse(last.at).plus(Duration.ofMinutes(1))
            val prev = Base64.getDecoder().decode(last.hash)
            val h = hash(prev, seq, at.toEpochMilli(), kind, null)
            all = all + AuditEntry("e$seq", seq, at.toString(), kind, null, null, null, null, b64(prev), b64(h))
        }

        init {
            var prev = ByteArray(32)
            all = (1..n).map { s ->
                val at = t0.minus(Duration.ofMinutes((n - s).toLong()))
                val kind = kinds[s % kinds.size]
                val conn = if (s % 2 == 0) "c1" else null
                val h = hash(prev, s.toLong(), at.toEpochMilli(), kind, conn)
                AuditEntry("e$s", s.toLong(), at.toString(), kind, conn, null, null, null, b64(prev), b64(h)).also { prev = h }
            }
        }

        override suspend fun auditList(
            connectionId: String?,
            kinds: List<String>?,
            beforeSeq: Long?,
            limit: Int,
            q: String?,
            since: String?,
            until: String?,
        ): AuditPage {
            calls += mapOf(
                "connection_id" to connectionId, "kinds" to kinds, "before_seq" to beforeSeq, "limit" to limit,
                "q" to q, "since" to since, "until" to until,
            )
            val search = q != null || since != null || until != null
            if (mode == Mode.REFUSES && search) throw VaultOpException("audit.list", "bad_request")
            val honour = mode == Mode.V020
            val sinceAt = since?.let { Instant.parse(it) }
            val untilAt = until?.let { Instant.parse(it) }
            val candidates = all.asReversed()
                .filter { beforeSeq == null || it.seq < beforeSeq }
                .filter { connectionId == null || it.connectionId == connectionId }
                .filter { e -> kinds == null || kinds.any { e.kind == it || e.kind.startsWith("$it.") } }
                .filter { !honour || sinceAt == null || !Instant.parse(it.at).isBefore(sinceAt) }
                .filter { !honour || untilAt == null || Instant.parse(it.at).isBefore(untilAt) }
            if (honour && q != null) {
                // The scan budget: at most [budget] candidates evaluated per request.
                val evaluated = candidates.take(budget)
                val matches = evaluated.filter { AuditFilter.matches(q, AuditFilter.kindText(it.kind)) }.take(limit)
                val ranOut = candidates.size > budget && matches.size < limit
                val cursor = when {
                    ranOut -> evaluated.last().seq
                    matches.size == limit && candidates.last().seq < matches.last().seq -> matches.last().seq
                    else -> null
                }
                return AuditPage(matches, seq = all.last().seq, nextBeforeSeq = cursor, partial = ranOut)
            }
            val page = candidates.take(limit)
            val more = page.size == limit && candidates.size > limit
            return AuditPage(page, seq = all.last().seq, nextBeforeSeq = if (more) page.last().seq else null)
        }

        companion object {
            fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

            fun hash(prev: ByteArray, seq: Long, atMs: Long, kind: String, conn: String?): ByteArray {
                fun u(v: Long, n: Int) = ByteArray(n) { i -> (v ushr (8 * (n - 1 - i))).toByte() }
                fun lp(s: String?): ByteArray {
                    val b = (s ?: "").toByteArray()
                    return u(b.size.toLong(), 2) + b
                }
                val md = MessageDigest.getInstance("SHA-256")
                listOf("vettid/vms/2/audit".toByteArray(), prev, u(seq, 8), u(atMs, 8), lp(kind), lp(conn), lp(null), lp(null), lp(null))
                    .forEach { md.update(it) }
                return md.digest()
            }
        }
    }

    private val text: (AuditRecord) -> List<String> = { AuditFilter.kindText(it.kind) }

    @Test
    fun pagesOf50NewestFirstWithTheCursor() = runTest {
        val log = Log(120, listOf("vault.unlocked"), t0)
        val pager = HistoryPager(HistoryManager { log }, text, minPerLoad = 1)
        val first = pager.reset(AuditFilter())
        assertEquals(50, log.calls.single()["limit"])
        assertNull(log.calls.single()["before_seq"])
        assertEquals(50, first.entries.size)
        assertEquals(120L, first.entries.first().seq)
        assertFalse(first.end)
        pager.more()
        assertEquals(71L, log.calls.last()["before_seq"])
        val third = pager.more()
        assertEquals(120, third.entries.size)
        assertTrue(third.end)
        // Nothing more is asked once a page has no cursor.
        pager.more()
        assertEquals(3, log.calls.size)
        assertEquals((120L downTo 1L).toList(), third.entries.map { it.seq })
        assertFalse(third.chainBroken)
    }

    @Test
    fun theCategoryConnectionAndDatesAreSent() = runTest {
        val log = Log(10, listOf("vault.unlocked", "message.sent"), t0)
        val pager = HistoryPager(HistoryManager { log }, text)
        val since = t0.minus(Duration.ofMinutes(5))
        val until = t0
        val s = pager.reset(AuditFilter(category = AuditCategory.VAULT_ACCESS, connectionId = "c1", since = since, until = until))
        val call = log.calls.single()
        assertEquals(listOf("vault", "owner_check"), call["kinds"])
        assertEquals("c1", call["connection_id"])
        assertEquals(since.toString(), call["since"])
        assertEquals(until.toString(), call["until"])
        assertTrue(s.entries.isNotEmpty())
        assertTrue(s.entries.all { it.kind == "vault.unlocked" && it.connectionId == "c1" && it.at!! < until && it.at!! >= since })
    }

    @Test
    fun aPartialSearchGoesOnWithTheCursor() = runTest {
        // One match in ten; the vault evaluates 30 entries per request.
        val kinds = List(9) { "vault.unlocked" } + "credential.password_changed"
        val log = Log(400, kinds, t0, budget = 30)
        val pager = HistoryPager(HistoryManager { log }, text, minPerLoad = 10, maxPagesPerLoad = 8)
        val s = pager.reset(AuditFilter(query = "password changed"))
        assertEquals("password changed", log.calls.first()["q"])
        // 3 matches per partial page: 4 pages for 10.
        assertEquals(4, log.calls.size)
        assertEquals(listOf(null, 371L, 341L, 311L), log.calls.map { it["before_seq"] })
        assertEquals(12, s.entries.size)
        assertTrue(s.partial)
        assertFalse(s.localSearch)
        assertFalse(s.end)
        assertTrue(s.entries.all { it.kind == "credential.password_changed" })
    }

    @Test
    fun aRefusedSearchIsSentAgainWithoutItAndRunsHere() = runTest {
        val log = Log(50, listOf("vault.unlocked", "message.sent"), t0, Log.Mode.REFUSES)
        val repo = HistoryManager { log }
        val pager = HistoryPager(repo, text)
        val s = pager.reset(AuditFilter(query = "message"))
        assertEquals("message", log.calls[0]["q"])
        assertNull(log.calls[1]["q"])
        assertTrue(repo.searchUnsupported)
        assertTrue(s.localSearch)
        assertEquals(25, s.entries.size)
        assertTrue(s.entries.all { it.kind == "message.sent" })
        // The session stops sending it.
        pager.reset(AuditFilter(query = "vault"))
        assertNull(log.calls.last()["q"])
    }

    @Test
    fun anIgnoredSearchIsNoticedAndRunsHere() = runTest {
        // An S4 vault (0.15.0–0.19.0) ignores q, since and until: the answer is unfiltered.
        val log = Log(50, listOf("vault.unlocked", "message.sent"), t0, Log.Mode.IGNORES)
        val repo = HistoryManager { log }
        val pager = HistoryPager(repo, text)
        val s = pager.reset(AuditFilter(query = "vault unlocked", since = t0.minus(Duration.ofMinutes(19))))
        assertEquals("vault unlocked", log.calls[0]["q"])
        assertTrue(repo.searchUnsupported)
        assertTrue(s.localSearch)
        // The 20 newest minutes, vault.unlocked only (odd seqs... every other entry).
        assertEquals(10, s.entries.size)
        assertTrue(s.entries.all { it.kind == "vault.unlocked" && !it.at!!.isBefore(t0.minus(Duration.ofMinutes(19))) })
    }

    @Test
    fun aVaultSearchIsTrusted() = runTest {
        val log = Log(50, listOf("vault.unlocked", "message.sent"), t0)
        val pager = HistoryPager(HistoryManager { log }, text)
        val s = pager.reset(AuditFilter(query = "MESSAGE"))
        assertFalse(s.localSearch)
        assertEquals(25, s.entries.size)
    }

    @Test
    fun aBrokenChainIsReportedOnlyUnfiltered() = runTest {
        val log = Log(10, listOf("vault.unlocked"), t0)
        val tampered = log.all.toMutableList().also { it[4] = it[4].copy(kind = "vault.locked") }
        val ops = AuditOps { _, kinds, _, limit, _, _, _ ->
            AuditPage(tampered.asReversed().filter { e -> kinds == null || kinds.any { e.kind.startsWith(it) } }.take(limit), seq = 10)
        }
        val pager = HistoryPager(HistoryManager { ops }, text)
        assertTrue(pager.reset(AuditFilter()).chainBroken)
        assertFalse(pager.reset(AuditFilter(category = AuditCategory.VAULT_ACCESS)).chainBroken)
    }

    @Test
    fun theDetailFindsAnEntryReadBefore() = runTest {
        val log = Log(5, listOf("vault.unlocked"), t0)
        val repo = HistoryManager { log }
        HistoryPager(repo, text).reset(AuditFilter())
        assertEquals("e3", repo.cached(3)?.entryId)
        repo.clear()
        assertNull(repo.cached(3))
    }

    @Test
    fun aRefreshPutsWhatArrivedSinceOnTopAndKeepsTheRest() = runTest {
        val log = Log(120, listOf("vault.unlocked"), t0)
        val pager = HistoryPager(HistoryManager { log }, text, minPerLoad = 1)
        pager.reset(AuditFilter())
        pager.more()
        // An export: the vault records `audit.exported` (and nothing else changed).
        log.append("audit.exported")
        val s = pager.refresh()
        assertNull(log.calls.last()["before_seq"])
        assertEquals(121L, s.entries.first().seq)
        assertEquals("audit.exported", s.entries.first().kind)
        assertEquals((121L downTo 21L).toList(), s.entries.map { it.seq })
        assertFalse(s.chainBroken)
        // The cursor is where it was: the next page goes on from 21.
        pager.more()
        assertEquals(21L, log.calls.last()["before_seq"])
        // Nothing new: the list stays as it is.
        assertEquals(pager.snapshot.entries, pager.refresh().entries)
    }

    @Test
    fun aRefreshUnderAFilterAddsOnlyWhatMatches() = runTest {
        val log = Log(10, listOf("vault.unlocked"), t0)
        val pager = HistoryPager(HistoryManager { log }, text)
        pager.reset(AuditFilter(category = AuditCategory.VAULT_ACCESS))
        log.append("audit.exported")
        log.append("vault.unlocked")
        val s = pager.refresh()
        assertEquals(listOf(12L) + (10L downTo 1L).toList(), s.entries.map { it.seq })
        val sec = HistoryPager(HistoryManager { log }, text)
        sec.reset(AuditFilter(category = AuditCategory.SECURITY))
        log.append("audit.exported")
        assertEquals(listOf(13L, 11L), sec.refresh().entries.map { it.seq })
    }

    @Test
    fun moreThanAPageArrivedStartsOver() = runTest {
        val log = Log(60, listOf("vault.unlocked"), t0)
        val pager = HistoryPager(HistoryManager { log }, text)
        pager.reset(AuditFilter())
        repeat(55) { log.append("vault.unlocked") }
        val s = pager.refresh()
        // The newest page's cursor (66) is above the newest shown (60): a reset, newest first and contiguous.
        assertEquals(115L, s.entries.first().seq)
        assertEquals((115L downTo 115L - s.entries.size + 1).toList(), s.entries.map { it.seq })
        assertFalse(s.chainBroken)
    }
}
