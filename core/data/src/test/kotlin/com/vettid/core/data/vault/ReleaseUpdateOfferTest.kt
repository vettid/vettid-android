package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The offer the release gate decides from: when there is one, what it lists, and its kind (ANDROID-PLAN 0.1.19). */
class ReleaseUpdateOfferTest {
    private val ends = Instant.parse("2026-11-30T00:00:00Z")

    private fun rel(n: Long, status: String = "active", endsAt: Instant? = null) =
        ReleaseView(n, "%02d".format(n).repeat(48), status, endsAt, "https://vettid.org/r/$n")

    @Test
    fun anOfferNeedsANewerActiveRelease() {
        assertNull(ReleaseUpdateOffer.of(rel(5), rel(5)))
        assertNull(ReleaseUpdateOffer.of(rel(6), rel(5)))
        assertNull(ReleaseUpdateOffer.of(null, rel(5)))
        assertNull(ReleaseUpdateOffer.of(rel(4), null))
        assertNull(ReleaseUpdateOffer.of(rel(4), rel(5, "deprecated")))
        assertEquals(5L, ReleaseUpdateOffer.of(rel(4), rel(5))!!.target.number)
    }

    @Test
    fun theOfferKeepsOnlyTheReleasesInBetween() {
        val o = ReleaseUpdateOffer.of(rel(4), rel(7), listOf(rel(3), rel(4), rel(5), rel(6), rel(7), rel(8)))!!
        assertEquals(listOf(5L, 6L), o.between.map { it.number })
        assertTrue(ReleaseUpdateOffer.of(rel(4), rel(5))!!.between.isEmpty())
    }

    @Test
    fun theKindFollowsTheVaultsRelease() {
        // An older active release, or a deprecated one without an end date: "available".
        assertEquals(UpdateNoticeKind.AVAILABLE, ReleaseUpdateOffer(rel(4), rel(5)).kind)
        assertEquals(UpdateNoticeKind.AVAILABLE, ReleaseUpdateOffer(rel(4, "deprecated"), rel(5)).kind)
        // Deprecated with an end date, or retired (final_warning): the warning with the date.
        val deprecated = ReleaseUpdateOffer(rel(4, "deprecated", ends), rel(5))
        assertEquals(UpdateNoticeKind.ENDING, deprecated.kind)
        assertEquals(ends, deprecated.endsAt)
        assertEquals(UpdateNoticeKind.ENDING, ReleaseUpdateOffer(rel(4, "retired", ends), rel(5)).kind)
        assertEquals(UpdateNoticeKind.ENDING, ReleaseUpdateOffer(rel(4, "retired"), rel(5)).kind)
        // Removed, open for a rescue: only the move.
        assertEquals(UpdateNoticeKind.ENDED, ReleaseUpdateOffer(rel(4, "removed"), rel(5)).kind)
    }
}
