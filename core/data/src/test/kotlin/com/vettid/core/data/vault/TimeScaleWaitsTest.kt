// Expected values stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The app's own waits under the vault's time scale (STAGING-TIME-SCALE §3, §5.6; ANDROID-PLAN 0.1.44): each is divided
 * when it starts and keeps the absolute time it was given (S2, S8, S10, S12, S16); at 1 nothing changes.
 */
class TimeScaleWaitsTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 100 * day

    @Test
    fun theRuleDividesAndRoundsUpToAWholeSecond() {
        assertEquals(3_600L, TimeScales.divide(86_400, 24))
        assertEquals(25_200L, TimeScales.divide(604_800, 24))
        assertEquals(150L, TimeScales.divide(3_600, 24))
        assertEquals(3L, TimeScales.divide(7, 3)) // 2.33 s rounds up
        assertEquals(86_400L, TimeScales.divide(86_400, 1))
        assertEquals(86_400L, TimeScales.divide(86_400, 0)) // never below real time
        assertEquals(7 * hour, TimeScales.divideMs(7 * day, 24))
    }

    @Test
    fun theViewSaysWhenTheStagingNoteShows() {
        assertFalse(TimeScaleView().stagingNote)
        assertFalse("production: never", TimeScaleView(scale = 1, known = true, stagingBuild = false).stagingNote)
        assertTrue(TimeScaleView(scale = 24, known = true, stagingBuild = true).stagingNote)
        assertTrue("a staging build that has not read its vault's scale yet", TimeScaleView(known = false, stagingBuild = true).stagingNote)
        assertFalse("a staging vault at real time", TimeScaleView(scale = 1, known = true, stagingBuild = true).stagingNote)
        assertEquals(3_600L, TimeScaleView(scale = 24).scaledSeconds(86_400))
        assertEquals(Duration.ofHours(7), TimeScaleView(scale = 24).scaled(Duration.ofDays(7)))
    }

    /** S2: the early warning from 1 h before the deadline, 2.5 minutes at 24; the deadline itself is the vault's. */
    @Test
    fun theOwnerChecksEarlyWarning() {
        val deadline = Instant.parse("2026-10-19T12:00:00Z")
        val v = OwnerCheckView(OwnerCheckState.OK, deadline, 86_400, 0, hold = true, holdOffUntil = null)
        assertTrue(v.warning(deadline.minusSeconds(3_600)))
        assertFalse(v.warning(deadline.minusSeconds(3_601)))
        assertFalse("at 24 not an hour ahead", v.warning(deadline.minusSeconds(3_600), timeScale = 24))
        assertTrue(v.warning(deadline.minusSeconds(150), timeScale = 24))
        assertFalse(v.warning(deadline.minusSeconds(151), timeScale = 24))
        assertFalse("past the deadline it is the check, not the warning", v.warning(deadline, timeScale = 24))
        assertEquals(Duration.ofSeconds(150), OwnerCheckView.warningFor(24))
        assertEquals(OwnerCheckView.WARNING, OwnerCheckView.warningFor(1))
        // The interval keeps its real units; it runs divided.
        assertEquals(3_600L, v.intervalRunsSeconds(24))
        assertEquals(86_400L, v.intervalRunsSeconds(1))
    }

    /**
     * S2 (VAULT-MESSAGING 0.43.0 §11.10.8): `vault.status`'s `time_scale` is stored when this build accepts it by the
     * descriptor's rule; an absent, unchanged, out-of-range or refused value stores nothing.
     */
    @Test
    fun aStatusScaleIsTakenByTheDescriptorsRule() {
        val staging: (Long) -> Boolean = { it == 1L || it == 24L }
        val production: (Long) -> Boolean = { it == 1L }
        assertEquals("fills a missing scale", 24L, TimeScales.fromStatus(null, 24, staging))
        assertEquals("follows the vault", 24L, TimeScales.fromStatus(1, 24, staging))
        assertEquals(1L, TimeScales.fromStatus(24, 1, staging))
        assertNull("unchanged", TimeScales.fromStatus(24, 24, staging))
        assertNull("absent: the stored scale stays", TimeScales.fromStatus(24, null, staging))
        assertNull("production refuses 24", TimeScales.fromStatus(null, 24, production))
        assertNull(TimeScales.fromStatus(1, 24, production))
        assertEquals(1L, TimeScales.fromStatus(null, 1, production))
        assertNull("not an accepted value", TimeScales.fromStatus(null, 12, staging))
        assertNull("out of range", TimeScales.fromStatus(null, 0) { true })
        assertNull(TimeScales.fromStatus(null, -24) { true })
        assertNull(TimeScales.fromStatus(null, 86_401) { true })
    }

    /** S10: the old phone keeps its keys 7 days after the approval, 7 hours at 24, from the period stored with it. */
    @Test
    fun theOldPhonesKeysAfterATransfer() {
        assertEquals(KeptTransfer.KEEP_MS, KeptTransfer.keepMs(1))
        assertEquals(7 * hour, KeptTransfer.keepMs(24))
        val keep = KeptTransfer.keepMs(24)
        assertEquals(KeptTransfer.Decision.Keep(hour), KeptTransfer.decide(now - 6 * hour, now, keep))
        assertEquals(KeptTransfer.Decision.Erase, KeptTransfer.decide(now - 7 * hour, now, keep))
        // A clock set back counts from now, never beyond the period.
        assertEquals(KeptTransfer.Decision.Keep(keep), KeptTransfer.decide(now + hour, now, keep))
        // An approval made before the scale was known keeps its 7 days.
        assertEquals(KeptTransfer.Decision.Keep(6 * day + 17 * hour), KeptTransfer.decide(now - 7 * hour, now))
    }

    /** S12: the long-unreachable bound is set with each contact, from the scale then, and kept with it. */
    @Test
    fun theLongUnreachableBound() {
        assertEquals(7 * hour, StuckScreens.unreachableAfterMs(24))
        assertTrue(StuckScreens.longUnreachable(FailureKind.NETWORK, now - 7 * hour, now, 7 * hour))
        assertFalse(StuckScreens.longUnreachable(FailureKind.NETWORK, now - 7 * hour + 1, now, 7 * hour))
        // At 24 a contact is written every 2.5 minutes at most, so the 7 hours are not reached early.
        assertFalse(StuckScreens.writeContact(now - 149_999, now, 7 * hour))
        assertTrue(StuckScreens.writeContact(now - 150_000, now, 7 * hour))

        var disk: LocalAccount? = null
        val r = AccountRecord(LocalAccount(userGuid = "u", timeScale = 24), readable = true, persist = { disk = it })
        assertTrue(r.contacted(r.recordGeneration, now))
        assertEquals(now, disk?.lastContactAtMs)
        assertEquals(7 * hour, disk?.unreachableAfterMs)
        // A vault at real time (or one whose scale is not known): 7 days, stored as none.
        val real = AccountRecord(LocalAccount(userGuid = "u", unreachableAfterMs = 7 * hour, lastContactAtMs = now), readable = true, persist = { disk = it })
        assertTrue("the bound changed: written at once", real.contacted(real.recordGeneration, now + 1))
        assertNull(disk?.unreachableAfterMs)
    }

    /** S16: the release banner's dismissal, 24 h at most, 1 h at 24, kept with the dismissal. */
    @Test
    fun theUpdateBannersDismissal() {
        val at = Instant.parse("2026-10-19T10:00:00Z")
        val o = ReleaseUpdateOffer(rel(4), rel(5))
        assertEquals(Duration.ofHours(1), ReleaseNotices.dismissFor(24))
        assertEquals(ReleaseNotices.DISMISS_FOR, ReleaseNotices.dismissFor(1))
        val scaled = ReleaseDismissal(5, UpdateNoticeKind.AVAILABLE, at, ReleaseNotices.dismissFor(24))
        assertFalse(ReleaseNotices.bannerVisible(o, scaled, at.plusSeconds(3_599)))
        assertTrue(ReleaseNotices.bannerVisible(o, scaled, at.plusSeconds(3_600)))
        // A dismissal from before the scale keeps its 24 hours.
        val before = ReleaseDismissal(5, UpdateNoticeKind.AVAILABLE, at)
        assertFalse(ReleaseNotices.bannerVisible(o, before, at.plusSeconds(3_600)))
    }

    private fun rel(n: Long) = ReleaseView(n, "%02d".format(n).repeat(48), ReleaseUpdateOffer.STATUS_ACTIVE, null, "https://vettid.org/r/$n")
}
