package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The long-lived collectors a lock, the hold or an unlock depends on (VaultManager) survive an [IOException] on one
 * event, and a lock after it still clears. A bug, a stray cancellation and a lock's clears that fail twice end the
 * process through the scope's real [RelaySafetyNet] (fatal, never "relay error"), after every clearing step ran.
 */
class SurvivingCollectorTest {
    private val lockOrder = listOf(
        PhaseSteps.REPLAY, PhaseSteps.SOCIAL, PhaseSteps.ATTACHMENTS, PhaseSteps.ITEMS, PhaseSteps.SHARING, PhaseSteps.FEED,
        PhaseSteps.OWN_PROFILE,
    )
    private val clears = lockOrder.drop(1)

    /** VaultManager's phase steps through [PhaseSteps.of] with recording fakes; [failures] are thrown in turn, by name. */
    private class Fakes(vararg failures: Pair<String, Throwable>) {
        val ran = mutableListOf<String>()
        private val pending = failures.toMutableList()

        private fun run(name: String) {
            ran += name
            val i = pending.indexOfFirst { it.first == name }
            if (i >= 0) throw pending.removeAt(i).second
        }

        val steps = PhaseSteps.of(
            replay = { run(PhaseSteps.REPLAY) },
            forgetOpenVault = { run(PhaseSteps.SOCIAL) },
            clearAttachments = { run(PhaseSteps.ATTACHMENTS) },
            clearItems = { run(PhaseSteps.ITEMS) },
            clearSharing = { run(PhaseSteps.SHARING) },
            clearFeed = { run(PhaseSteps.FEED) },
            ownProfile = { run(PhaseSteps.OWN_PROFILE) },
        )
    }

    /** The process scope as AppModule builds it, with a real [RelaySafetyNet] whose two outcomes are recorded. */
    private class ProcessScope {
        val relay = mutableListOf<IOException>()
        val fatal = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + RelaySafetyNet(onRelayError = { relay += it }, fatal = { fatal += it }),
        )

