package com.vettid.core.data.social

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner decision 2026-10-30 (finding 5) with the recheck of #204: the asks file survives an offline cold start only. */
class AsksFileRuleTest {
    private fun unreachable(k: FailureKind) = AppPhase.Unreachable(k)

    @Test
    fun aColdStartIntoEachOfflineKindKeepsTheFile() {
        for (k in AsksFileRule.OFFLINE_KINDS) {
            assertTrue("$k", AsksFileRule.keeps(AppPhase.Starting, unreachable(k), lockPending = false))
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
    fun aLockFollowedByUnreachableDeletes() {
        for (before in listOf(AppPhase.Locked, AppPhase.Unlocked)) {
            assertFalse("$before", AsksFileRule.keeps(before, unreachable(FailureKind.NETWORK), lockPending = false))
        }
    }

    @Test
    fun aPendingLockOrAHeldPhoneDeletes() {
        assertFalse(AsksFileRule.keeps(AppPhase.Starting, unreachable(FailureKind.NETWORK), lockPending = true))
    }

    @Test
    fun everyOtherKindDeletes() {
        for (k in FailureKind.entries - AsksFileRule.OFFLINE_KINDS) {
            assertFalse("$k", AsksFileRule.keeps(AppPhase.Starting, unreachable(k), lockPending = false))
        }
        assertFalse(AsksFileRule.keeps(AppPhase.Starting, AppPhase.Locked, lockPending = false))
    }
}
