package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** When the release banner shows and when the local notification is posted (owner decision 2026-10-09). */
class ReleaseNoticesTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z")
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

    @Test
    fun noOfferNoBanner() {
        assertFalse(ReleaseNotices.bannerVisible(null, null, now))
    }

    @Test
    fun updateAvailableShowsUntilDismissed() {
        val o = ReleaseUpdateOffer(rel(4), rel(5))
        assertTrue(ReleaseNotices.bannerVisible(o, null, now))
        val d = ReleaseDismissal(5, UpdateNoticeKind.AVAILABLE, now)
        assertFalse(ReleaseNotices.bannerVisible(o, d, now))
        assertFalse(ReleaseNotices.bannerVisible(o, d, now.plus(Duration.ofHours(23)).plusSeconds(3_599)))
        // 24 h later it is back.
        assertTrue(ReleaseNotices.bannerVisible(o, d, now.plus(Duration.ofHours(24))))
        assertTrue(ReleaseNotices.bannerVisible(o, d, now.plus(Duration.ofDays(3))))
    }

    @Test
    fun theFinalWarningIsDismissedFor24HoursAtMost() {
        val o = ReleaseUpdateOffer(rel(4, "retired", ends), rel(5))
        val d = ReleaseDismissal(5, UpdateNoticeKind.ENDING, now)
        assertFalse(ReleaseNotices.bannerVisible(o, d, now.plusSeconds(3_600)))
        assertTrue(ReleaseNotices.bannerVisible(o, d, now.plus(ReleaseNotices.DISMISS_FOR)))
    }

    @Test
    fun aDismissalCountsOnlyForItsReleaseAndKind() {
        val available = ReleaseDismissal(5, UpdateNoticeKind.AVAILABLE, now)
        // A newer release shows at once.
        assertTrue(ReleaseNotices.bannerVisible(ReleaseUpdateOffer(rel(4), rel(6)), available, now.plusSeconds(60)))
        // The same release, now with an end date: the warning shows at once.
        assertTrue(ReleaseNotices.bannerVisible(ReleaseUpdateOffer(rel(4, "deprecated", ends), rel(5)), available, now.plusSeconds(60)))
    }

    @Test
    fun aClockSetBackDoesNotHideTheBanner() {
        val o = ReleaseUpdateOffer(rel(4), rel(5))
        assertTrue(ReleaseNotices.bannerVisible(o, ReleaseDismissal(5, UpdateNoticeKind.AVAILABLE, now), now.minusSeconds(60)))
    }

    @Test
    fun oneNotificationPerRelease() {
        val o = ReleaseUpdateOffer(rel(4), rel(5))
        assertTrue(ReleaseNotices.shouldNotify(o, 0))
        assertTrue(ReleaseNotices.shouldNotify(o, 4))
        assertFalse(ReleaseNotices.shouldNotify(o, 5))
        assertFalse(ReleaseNotices.shouldNotify(o, 6))
        assertFalse(ReleaseNotices.shouldNotify(null, 0))
        assertTrue(ReleaseNotices.shouldNotify(ReleaseUpdateOffer(rel(5), rel(6)), 5))
    }
}
