package com.vettid.feature.history

import com.vettid.core.data.vault.AuditCategory
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.AuditRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The filter the History screen builds (ANDROID-PLAN 0.1.11) and `q`'s rules (VAULT-MESSAGING 0.20.0 §10.9). */
class HistoryFiltersTest {
    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val now: Instant = Instant.parse("2026-10-07T15:30:00Z") // 11:30 in New York

    @Test
    fun oneCategoryAtATime() {
        val f = HistoryFilters.category(AuditFilter(), AuditCategory.DEVICES)
        assertEquals(AuditCategory.DEVICES, f.category)
        assertEquals(listOf("device", "approval"), f.kinds())
        assertEquals(AuditCategory.SECURITY, HistoryFilters.category(f, AuditCategory.SECURITY).category)
        // The selected one again, or All, clears it.
        assertNull(HistoryFilters.category(f, AuditCategory.DEVICES).category)
        assertNull(HistoryFilters.category(f, null).kinds())
        // §10.9: 1–16 prefixes per request.
        assertTrue(AuditCategory.filters.all { it.prefixes.size in 1..16 })
    }

    @Test
    fun presetsStartAtLocalMidnight() {
        val today = HistoryFilters.dates(AuditFilter(), DatePreset.TODAY, now, zone)
        assertEquals(Instant.parse("2026-10-07T04:00:00Z"), today.since)
        assertNull(today.until)
        val week = HistoryFilters.dates(AuditFilter(), DatePreset.WEEK, now, zone)
        assertEquals(Instant.parse("2026-10-01T04:00:00Z"), week.since)
        val month = HistoryFilters.dates(AuditFilter(), DatePreset.MONTH, now, zone)
        assertEquals(Instant.parse("2026-09-08T04:00:00Z"), month.since)
        val any = HistoryFilters.dates(week, DatePreset.ANY, now, zone)
        assertNull(any.since)
        assertNull(any.until)
    }

    @Test
    fun aCustomRangeIsWholeDaysUntilExclusive() {
        val (since, until) = HistoryFilters.days(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 4), zone)
        assertEquals(Instant.parse("2026-10-02T04:00:00Z"), since)
        assertEquals(Instant.parse("2026-10-05T04:00:00Z"), until)
        // Picked backwards: the same range.
        assertEquals(since to until, HistoryFilters.days(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 2), zone))
        val f = HistoryFilters.dates(AuditFilter(), DatePreset.CUSTOM, now, zone, since, until)
        fun r(at: String) = AuditRecord("e", 1, Instant.parse(at), "vault.unlocked")
        assertTrue(f.acceptsExceptSearch(r("2026-10-02T04:00:00Z")))
        assertTrue(f.acceptsExceptSearch(r("2026-10-05T03:59:59.999Z")))
        assertFalse(f.acceptsExceptSearch(r("2026-10-05T04:00:00Z")))
        assertFalse(f.acceptsExceptSearch(r("2026-10-02T03:59:59Z")))
    }

    @Test
    fun theSearchTextIsTrimmedNfcAndCappedAt128Bytes() {
        assertNull(AuditFilter.searchText("   "))
        assertEquals("ada", AuditFilter.searchText("  ada \t"))
        // e + combining acute → é (NFC).
        assertEquals("é", AuditFilter.searchText("é"))
        val long = "é".repeat(100) // 200 bytes
        val q = AuditFilter.searchText(long)!!
        assertEquals(64, q.length)
        assertTrue(q.toByteArray(Charsets.UTF_8).size <= 128)
        assertNull(AuditFilter(query = "  ").q)
    }

    @Test
    fun theKindIsSearchedInBothForms() {
        val fields = AuditFilter.kindText("credential.password_changed")
        assertTrue(AuditFilter.matches("password changed", fields))
        assertTrue(AuditFilter.matches("PASSWORD_CHANGED", fields))
        assertFalse(AuditFilter.matches("password  changed", fields))
        // A match never spans two fields; an empty field never matches.
        assertFalse(AuditFilter.matches("ada lovelace", listOf("Ada", "Lovelace", "")))
        assertTrue(AuditFilter.matches("ada lovelace", listOf("Ada Lovelace")))
    }

    @Test
    fun theFilterIsEmptyOnlyWithoutAnything() {
        assertTrue(AuditFilter().isEmpty)
        assertTrue(AuditFilter(query = " ").isEmpty)
        assertFalse(AuditFilter(connectionId = "c").isEmpty)
        assertFalse(AuditFilter(category = AuditCategory.LOCATION).isEmpty)
    }
}