        fun run(block: suspend () -> Unit): Job = runBlocking { scope.launch { block() }.also { it.join() } }
    }

    private suspend fun Flow<AppPhase>.collectPhases(f: Fakes, report: (String, IOException) -> Unit = { _, _ -> }) =
        collectSurviving("phase", report) { p -> f.steps.onPhase(p) { f.ran += OPENED } }

    @Test
    fun theFactoryKeepsVaultManagersListInOrder() = runBlocking {
        val f = Fakes()
        assertEquals(lockOrder, f.steps.lockOrder)
        f.steps.onPhase(AppPhase.Locked) { f.ran += OPENED }
        assertEquals("a lock: the release, every clear, the own profile", lockOrder, f.ran)
        f.ran.clear()
        f.steps.onPhase(AppPhase.Unlocked) { f.ran += OPENED }
        assertEquals(listOf(PhaseSteps.REPLAY, OPENED, PhaseSteps.OWN_PROFILE), f.ran)
        f.ran.clear()
        f.steps.onPhase(AppPhase.Starting) { f.ran += OPENED }
        assertEquals(listOf(PhaseSteps.REPLAY, PhaseSteps.OWN_PROFILE), f.ran)
        f.ran.clear()
        f.steps.onPhase(AppPhase.SignedOut) { f.ran += OPENED }
        assertEquals("a sign-out clears as a lock does", lockOrder, f.ran)
    }

    @Test
    fun afterAnIOExceptionOnOneLockTheClearsRunAgainAndTheNextLockStillClears() {
        val io = IOException("relay")
        val f = Fakes(PhaseSteps.ATTACHMENTS to io)
        val reports = mutableListOf<IOException>()
        val p = ProcessScope()
        p.run { flowOf(AppPhase.Locked, AppPhase.Unlocked, AppPhase.Locked).collectPhases(f) { _, e -> reports += e } }
        assertEquals(
            // The first lock: every step, then the clears once more; the unlock; the second lock: every step once.
            lockOrder + clears + listOf(PhaseSteps.REPLAY, OPENED, PhaseSteps.OWN_PROFILE) + lockOrder,
            f.ran,
        )
        assertEquals(listOf(io), reports)
        assertEquals(emptyList<Throwable>(), p.relay + p.fatal)
    }

    @Test
    fun clearsThatFailTwiceEndTheProcess() {
        val f = Fakes(PhaseSteps.FEED to IOException("first"), PhaseSteps.FEED to IOException("again"))
        val p = ProcessScope()
        p.run { flowOf(AppPhase.Locked, AppPhase.Locked).collectPhases(f) }
        assertEquals("nothing reported as a relay error", 0, p.relay.size)
        assertTrue(p.fatal.single() is LockClearFailed)
        assertEquals("both runs of the first lock ran every clear; the collector ended there", lockOrder + clears, f.ran)
    }

    @Test
    fun aBugBehindAnIOExceptionIsFatalNotARelayError() {
        // The #181 reviewer's probe: step 1 throws an IOException, step 2 an IllegalStateException.
        val f = Fakes(PhaseSteps.SOCIAL to IOException("relay"), PhaseSteps.ATTACHMENTS to IllegalStateException("bug"))
        val p = ProcessScope()
        p.run { flowOf(AppPhase.Locked, AppPhase.Locked).collectPhases(f) }
        assertEquals(0, p.relay.size)
        val crash = p.fatal.single()
        assertTrue(crash is CollectorFailure)
        assertTrue(crash.cause is IOException)
        // Cleared again on the retry (which succeeded); the bug still crashes, after every step.
        assertEquals(lockOrder + clears, f.ran)
    }

    @Test
    fun aBugIsFatalAfterEveryStepRan() {
        val bug = IllegalStateException("bug")
        val f = Fakes(PhaseSteps.ITEMS to bug)
        val p = ProcessScope()
        p.run { flowOf(AppPhase.Locked, AppPhase.Locked).collectPhases(f) }
        assertSame(bug, p.fatal.single())
        assertEquals(0, p.relay.size)
        assertEquals(lockOrder + clears, f.ran)
    }

    @Test
    fun aStrayCancellationWhileTheCollectorRunsIsFatal() {
        val f = Fakes(PhaseSteps.SHARING to CancellationException("stray"))
        val p = ProcessScope()
        p.run { flowOf(AppPhase.Locked, AppPhase.Locked).collectPhases(f) }
        val crash = p.fatal.single()
        assertTrue(crash is CollectorFailure)
        assertTrue(crash.cause is CancellationException)
        assertEquals(0, p.relay.size)
    }

    @Test
    fun aCancellationOfTheCollectorItselfEndsItQuietly() {
        val p = ProcessScope()
        var seen = 0
        val job = p.run {
            flowOf(1, 2).collectSurviving("events", { _, _ -> error("not reported") }) {
                seen++
                currentCoroutineContext().job.cancel()
                throw CancellationException("ended")
            }
        }
        assertTrue(job.isCancelled)
        assertEquals(1, seen)
        assertEquals(emptyList<Throwable>(), p.relay + p.fatal)
    }

    @Test
    fun aBugDeepInTheSuppressedChainIsFound() {
        val io = IOException("relay")
        val inner = IOException("later")
        inner.addSuppressed(IllegalStateException("bug"))
        io.addSuppressed(inner)
        val e = assertThrows(CollectorFailure::class.java) {
            surviveEvent("phase", { _, _ -> error("not reported") }, { true }) { throw io }
        }
        assertSame(io, e.cause)
    }

    @Test
    fun aCancellationBehindAnIOExceptionIsRethrownOnlyWhenCancelled() {
        val cancel = CancellationException("ended")
        val io = IOException("relay").apply { addSuppressed(cancel) }
        assertSame(cancel, assertThrows(CancellationException::class.java) { surviveEvent("phase", { _, _ -> }, { false }) { throw io } })
        assertThrows(CollectorFailure::class.java) { surviveEvent("phase", { _, _ -> }, { true }) { throw io } }
    }

    @Test
    fun aCollectorKeepsGoingAfterIOExceptionsFromThePlainBody() = runBlocking {
        val seen = mutableListOf<Int>()
        var reported = 0
        flowOf(1, 2, 3).collectSurviving("events", { _, _ -> reported++ }) { n ->
            if (n != 3) throw IOException("relay $n")
            seen += n
        }
        assertEquals(listOf(3), seen)
        assertEquals(2, reported)
    }

    private companion object {
        const val OPENED = "opened"
    }
}
