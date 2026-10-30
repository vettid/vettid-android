package com.vettid.core.data.social

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner decision 2026-10-30 (finding 5) with the rechecks of #204: the asks file survives an offline cold start only. */
class AsksFileRuleTest {
    private fun unreachable(k: FailureKind, long: Boolean = false) = AppPhase.Unreachable(k, longUnreachable = long)

    /** Publishes [phase] as the app does, then handles it as the collector does. */
    private fun AsksFileRule.Track.step(phase: AppPhase, lockPending: Boolean = false): Boolean {
        published(phase)
        return keeps(phase, lockPending)
    }

    @Test
    fun aColdStartIntoEachOfflineKindKeepsTheFile() {
        for (k in AsksFileRule.OFFLINE_KINDS) {
            assertTrue("$k", AsksFileRule.Track().step(unreachable(k)))
        }
        val named = setOf(
            FailureKind.NETWORK,
            FailureKind.NO_RESPONSE,
            FailureKind.SERVICE_PAUSED,
            FailureKind.VAULT_UNAVAILABLE,
            FailureKind.RATE_LIMITED,
        )
        assertEquals(named, AsksFileRule.OFFLINE_KINDS)
    }

    @Test
    fun tryAgainWhileStillOfflineKeepsTheFile() {
        val t = AsksFileRule.Track()
        assertTrue(t.step(unreachable(FailureKind.NETWORK)))
        assertTrue(t.step(unreachable(FailureKind.NO_RESPONSE)))
        assertTrue(t.step(unreachable(FailureKind.NO_RESPONSE, long = true)))
        assertTrue(t.step(unreachable(FailureKind.VAULT_UNAVAILABLE)))
    }

    @Test
    fun aLockEndsThePathForGood() {
        val t = AsksFileRule.Track()
        assertTrue(t.step(unreachable(FailureKind.NETWORK)))
        assertFalse(t.step(AppPhase.Locked))
        assertFalse(t.step(unreachable(FailureKind.NETWORK)))
        assertFalse(t.step(unreachable(FailureKind.NO_RESPONSE)))
    }

    @Test
    fun aLockedPhaseTheCollectorNeverSawStillEndsIt() {
        val t = AsksFileRule.Track()
        t.published(AppPhase.Locked) // replaced by the next phase before the collector ran
        assertFalse(t.step(unreachable(FailureKind.NETWORK)))
    }

    @Test
    fun anUnlockOrASetupPhaseEndsIt() {
        for (p in listOf(AppPhase.Unlocked, AppPhase.SignedOut, AppPhase.Setup(com.vettid.core.data.vault.SetupStage.NEW_VAULT))) {
            val t = AsksFileRule.Track()
            assertTrue(t.step(unreachable(FailureKind.NETWORK)))
            t.published(p)
            assertFalse("$p", t.step(unreachable(FailureKind.NETWORK)))
        }
    }

    @Test
    fun aPendingLockOrAHeldPhoneDeletesAndEndsIt() {
        val t = AsksFileRule.Track()
        assertFalse(t.step(unreachable(FailureKind.NETWORK), lockPending = true))
        assertFalse(t.step(unreachable(FailureKind.NETWORK)))
    }

    @Test
    fun everyOtherKindDeletesAndEndsIt() {
        for (k in FailureKind.entries - AsksFileRule.OFFLINE_KINDS) {
            val t = AsksFileRule.Track()
            assertFalse("$k", t.step(unreachable(k)))
            assertFalse("$k then offline", t.step(unreachable(FailureKind.NETWORK)))
        }
    }
}
