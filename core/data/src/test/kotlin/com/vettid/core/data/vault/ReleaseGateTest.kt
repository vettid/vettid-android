// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.vault

import com.vettid.core.attestation.manifest.Release
import com.vettid.core.attestation.manifest.ReleaseStatus
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.social.SocialManager
import com.vettid.core.vault.PendingPage
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Rule 3: the app requires the vault's current release. The release gate's state, its reads, its pause and its events. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // one gate: its state, retries, pause, events and steps side by side
class ReleaseGateTest {
    private fun rel(n: Long, status: String = "active") = ReleaseView(n, "%02d".format(n).repeat(48), status, null, "https://vettid.org/r/$n")

    /** Release 4 behind release 5. */
    private val behind = ReleaseUpdateOffer(rel(4), rel(5))

    /** The manifest as the gate reads it: each read answers the next of [answers] (a failure is thrown), else [offer]. */
    private class Ops : ReleaseUpdateOps {
        var offer: ReleaseUpdateOffer? = null
        val answers = ArrayDeque<Result<ReleaseUpdateOffer?>>()
        var release: Long? = 4

        /** The virtual time of each read. */
        val reads = mutableListOf<Long>()
        var clock: () -> Long = { 0 }

        /** When set, the next read takes its answer, then waits for it (a read on its way while the generation moves). */
        var gate: CompletableDeferred<Unit>? = null

        /** A read throws this (a failure that is not the vault's). */
        var bug: Exception? = null

        /** Every read fails while set. */
        var failing = false

        /** The update's unlocks, in turn (then opened). */
        val results = ArrayDeque<UpdateStepResult>()

        /** The app is in front ([ReleaseUpdateOps.inFront]). */
        var front = true

        /** When set, a read fails with this kind while [failing]. */
        var failKind = FailureKind.NETWORK

        override suspend fun offer(): ReleaseUpdateOffer? {
            reads += clock()
            val answer = answers.removeFirstOrNull()
            val held = offer
            gate?.let { g ->
                gate = null
                g.await()
            }
            bug?.let { throw it }
            if (failing) throw VaultFailure(failKind)
            return if (answer != null) answer.getOrThrow() else held
        }

        /** How often the vault's release number was read. */
        var numberReads = 0

        override fun releaseNumber(): Long? {
            numberReads++
            return release
        }
        override fun ownerCheckDue() = false
        override suspend fun lock() = Unit
        /** Runs inside each of the update's unlocks, before it answers. */
        var duringUnlock: suspend () -> Unit = {}

        override suspend fun unlock(pin: TakenPin, approve: ReleaseView?, abandon: Boolean, onStarting: (Long) -> Unit): UpdateStepResult {
            duringUnlock()
            return results.removeFirstOrNull() ?: UpdateStepResult.Opened
        }
        override fun canAbandon() = false
        override fun inFront() = front
    }

    /** The pause's changes as they are told. */
    private fun ReleaseUpdateManager.toldPauses(): MutableList<Boolean> {
        val told = mutableListOf<Boolean>()
        onGateChange = { c -> if (c.pausedChanged) told += c.paused }
        return told
    }

    private fun failure() = Result.failure<ReleaseUpdateOffer?>(VaultFailure(FailureKind.MANIFEST_UNAVAILABLE))

    private fun TestScope.manager(ops: Ops): ReleaseUpdateManager {
        ops.clock = { testScheduler.currentTime }
        return ReleaseUpdateManager(backgroundScope, ops, userGuidUsable = { true }, elapsedMs = { testScheduler.currentTime })
    }

    // --- the state ---

