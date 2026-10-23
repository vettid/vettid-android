package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * The long-lived collectors a lock, the hold or an unlock depends on (VaultManager) survive an [IOException] on one
 * event, and a lock after it still clears; a bug still ends the collector, after every clearing step ran.
 */
class SurvivingCollectorTest {
    private val names = listOf("social", "attachments", "items", "sharing", "feed")

    /** The phase collector's lock clears, in VaultManager's order; [failAt] throws [failure] once, at that step. */
    private class Clears(names: List<String>, failAt: String, private var failure: Throwable?) {
        val ran = mutableListOf<String>()
        val lock = LockClears(
            names.map { n ->
                {
                    ran += n
                    if (n == failAt) failure?.let { f -> failure = null; throw f }
                }
            },
        )
    }

    /** The phase collector's per-phase shape: the lock clears through [ClearSteps], then [ClearSteps.finish]. */
    private suspend fun collectPhases(phases: List<AppPhase>, c: Clears, report: (String, IOException) -> Unit) =
        flowOf(*phases.toTypedArray()).collectSurviving("phase", report) { p ->
            val steps = ClearSteps()
            c.lock.onPhase(p, steps)
            steps.finish()
        }

    @Test
    fun afterAnIOExceptionOnOneLockTheNextLockStillClears() = runTest {
        val io = IOException("relay")
        val c = Clears(names, failAt = "attachments", failure = io)
        val reports = mutableListOf<Pair<String, IOException>>()
        collectPhases(listOf(AppPhase.Locked, AppPhase.Unlocked, AppPhase.Locked), c) { w, e -> reports += w to e }
        // The first lock: every step ran though the second threw; the unlock clears nothing; the second lock clears all.
        assertEquals(names + names, c.ran)
        assertEquals(1, reports.size)
        assertEquals("phase", reports.single().first)
        assertSame(io, reports.single().second)
    }

    @Test
    fun aBugStillEndsTheCollectorButOnlyAfterEveryStepRan() {
        val bug = IllegalStateException("bug")
        val c = Clears(names, failAt = "items", failure = bug)
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking { collectPhases(listOf(AppPhase.Locked, AppPhase.Locked), c) { _, _ -> } }
        }
        assertSame(bug, e)
        // Every step of the first lock ran; the collector ended there (the app crashes: fail secure).
        assertEquals(names, c.ran)
    }

    @Test
    fun aSignOutClearsAndStartingAndUnlockedDoNot() = runTest {
        val c = Clears(names, failAt = "", failure = null)
        collectPhases(listOf(AppPhase.Starting, AppPhase.Unlocked, AppPhase.SignedOut), c) { _, _ -> }
        assertEquals(names, c.ran)
    }

    @Test
    fun anIOExceptionCarryingABugIsRethrown() {
        val io = IOException("relay")
        io.addSuppressed(IllegalStateException("bug"))
        val e = assertThrows(IOException::class.java) { surviveEvent("phase", { _, _ -> error("not reported") }) { throw io } }
        assertSame(io, e)
    }

    @Test
    fun aCancellationIsRethrownEvenBehindAnIOException() {
        val cancel = CancellationException("ended")
        assertSame(cancel, assertThrows(CancellationException::class.java) { surviveEvent("phase", { _, _ -> }) { throw cancel } })
        val io = IOException("relay")
        io.addSuppressed(cancel)
        assertSame(cancel, assertThrows(CancellationException::class.java) { surviveEvent("phase", { _, _ -> }) { throw io } })
    }

    @Test
    fun aCollectorKeepsGoingAfterIOExceptionsFromThePlainBody() = runTest {
        val seen = mutableListOf<Int>()
        var reported = 0
        flowOf(1, 2, 3).collectSurviving("events", { _, _ -> reported++ }) { n ->
            if (n != 3) throw IOException("relay $n")
            seen += n
        }
        assertEquals(listOf(3), seen)
        assertEquals(2, reported)
    }
}
