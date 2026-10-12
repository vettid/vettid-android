package com.vettid.core.data.lock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Allow screenshots" (owner decision 2026-10-11, A6 audit S1): off at start, off again at a vault lock and after 1 hour. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotAllowanceTest {
    private fun TestScope.allowance(onByDefault: Boolean = false, clock: (() -> Long)? = null) =
        ScreenshotAllowance(onByDefault, backgroundScope, clock ?: { testScheduler.currentTime })

    @Test
    fun neverOnAtAppStart() = runTest {
        assertFalse(allowance().allowed.value)
    }

    @Test
    fun turnsItselfOffAfterOneHour() = runTest {
        val a = allowance()
        a.allow()
        assertTrue(a.allowed.value)
        advanceTimeBy(ScreenshotAllowance.LIFETIME_MS - 1)
        assertTrue(a.allowed.value)
        advanceTimeBy(2)
        assertFalse(a.allowed.value)
    }

    @Test
    fun turnsItselfOffWhenTheVaultLocksBeforeTheHour() = runTest {
        val a = allowance()
        a.allow()
        advanceTimeBy(60_000)
        a.vaultClosed()
        assertFalse(a.allowed.value)
        // Nothing turns it back on later.
        advanceTimeBy(2 * ScreenshotAllowance.LIFETIME_MS)
        assertFalse(a.allowed.value)
    }

    @Test
    fun theHourCountsWhileThePhoneSleepsAndIsCheckedOnReturn() = runTest {
        // The phone slept: the timer has not fired, the clock moved on.
        var clock = 0L
        val a = allowance(clock = { clock })
        a.allow()
        clock = ScreenshotAllowance.LIFETIME_MS - 1
        a.check()
        assertTrue(a.allowed.value)
        clock = ScreenshotAllowance.LIFETIME_MS
        a.check()
        assertFalse(a.allowed.value)
    }

    @Test
    fun turningItOnAgainStartsANewHour() = runTest {
        val a = allowance()
        a.allow()
        advanceTimeBy(ScreenshotAllowance.LIFETIME_MS - 1000)
        a.allow()
        advanceTimeBy(2000)
        assertTrue("the first hour's timer does not end the second", a.allowed.value)
        advanceTimeBy(ScreenshotAllowance.LIFETIME_MS)
        assertFalse(a.allowed.value)
    }

    @Test
    fun theMemberCanTurnItOff() = runTest {
        val a = allowance()
        a.allow()
        a.disallow()
        assertFalse(a.allowed.value)
    }

    @Test
    fun developmentBuildsStartOnAndStayOnUntilSwitchedOff() = runTest {
        val a = allowance(onByDefault = true)
        assertTrue(a.allowed.value)
        assertTrue(a.staysOn)
        a.vaultClosed()
        advanceTimeBy(2 * ScreenshotAllowance.LIFETIME_MS)
        a.check()
        assertTrue(a.allowed.value)
        a.disallow()
        assertFalse(a.allowed.value)
        a.allow()
        advanceTimeBy(2 * ScreenshotAllowance.LIFETIME_MS)
        assertTrue(a.allowed.value)
    }
}