    @Test
    fun requiredFromTheOpeningUntilTheReadThenWhileBehind() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val told = m.toldPauses()
        assertFalse(m.required.value)
        m.vaultOpened()
        assertTrue("from the opening", m.required.value && m.checking.value)
        assertFalse("the first reads start at once", m.paused.value)
        ops.offer = behind
        m.refreshOffer()
        assertTrue(m.required.value && m.behind.value && m.paused.value)
        assertFalse(m.checking.value)
        ops.offer = null
        m.refreshOffer()
        assertFalse(m.required.value || m.behind.value || m.paused.value)
        assertEquals(listOf(true, false), told)
    }

    /** An offer read before the vault moved counts for nothing: behind compares it with the release the vault is on now. */
    @Test
    fun aStaleOfferIsNotBehind() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.offer = behind
        m.vaultOpened()
        m.refreshOffer()
        assertTrue(m.behind.value)
        // The update moved the vault to release 5 and opened it there; the read still answers the old offer.
        ops.release = 5
        m.vaultOpened()
        m.refreshOffer()
        assertFalse(m.behind.value || m.required.value || m.paused.value)
    }

    /** Fail closed: a failed read after the opening keeps the check and pauses the background work. */
    @Test
    fun aFailedReadKeepsTheCheckAndPauses() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val told = m.toldPauses()
        m.vaultOpened()
        ops.answers += failure()
        m.refreshOffer()
        assertTrue(m.checking.value && m.required.value && m.paused.value)
        assertEquals(FailureKind.MANIFEST_UNAVAILABLE, m.checkFailure.value)
        assertFalse("never counted as current", m.behind.value)
        assertEquals(listOf(true), told)
    }

    /**
     * After this opening's check passed, a read that could not reach the manifest keeps the last verified answer: nothing
     * is shown or paused, and it is read again quietly (parity with iOS).
     */
    @Test
    fun aManifestOutOfReachAfterTheCheckKeepsTheLastAnswer() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val told = m.toldPauses()
        m.vaultOpened()
        runCurrent()
        assertFalse(m.required.value)
        ops.answers += failure()
        ops.answers += Result.failure(VaultFailure(FailureKind.NETWORK))
        m.refreshOffer()
        assertFalse(m.paused.value || m.required.value || m.checking.value)
        assertNull(m.checkFailure.value)
        advanceTimeBy(2_001)
        assertEquals("retried quietly", 3, ops.reads.size)
        assertFalse(m.paused.value || m.required.value)
        advanceTimeBy(5_000)
        assertEquals("read again, and it succeeded", 4, ops.reads.size)
        advanceTimeBy(600_000)
        assertEquals(4, ops.reads.size)
        assertTrue("never paused", told.isEmpty())
    }

    /** The same while behind: the last verified answer (behind) stays. */
    @Test
    fun aManifestOutOfReachWhileBehindStaysBehind() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.offer = behind
        m.vaultOpened()
        runCurrent()
        ops.answers += failure()
        m.refreshOffer()
        assertTrue(m.behind.value && m.required.value && m.paused.value)
        assertFalse(m.checking.value)
        assertNull(m.checkFailure.value)
    }

    /**
     * After this opening's check passed, a read that fails verification (the signature, an older manifest, a release that
     * does not match or is not listed) holds the gate as the first check does: required, the checking screen with the
     * failure, "Try again" and the retries; the work pauses until a read succeeds.
     */
    @Test
    fun aVerificationFailureAfterTheCheckHoldsTheGate() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val told = m.toldPauses()
        m.vaultOpened()
        runCurrent()
        assertFalse(m.required.value)
        ops.answers += Result.failure(VaultFailure(FailureKind.MANIFEST, "manifest_older"))
        m.refreshOffer()
        assertTrue(m.checking.value && m.required.value && m.paused.value)
        assertEquals(FailureKind.MANIFEST, m.checkFailure.value)
        assertEquals(listOf(true), told)
        // A read that then cannot reach the manifest does not lift it: only a read that succeeds does.
        ops.answers += failure()
        advanceTimeBy(2_001)
        assertTrue(m.checking.value && m.required.value && m.paused.value)
        advanceTimeBy(5_000)
        assertFalse(m.checking.value || m.required.value || m.paused.value)
        assertEquals(listOf(true, false), told)
    }

    /** Anything not clearly "out of reach" counts as a verification failure (fail closed). */
    @Test
    fun onlyAManifestOutOfReachKeepsTheLastAnswer() {
        val reach = setOf(
            FailureKind.NETWORK, FailureKind.NO_RESPONSE, FailureKind.MANIFEST_UNAVAILABLE, FailureKind.VAULT_UNAVAILABLE,
            FailureKind.SERVICE_PAUSED, FailureKind.RATE_LIMITED,
        )
        FailureKind.entries.forEach { assertEquals(it.name, it in reach, ReleaseCheckFailure.unavailable(it)) }
        listOf(FailureKind.MANIFEST, FailureKind.RELEASE_UNKNOWN, FailureKind.ENCLAVE, FailureKind.OTHER, FailureKind.CLOCK).forEach {
            assertFalse(it.name, ReleaseCheckFailure.unavailable(it))
        }
    }

    /** Before this opening's first success, a manifest out of reach holds the gate as before. */
    @Test
    fun aManifestOutOfReachBeforeTheCheckHolds() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.answers += Result.failure(VaultFailure(FailureKind.NETWORK))
        m.vaultOpened()
        runCurrent()
        assertTrue(m.checking.value && m.required.value && m.paused.value)
        assertEquals(FailureKind.NETWORK, m.checkFailure.value)
    }

    // --- the retry ---

    @Test
    fun aFailingCheckIsReadAgainAfter2515And30sThenEveryMinute() = runTest {
        val ops = Ops()
        val m = manager(ops)
        repeat(7) { ops.answers += failure() }
        // The opening reads by itself (also when the phase's followers saw no opening).
        m.vaultOpened()
        advanceTimeBy(2_000 + 5_000 + 15_000 + 30_000 + 60_000 + 60_000 + 60_000 + 1)
        assertEquals(listOf(0L, 2_000L, 7_000L, 22_000L, 52_000L, 112_000L, 172_000L, 232_000L), ops.reads)
        assertFalse("read once the failures were over", m.checking.value)
        assertNull(m.checkFailure.value)
        advanceTimeBy(600_000)
        assertEquals("no read after one succeeded", 8, ops.reads.size)
    }

    /** A read that succeeds ends the retries: a failure after it starts them over at 2 s, not where they were. */
    @Test
    fun aFailureAfterASuccessIsRetriedAt2sAgain() = runTest {
        val ops = Ops()
        val m = manager(ops)
        repeat(5) { ops.answers += failure() }
        m.vaultOpened()
        advanceTimeBy(52_001)
        assertEquals(listOf(0L, 2_000L, 7_000L, 22_000L, 52_000L), ops.reads)
        m.refreshOffer()
        assertFalse(m.checking.value)
        advanceTimeBy(7_999)
        ops.answers += Result.failure(VaultFailure(FailureKind.MANIFEST))
        m.refreshOffer()
        assertTrue(m.checking.value)
        advanceTimeBy(2_001)
        assertEquals(listOf(0L, 2_000L, 7_000L, 22_000L, 52_000L, 52_001L, 60_000L, 62_000L), ops.reads)
        assertFalse(m.checking.value || m.paused.value)
    }

    /**
     * In the background a check held by a verification failure is not read again (no polling every minute while nobody
     * looks); the return to the foreground reads it (as VaultManager.onForeground does), and a failure then starts the
     * retries over.
     */
    @Test
    fun noRetriesInTheBackgroundAfterAVerificationFailure() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.failKind = FailureKind.MANIFEST
        ops.failing = true
        m.vaultOpened()
        runCurrent()
        advanceTimeBy(1_000)
        ops.front = false
        advanceTimeBy(600_000)
        assertEquals("the opening's read only", 1, ops.reads.size)
        assertTrue("still held", m.checking.value && m.required.value && m.paused.value)
        ops.front = true
        m.refreshOffer()
        assertEquals(2, ops.reads.size)
        advanceTimeBy(2_001)
        assertEquals("retried again in front", 3, ops.reads.size)
        // Opened in the background: no retry either; "Try again" still reads.
        ops.front = false
        m.vaultOpened()
        runCurrent()
        advanceTimeBy(600_000)
        assertEquals(4, ops.reads.size)
        m.checkNow()
        runCurrent()
        assertEquals(5, ops.reads.size)
    }

    /**
     * A manifest only out of reach is read again in the background too (2, 5, 15, 30 s, then every minute), before the
     * opening's first success as after it; a verification failure in the background then ends the retries.
     */
    @Test
    fun aManifestOutOfReachIsRetriedInTheBackground() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.failing = true
        ops.front = false
        m.vaultOpened()
        runCurrent()
        advanceTimeBy(2_000 + 5_000 + 15_000 + 30_000 + 60_000 + 1)
        assertEquals("opened in the background, retried there", listOf(0L, 2_000L, 7_000L, 22_000L, 52_000L, 112_000L), ops.reads)
        assertTrue("still held", m.checking.value && m.paused.value)
        // A verification failure: no more reads in the background.
        ops.failKind = FailureKind.MANIFEST
        advanceTimeBy(60_000)
        assertEquals(7, ops.reads.size)
        advanceTimeBy(600_000)
        assertEquals(7, ops.reads.size)
        // After a success, a manifest out of reach is retried quietly in the background as well.
        ops.failing = false
        m.refreshOffer()
        assertFalse(m.checking.value)
        ops.failKind = FailureKind.NETWORK
        ops.failing = true
        m.refreshOffer()
        assertFalse("quiet", m.paused.value || m.checking.value)
        advanceTimeBy(2_001)
        assertEquals(10, ops.reads.size)
        ops.failing = false
        advanceTimeBy(5_000)
        assertEquals("read again, and it succeeded", 11, ops.reads.size)
        advanceTimeBy(600_000)
        assertEquals(11, ops.reads.size)
    }

    /**
     * A relay reconnect reads the manifest, but not within a minute of a read that succeeded, nor while the retries run
     * (they read it); a check held in the background by a verification failure (no retries) is read.
     */
    @Test
    fun aRelayReconnectReadsUnlessARecentReadOrTheRetriesDo() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.vaultOpened()
        runCurrent()
        assertEquals(1, ops.reads.size)
        advanceTimeBy(59_000)
        m.relayReconnected()
        runCurrent()
        assertEquals("a read succeeded within the minute", 1, ops.reads.size)
        advanceTimeBy(1_000)
        m.relayReconnected()
        runCurrent()
        assertEquals(2, ops.reads.size)
        // A manifest out of reach: the retries run, the reconnect leaves it to them.
        ops.failing = true
        advanceTimeBy(60_000)
        m.refreshOffer()
        assertEquals(3, ops.reads.size)
        m.relayReconnected()
        runCurrent()
        assertEquals("the retries read it", 3, ops.reads.size)
        // A verification failure in the background: no retries, so the reconnect reads.
        ops.failKind = FailureKind.MANIFEST
        ops.front = false
        advanceTimeBy(2_001)
        assertEquals(4, ops.reads.size)
        advanceTimeBy(600_000)
        assertEquals("the retries ended", 4, ops.reads.size)
        m.relayReconnected()
        runCurrent()
        assertEquals(5, ops.reads.size)
    }

    @Test
    fun aLockEndsTheRetry() = runTest {
        val ops = Ops()
        val m = manager(ops)
        repeat(3) { ops.answers += failure() }
        m.vaultOpened()
        advanceTimeBy(2_001)
        assertEquals(2, ops.reads.size)
        m.vaultClosed()
        assertFalse(m.checking.value || m.required.value || m.paused.value)
        advanceTimeBy(600_000)
        assertEquals(2, ops.reads.size)
    }

    @Test
    fun aNewOpeningEndsTheRetryOfTheOneBefore() = runTest {
        val ops = Ops()
        val m = manager(ops)
        repeat(3) { ops.answers += failure() }
        m.vaultOpened()
        runCurrent()
        m.vaultOpened()
        assertTrue("the new opening's own check", m.checking.value)
        assertNull(m.checkFailure.value)
        advanceTimeBy(600_000)
        // The new opening's read at once, then its own retries: none of the first opening's at 2 s.
        assertEquals(listOf(0L, 0L, 2_000L, 7_000L), ops.reads)
        assertFalse(m.checking.value)
    }

    /** A lock and a new opening while a check fails: the new opening's own retries read for it, from its own failure. */
    @Test
    fun aRetryFromBeforeALockNeverReadsForTheNextOpening() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.answers += failure()
        m.vaultOpened()
        runCurrent()
        advanceTimeBy(1_000)
        m.vaultClosed()
        ops.answers += failure()
        m.vaultOpened()
        runCurrent()
        assertTrue(m.checking.value && m.paused.value)
        advanceTimeBy(2_001)
        assertEquals("the new opening's retry, 2 s after its own failure", listOf(0L, 1_000L, 3_000L), ops.reads)
        assertFalse(m.checking.value || m.paused.value)
    }

    /** A failed read while the vault is not open (locked, or never opened) starts no check and no retry. */
    @Test
    fun aFailureWhileNotOpenChangesNothing() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.answers += failure()
        m.refreshOffer()
        assertFalse(m.paused.value || m.required.value)
        advanceTimeBy(600_000)
        assertEquals(1, ops.reads.size)
    }

    /** "Try again" reads at once, and the automatic retries go on. */
    @Test
    fun tryAgainReadsAtOnce() = runTest {
        val ops = Ops()
        val m = manager(ops)
        repeat(3) { ops.answers += failure() }
        m.vaultOpened()
        runCurrent()
        advanceTimeBy(1_000)
        m.checkNow()
        runCurrent()
        assertEquals(listOf(0L, 1_000L), ops.reads)
        advanceTimeBy(1_001)
        assertEquals("the backoff goes on", listOf(0L, 1_000L, 2_000L), ops.reads)
        advanceTimeBy(5_000)
        assertFalse(m.checking.value)
    }

    // --- the generation ---

    /** A read begun before an opening neither sets an offer nor ends the new opening's check. */
    @Test
    fun aReadFromAnEarlierGenerationSetsAndEndsNothing() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val gate = CompletableDeferred<Unit>()
        ops.answers += Result.success(behind)
        ops.gate = gate
        m.vaultOpened()
        runCurrent()
        // The second opening's read waits too: its check stays.
        val second = CompletableDeferred<Unit>()
        ops.gate = second
        m.vaultOpened()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(m.checking.value)
        assertNull(m.offer.value)
        second.complete(Unit)
        runCurrent()
        assertFalse(m.checking.value || m.behind.value)
    }

    /**
     * Overlapping reads in one generation: an older read that ends after a newer one was taken is dropped, so a slow
     * "current" answer never lifts the gate a newer "behind" answer set.
     */
    @Test
    fun anOlderReadEndingLastNeverUndoesANewerOne() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val slow = CompletableDeferred<Unit>()
        ops.answers += Result.success(null)
        ops.gate = slow
        ops.offer = behind
        m.vaultOpened()
        runCurrent()
        m.refreshOffer()
        assertTrue(m.behind.value && m.required.value)
        slow.complete(Unit)
        runCurrent()
        assertTrue("still behind", m.behind.value && m.required.value && m.paused.value)
        assertEquals(behind, m.offer.value)
    }

    /** Nor does an older failure ending last start a check the newer read ended. */
    @Test
    fun anOlderFailureEndingLastIsDropped() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val slow = CompletableDeferred<Unit>()
        ops.answers += failure()
        ops.gate = slow
        m.vaultOpened()
        runCurrent()
        m.refreshOffer()
        assertFalse(m.checking.value)
        slow.complete(Unit)
        runCurrent()
        assertFalse(m.paused.value || m.required.value)
        assertNull(m.checkFailure.value)
    }

    /** Any failure of the read but a cancellation keeps the check, with its retries and its "Try again". */
    @Test
    fun aReadThatThrowsAnythingKeepsTheCheckAndRetries() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.bug = IllegalStateException("not the vault's")
        m.vaultOpened()
        runCurrent()
        assertTrue(m.checking.value && m.required.value && m.paused.value)
        assertEquals(FailureKind.OTHER, m.checkFailure.value)
        ops.bug = null
        advanceTimeBy(2_001)
        assertFalse(m.checking.value || m.paused.value)
        assertEquals(2, ops.reads.size)
    }

    /** The opening reads by itself: a close and an open conflated for the phase's followers still end in a read. */
    @Test
    fun anOpeningReadsTheManifestByItself() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.offer = behind
        m.vaultOpened()
        assertTrue(m.checking.value)
        runCurrent()
        assertEquals(1, ops.reads.size)
        assertFalse(m.checking.value)
        assertTrue(m.behind.value)
    }

    /** Nor does one that fails: no failure is set, no retry starts. */
    @Test
    fun aFailedReadFromAnEarlierGenerationStartsNoRetry() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val gate = CompletableDeferred<Unit>()
        ops.answers += failure()
        ops.gate = gate
        m.vaultOpened()
        runCurrent()
        m.vaultClosed()
        gate.complete(Unit)
        runCurrent()
        assertNull(m.checkFailure.value)
        assertFalse(m.paused.value)
        advanceTimeBy(600_000)
        assertEquals(1, ops.reads.size)
    }

    /** The wipe's clear needs no release number: it reads none from the vault's state. */
    @Test
    fun aClearReadsNoReleaseNumber() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.offer = behind
        m.vaultOpened()
        m.refreshOffer()
        assertTrue(m.behind.value)
        ops.numberReads = 0
        m.clear()
        assertEquals(0, ops.numberReads)
        assertFalse(m.behind.value || m.required.value)
        assertTrue(m.paused.value)
    }

    /** A wipe or a deletion while behind: the check ends and the offer goes, and nothing is told (no list is read). */
    @Test
    fun aClearWhileBehindTellsNothing() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.offer = behind
        m.vaultOpened()
        m.refreshOffer()
        val told = m.toldPauses()
        m.clear()
        assertFalse(m.required.value || m.behind.value)
        assertTrue("the work stays paused until the next opening", m.paused.value)
        assertNull(m.offer.value)
        m.vaultClosed()
        assertTrue("a lock after the wipe keeps it", m.paused.value)
        ops.offer = null
        m.vaultOpened()
        assertFalse(m.paused.value)
        assertTrue(told.isEmpty())
    }

    /** The update done: the offer goes, and the pause lifts (told: the lists are read). */
    @Test
    fun theUpdateDoneLiftsThePause() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.offer = behind
        m.vaultOpened()
        m.refreshOffer()
        val told = m.toldPauses()
        ops.results += UpdateStepResult.Moved
        assertTrue(m.start("2468", behind))
        runCurrent()
        assertEquals(UpdateStep.DONE, m.progress.value?.step)
        m.finish()
        assertFalse(m.paused.value || m.required.value)
        assertEquals(listOf(false), told)
    }

    /** The update done, and the opening on the new release already found a newer one: that offer stays (behind again). */
    @Test
    fun theUpdateDoneKeepsANewerOffer() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.offer = behind
        m.vaultOpened()
        m.refreshOffer()
        ops.results += UpdateStepResult.Moved
        assertTrue(m.start("2468", behind))
        runCurrent()
        assertEquals(UpdateStep.DONE, m.progress.value?.step)
        // The opening on release 5 reads release 6.
        ops.release = 5
        ops.offer = ReleaseUpdateOffer(rel(5), rel(6))
        m.vaultOpened()
        runCurrent()
        assertTrue(m.behind.value)
        m.finish()
        assertTrue("the newer offer stays", m.behind.value && m.required.value && m.paused.value)
        assertEquals(6L, m.offer.value?.target?.number)
    }

    /** Also when that opening's read lands before the update says it is done. */
    @Test
    fun aNewerOfferReadBeforeTheUpdateIsDoneStays() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.offer = behind
        m.vaultOpened()
        m.refreshOffer()
        ops.results += UpdateStepResult.Moved
        var unlocks = 0
        ops.duringUnlock = {
            if (++unlocks == 2) {
                ops.release = 5
                ops.offer = ReleaseUpdateOffer(rel(5), rel(6))
                m.vaultOpened()
                m.refreshOffer()
            }
        }
        assertTrue(m.start("2468", behind))
        runCurrent()
        assertEquals(UpdateStep.DONE, m.progress.value?.step)
        assertTrue("the newer offer stays", m.behind.value && m.required.value)
        assertEquals(6L, m.offer.value?.target?.number)
    }

    // --- the fail-closed offer ---

    private fun entry(n: Long, status: ReleaseStatus = ReleaseStatus.ACTIVE, pcr0: String = "%02d".format(n).repeat(48)) =
        Release(n, pcr0, "aa".repeat(48), "bb".repeat(48), "cc".repeat(32), status, Instant.EPOCH, null, "https://vettid.org/r/$n")

    @Test
    fun aListedReleaseIsBehindTheNewestActiveOne() {
        val o = ReleaseGateOffer.of(entry(4).pcr0, 4, listOf(entry(4, ReleaseStatus.DEPRECATED), entry(5), entry(6)))!!
        assertEquals(4L, o.current.number)
        assertEquals(6L, o.target.number)
        assertEquals(listOf(5L), o.between.map { it.number })
        assertNull("current", ReleaseGateOffer.of(entry(6).pcr0, 6, listOf(entry(5), entry(6))))
        // With no fingerprint recorded, by number.
        assertEquals(6L, ReleaseGateOffer.of("", 4, listOf(entry(4), entry(6)))!!.target.number)
    }

    /** A release the manifest no longer lists while a newer active one is: behind, as one that has ended. */
    @Test
    fun anUnlistedReleaseIsBehindANewerOneAsEnded() {
        val o = ReleaseGateOffer.of(entry(3).pcr0, 3, listOf(entry(4), entry(5)))!!
        assertEquals(3L, o.current.number)
        assertEquals(entry(3).pcr0, o.current.pcr0)
        assertEquals(UpdateNoticeKind.ENDED, o.kind)
        assertEquals(5L, o.target.number)
        assertEquals(listOf(4L), o.between.map { it.number })
    }

    @Test
    fun anUnlistedReleaseWithNothingNewerIsUnverifiable() {
        val e = assertThrows(VaultFailure::class.java) { ReleaseGateOffer.of(entry(7).pcr0, 7, listOf(entry(4), entry(5))) }
        assertEquals(FailureKind.MANIFEST, e.kind)
        assertEquals(ReleaseGateOffer.CODE_UNLISTED, e.code)
        assertThrows(VaultFailure::class.java) { ReleaseGateOffer.of(entry(5).pcr0 + "x", 5, listOf(entry(5, ReleaseStatus.DEPRECATED))) }
    }

    @Test
    fun aFingerprintListedUnderAnotherNumberIsUnverifiable() {
        val e = assertThrows(VaultFailure::class.java) { ReleaseGateOffer.of(entry(5).pcr0, 4, listOf(entry(4), entry(5), entry(6))) }
        assertEquals(FailureKind.MANIFEST, e.kind)
        assertEquals(ReleaseGateOffer.CODE_MISMATCH, e.code)
    }

    @Test
    fun releaseZeroIsUnverifiable() {
        val e = assertThrows(VaultFailure::class.java) { ReleaseGateOffer.of("", 0, listOf(entry(4))) }
        assertEquals(FailureKind.MANIFEST, e.kind)
        assertEquals(ReleaseGateOffer.CODE_UNVERIFIABLE, e.code)
        assertThrows(VaultFailure::class.java) { ReleaseGateOffer.checkNumber(0) }
    }

    // --- the events ---

    @Test
    fun whilePausedOnlyWhatNoListGivesBackIsTaken() {
        fun takes(t: EventTaker, type: String) = ReleaseGateEvents.takes(t, type, paused = true)
        // A key change (an authentication's verdict) and its key, a pairing's and the devices' events.
        assertTrue(takes(EventTaker.SOCIAL, "connection.authenticate.result"))
        assertTrue(takes(EventTaker.SOCIAL, "connection.authenticate.key"))
        assertTrue(takes(EventTaker.PAIRING, "device.pair.pending"))
        assertTrue(takes(EventTaker.DEVICES, "device.updated"))
        assertTrue(takes(EventTaker.LOCATION, "location.event"))
        assertTrue(takes(EventTaker.PRESENCE, "presence.result"))
        // What only changes a list waits.
        assertFalse(takes(EventTaker.SOCIAL, "message.new"))
        assertFalse(takes(EventTaker.SOCIAL, "connection.authenticate.pending"))
        assertFalse(takes(EventTaker.ITEMS, "item.changed"))
        assertFalse(takes(EventTaker.SHARING, "sync.event"))
        assertFalse(takes(EventTaker.VOTES, "vote.event"))
        assertFalse(takes(EventTaker.ATTACHMENTS, "attachment.ready"))
        // Not paused: every one.
        EventTaker.entries.forEach { assertTrue(ReleaseGateEvents.takes(it, "message.new", paused = false)) }
    }

    private val base = Instant.parse("2026-10-29T12:00:00Z")
    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = base.plusSeconds(1), body = body.toByteArray()))

    /** As the vault manager routes an event to the connections while the gate may pause them. */
    private suspend fun route(social: SocialManager, m: ReleaseUpdateManager, e: VaultMessage) {
        if (ReleaseGateEvents.takes(EventTaker.SOCIAL, e.type, m.paused.value)) social.onEvent(e)
    }

    /** A changed key reaches the connections while behind: its warning is in no list a read gives back. */
    @Test
    fun aKeyChangeIsTakenWhileBehind() = runTest {
        val social = SocialManager(backgroundScope, api = { throw VaultFailure(FailureKind.OTHER) }, credential = credential, clock = Clock.fixed(base, ZoneOffset.UTC))
        val ops = Ops()
        val m = manager(ops)
        ops.offer = behind
        m.vaultOpened()
        m.refreshOffer()
        assertTrue(m.paused.value)
        route(social, m, event("connection.authenticate.result", """{"connection_id":"c1","request_id":"r1","authenticated":true,"key_changed":true}"""))
        runCurrent()
        assertEquals(true, social.authentication.value["c1"]?.keyChanged)
    }

    /** An ask's event waits while behind; the lists read when the pause lifts bring it. */
    @Test
    fun anAskHeldWhileBehindIsThereAfterTheLift() = runTest {
        val social = SocialManager(backgroundScope, api = { throw VaultFailure(FailureKind.OTHER) }, credential = credential, clock = Clock.fixed(base, ZoneOffset.UTC))
        val ask = """{"connection_id":"c1","request_id":"r1","exp":"2026-10-29T13:00:00.000Z"}"""
        val ops = Ops()
        val m = manager(ops)
        val steps = ReleasePauseSteps(
            unlocked = { true },
            checkGated = { false },
            paused = { m.paused.value },
            required = { m.required.value },
            stopVotes = {},
            gateChanged = {},
            reads = {
                backgroundScope.launch {
                    social.refreshPendingFrom { PendingPage(listOf(VaultJson.parseObject("""{"kind":"authenticate","body":$ask}""".toByteArray())), null, base.plusSeconds(10)) }
                }
            },
            sends = {},
        )
        m.onGateChange = steps::onGate
        ops.offer = behind
        m.vaultOpened()
        m.refreshOffer()
        route(social, m, event("connection.authenticate.pending", ask))
        runCurrent()
        assertTrue("held while behind", social.approvals.value.isEmpty())
        ops.offer = null
        m.refreshOffer()
        runCurrent()
        assertEquals(listOf("auth:r1"), social.approvals.value.map { it.key })
    }

    // --- the pause ---

    private class Steps {
        val log = mutableListOf<String>()
        var open = true
        var checkGated = false
        var paused = false
        var required = false
        val pause = ReleasePauseSteps(
            unlocked = { open },
            checkGated = { checkGated },
            paused = { paused },
            required = { required },
            stopVotes = { log += "stopVotes" },
            gateChanged = { log += "gate:$it" },
            reads = { log += "reads" },
            sends = { log += "sends" },
        )
    }

    /** The pause lifts and the check is over (a read found the vault current). */
    private val lift = GateChange(paused = false, required = false, pausedChanged = true, requiredChanged = true)

    /** A read found the vault behind (or failed). */
    private val pause = GateChange(paused = true, required = true, pausedChanged = true, requiredChanged = false)

    /** The lists are read again when the pause lifts with the vault open: once, as after an unlock; and what sends starts. */
    @Test
    fun theListsAreReadAgainOnTheLift() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val s = Steps()
        m.onGateChange = s.pause::onGate
        ops.offer = behind
        m.vaultOpened()
        m.refreshOffer()
        assertEquals(listOf("gate:false", "stopVotes"), s.log)
        s.log.clear()
        ops.offer = null
        m.refreshOffer()
        m.refreshOffer()
        assertEquals(listOf("gate:true", "reads", "sends"), s.log)
    }

    /** The first check over, the vault current: what sends starts (the opening read the lists already). */
    @Test
    fun theEndOfTheCheckStartsTheSends() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val s = Steps()
        m.onGateChange = s.pause::onGate
        val gate = CompletableDeferred<Unit>()
        ops.gate = gate
        m.vaultOpened()
        runCurrent()
        assertTrue(s.log.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("gate:true", "sends"), s.log)
        // A lock and the next opening: its own check's end starts them again.
        s.log.clear()
        m.vaultClosed()
        m.vaultOpened()
        runCurrent()
        assertEquals(listOf("gate:true", "sends"), s.log)
    }

    /** The first check finds the vault behind: nothing sends until the lift. */
    @Test
    fun aCheckEndingBehindSendsNothingUntilTheLift() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val s = Steps()
        m.onGateChange = s.pause::onGate
        ops.offer = behind
        m.vaultOpened()
        runCurrent()
        assertEquals(listOf("gate:false", "stopVotes"), s.log)
        s.log.clear()
        ops.offer = null
        m.refreshOffer()
        assertEquals(listOf("gate:true", "reads", "sends"), s.log)
    }

    /**
     * A lock and an unlock conflated into one open phase (no lock step, no opened steps) while a failed check paused the
     * work: the opening's check, once over, runs the lift's reads it owed.
     */
    @Test
    fun aConflatedLockAndUnlockWhileFailingReadsOnceTheCheckPasses() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val s = Steps()
        m.onGateChange = s.pause::onGate
        ops.failing = true
        m.vaultOpened()
        runCurrent()
        assertEquals(listOf("gate:false", "stopVotes"), s.log)
        s.log.clear()
        ops.failing = false
        m.vaultOpened()
        runCurrent()
        assertEquals(listOf("gate:true", "reads", "sends"), s.log)
        // Not conflated (the lock's step ran): the opened steps read; the check's end only sends.
        ops.failing = true
        m.vaultOpened()
        runCurrent()
        m.vaultClosed()
        s.log.clear()
        ops.failing = false
        m.vaultOpened()
        runCurrent()
        assertEquals(listOf("gate:true", "sends"), s.log)
    }

    /**
     * The lock's step ran after the next opening was published (so it did not end the check), and the opened steps follow:
     * the reads that opening owed run once, whichever comes first, the opened steps or the end of its check.
     */
    @Test
    fun theOwedReadsRunOnceWithTheOpenedSteps() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val s = Steps()
        m.onGateChange = s.pause::onGate
        fun openedSteps() {
            if (m.openingReadsDue()) openedReads(m.paused.value) { s.log += "reads" }
        }
        // The opened steps first, then the end of the check.
        ops.failing = true
        m.vaultOpened()
        runCurrent()
        s.log.clear()
        val gate = CompletableDeferred<Unit>()
        ops.failing = false
        ops.gate = gate
        m.vaultOpened()
        runCurrent()
        openedSteps()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("reads", "gate:true", "sends"), s.log)
        // The end of the check first, then the opened steps.
        ops.failing = true
        m.vaultOpened()
        runCurrent()
        s.log.clear()
        ops.failing = false
        m.vaultOpened()
        runCurrent()
        openedSteps()
        assertEquals(listOf("gate:true", "reads", "sends"), s.log)
        // The next opening's opened steps read as usual.
        m.vaultClosed()
        m.vaultOpened()
        runCurrent()
        s.log.clear()
        openedSteps()
        assertEquals(listOf("reads"), s.log)
    }

    /** Not when the vault is no longer open, or is being deleted. */
    @Test
    fun noReadOnALiftWhileNotOpen() {
        val s = Steps()
        s.open = false
        s.pause.onGate(lift)
        assertEquals(listOf("gate:true"), s.log)
    }

    /**
     * §3.6.3: a lift while the owner check is due or held reads nothing; the reads wait for the check to lift, then run
     * once (the owner check's collector tells [ReleasePauseSteps.ownerCheckLifted]).
     */
    @Test
    fun aLiftDuringAHoldReadsOnlyOnceTheHoldLifts() {
        val s = Steps()
        s.checkGated = true
        s.pause.onGate(lift)
        assertEquals("nothing read or sent under the hold", listOf("gate:true"), s.log)
        s.pause.ownerCheckLifted()
        assertEquals("still held", listOf("gate:true"), s.log)
        s.checkGated = false
        s.pause.ownerCheckLifted()
        s.pause.ownerCheckLifted()
        assertEquals("once, when the hold lifts", listOf("gate:true", "reads", "sends"), s.log)
    }

    /** The hold lifts while the gate still pauses the work: nothing waits, and the gate's own lift reads (as before). */
    @Test
    fun aHoldLiftingWhilePausedReadsOnlyOnTheGatesLift() {
        val s = Steps()
        s.paused = true
        s.required = true
        s.pause.onGate(pause)
        s.pause.ownerCheckLifted()
        assertEquals(listOf("gate:false", "stopVotes"), s.log)
        s.paused = false
        s.required = false
        s.pause.onGate(lift)
        assertEquals(listOf("gate:false", "stopVotes", "gate:true", "reads", "sends"), s.log)
    }

    /** A lift's waiting reads go with a pause that comes back, or a lock. */
    @Test
    fun waitingReadsGoWithAPauseOrALock() {
        val s = Steps()
        s.checkGated = true
        s.pause.onGate(lift)
        s.pause.reset()
        s.checkGated = false
        s.pause.ownerCheckLifted()
        s.checkGated = true
        s.pause.onGate(lift)
        s.pause.onGate(pause)
        s.checkGated = false
        s.pause.ownerCheckLifted()
        assertFalse(s.log.contains("reads") || s.log.contains("sends"))
    }

    /** The pause's notices in the order of their changes: a late "paused" never stops the work after a lift. */
    @Test
    fun aLateNoticeIsDropped() {
        val told = mutableListOf<Boolean>()
        val n = GateNotices { told += it.paused }
        val paused = n.next(pause)
        val lifted = n.next(lift)
        n.tell(lifted)
        n.tell(paused)
        assertEquals(listOf(false), told)
        // A notice taken before a silent change (a lock, an opening) is dropped too.
        val again = n.next(pause)
        n.next(lift)
        n.tell(again)
        assertEquals(listOf(false), told)
    }

    /** A wipe while behind reads nothing: the clear tells nothing, and a read on its way after it changes nothing. */
    @Test
    fun aWipeWhileBehindReadsNothing() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val s = Steps()
        ops.offer = behind
        m.vaultOpened()
        m.refreshOffer()
        m.onGateChange = s.pause::onGate
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        ops.offer = null
        ops.gate = gate
        val read = backgroundScope.launch { m.refreshOffer() }
        runCurrent()
        m.clear()
        gate.complete(Unit)
        read.join()
        assertTrue(s.log.isEmpty())
    }

    /** The votes' hourly work, as `VotesManager.startSync`/`stopSync` run it: each run would send the votes' results. */
    private class HourlyVotes(private val scope: kotlinx.coroutines.CoroutineScope) {
        var runs = 0
        private var job: Job? = null
        fun start() {
            job?.cancel()
            job = scope.launch {
                while (true) {
                    runs++
                    delay(3_600_000)
                }
            }
        }
        fun stop() {
            job?.cancel()
        }
    }

    /** The vault's outward work as the gate's steps start it: the votes' hourly runs, presence's probe, location's sends. */
    private class Outward(scope: kotlinx.coroutines.CoroutineScope, m: ReleaseUpdateManager) {
        val votes = HourlyVotes(scope)
        val log = mutableListOf<String>()
        val steps = ReleasePauseSteps(
            unlocked = { true },
            checkGated = { false },
            paused = { m.paused.value },
            required = { m.required.value },
            stopVotes = votes::stop,
            gateChanged = { log += "location gate:$it" },
            reads = {},
            sends = {
                votes.start()
                log += "presence"
                log += "location"
            },
        )
    }

    /**
     * Nothing goes out during the check, nor while it fails: no votes run, and presence and location are gated
     * ([sendsGated], their `closed`/`due`); the opening's reads run meanwhile. Once a read succeeds the work starts.
     */
    @Test
    fun aVotesRunSendsNothingDuringAFailedCheck() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val out = Outward(backgroundScope, m)
        m.onGateChange = out.steps::onGate
        ops.failing = true
        m.vaultOpened()
        var read = false
        // The opening's own reads start at once (not the votes: they send).
        openedReads(m.paused.value) { read = true }
        assertTrue(read)
        assertTrue("presence and location wait during the check", sendsGated(featuresGated = false, checking = m.checking.value))
        runCurrent()
        assertTrue(m.paused.value)
        advanceTimeBy(3 * 3_600_000L)
        assertEquals("no run while the check fails", 0, out.votes.runs)
        assertTrue(sendsGated(featuresGated = false, checking = m.checking.value))
        assertFalse(out.log.contains("presence") || out.log.contains("location"))
        ops.failing = false
        advanceTimeBy(60_001)
        assertEquals("lifted: the work starts", 1, out.votes.runs)
        assertEquals(listOf("location gate:false", "location gate:true", "presence", "location"), out.log)
        assertFalse(sendsGated(featuresGated = false, checking = m.checking.value))
    }

    /** During a first check that passes, nothing goes out either; it starts once the check is over, the vault current. */
    @Test
    fun presenceLocationAndVotesWaitForTheFirstCheck() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val out = Outward(backgroundScope, m)
        m.onGateChange = out.steps::onGate
        val gate = CompletableDeferred<Unit>()
        ops.gate = gate
        m.vaultOpened()
        runCurrent()
        assertTrue(sendsGated(featuresGated = false, checking = m.checking.value))
        assertFalse(m.paused.value)
        assertEquals(0, out.votes.runs)
        assertTrue(out.log.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertFalse(sendsGated(featuresGated = false, checking = m.checking.value))
        assertEquals(1, out.votes.runs)
        assertEquals(listOf("location gate:true", "presence", "location"), out.log)
        // The owner check still gates them on its own.
        assertTrue(sendsGated(featuresGated = true, checking = false))
    }

    /** After a new credential the alarm is read whatever the gate says; the items wait while it pauses them. */
    @Test
    fun theAlarmIsReadWhileBehind() = runTest {
        val log = mutableListOf<String>()
        afterCredentialReset(paused = true, readAlarm = { log += "alarm" }, onOpened = { log += "check" }, readItems = { log += "items" })
        assertEquals(listOf("alarm", "check"), log)
        log.clear()
        afterCredentialReset(paused = false, readAlarm = { log += "alarm" }, onOpened = { log += "check" }, readItems = { log += "items" })
        assertEquals(listOf("alarm", "check", "items"), log)
    }
}
