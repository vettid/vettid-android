package com.vettid.core.data.vault

import com.vettid.core.data.vault.TransferInResume.Action
import com.vettid.core.vault.TransferInState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * (A6 audit N1; VAULT-MESSAGING 0.56.2 §11.14.10 rule 5, ANDROID-PLAN 0.1.76) A start after a kill at each step of a
 * transfer to this phone, and the one 11-minute deadline with its collect before the drop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferInResumeTest {
    private val at = Instant.parse("2026-10-23T10:00:00Z")
    private val shown = TransferInState(at, "042817", paired = false, userGuid = null)
    private val paired = TransferInState(at, "042817", paired = true, userGuid = "m-1")

    @Test
    fun aKillAtEachStepResumesFromThatStep() {
        val before = at.plusSeconds(60)
        // Nothing started.
        assertEquals(Action.NONE, TransferInResume.decide(null, null, before))
        // Killed after hs.init, before hs.resp: no code, no hs.fin sent: dropped.
        assertEquals(Action.DROP, TransferInResume.decide(TransferInState(at, null, paired = false, userGuid = null), null, before))
        // Killed after the code was shown (hs.fin may have reached the vault), started before the deadline: waiting.
        assertEquals(Action.WAIT, TransferInResume.decide(shown, null, before))
        // Killed after device.paired was applied, before the credential was taken over: completed.
        assertEquals(Action.COMPLETE, TransferInResume.decide(paired, "dev-2", before))
        // A vault release before 0.17.0 sends no user_guid: completed all the same.
        assertEquals(Action.COMPLETE, TransferInResume.decide(paired.copy(userGuid = null), "dev-2", before))
    }

    /** One deadline, the stored start of the hs.init plus 11 minutes; a paired transfer completes at any age. */
    @Test
    fun theDeadlineIsTheHsInitPlusElevenMinutes() {
        val deadline = at.plus(Duration.ofMinutes(11))
        assertEquals(Action.WAIT, TransferInResume.decide(shown, null, deadline.minusMillis(1)))
        // Killed before the deadline, started after it: the collect and the drop at that start.
        assertEquals(Action.DEADLINE, TransferInResume.decide(shown, null, deadline))
        assertEquals(Action.DEADLINE, TransferInResume.decide(shown, null, deadline.plus(Duration.ofDays(3))))
        assertEquals(Action.COMPLETE, TransferInResume.decide(paired, "dev-2", deadline.plus(Duration.ofDays(30))))
        assertEquals(Action.DROP, TransferInResume.decide(TransferInState(at, null, paired = false, userGuid = null), null, deadline))
    }

    /** At the deadline a device.paired waiting on the relay is applied by the one collect: completed, not dropped. */
    @Test
    fun aWaitingDevicePairedIsAppliedAtTheDeadline() = runTest {
        var isPaired = false
        var collects = 0
        var dropped = false
        val r = TransferInResume.atDeadline({ isPaired }, { collects++; isPaired = true }, { dropped = true })
        assertTrue(r)
        assertEquals(1, collects)
        assertFalse(dropped)
    }

    @Test
    fun nothingWaitingIsDropped() = runTest {
        var collects = 0
        var dropped = false
        assertFalse(TransferInResume.atDeadline({ false }, { collects++ }, { dropped = true }))
        assertEquals("one collect, no more", 1, collects)
        assertTrue(dropped)
    }

    @Test
    fun aFailedCollectIsDropped() = runTest {
        var dropped = false
        assertFalse(TransferInResume.atDeadline({ false }, { throw IOException("relay unreachable") }, { dropped = true }))
        assertTrue(dropped)
    }

    /** A collect that hangs is bounded (30 s in all); then dropped. */
    @Test
    fun aCollectThatHangsIsBoundedThenDropped() = runTest {
        var dropped = false
        val start = testScheduler.currentTime
        assertFalse(TransferInResume.atDeadline({ false }, { CompletableDeferred<Unit>().await() }, { dropped = true }))
        assertTrue(dropped)
        assertEquals(TransferInResume.DEADLINE_COLLECT_MS, testScheduler.currentTime - start)
    }

    @Test
    fun anAlreadyPairedTransferIsNotCollectedForOrDropped() = runTest {
        var collects = 0
        assertTrue(TransferInResume.atDeadline({ true }, { collects++ }, { error("dropped") }))
        assertEquals(0, collects)
    }

    /**
     * (#189 recheck) The wait counts from the start the device stored just before the hs.init (kept across restarts), not
     * from when this call began: 5 minutes after that start, 6 minutes are left.
     */
    @Test
    fun theWaitCountsFromTheStoredStart() = runTest {
        val now = at.plus(Duration.ofMinutes(5))
        var asked: Duration? = null
        val r = TransferInResume.awaitApproval({ shown }, { now }, { left -> asked = left; "m-1" }) { error("no deadline") }
        assertEquals(Duration.ofMinutes(6), asked)
        assertEquals(TransferInResume.Approved("m-1"), r)
        // Nothing stored: the whole 11 minutes.
        TransferInResume.awaitApproval({ null }, { now }, { left -> asked = left; null }) { error("no deadline") }
        assertEquals(Duration.ofMinutes(11), asked)
    }

    /** The wait ran out: the deadline's collect decides, applied (paired) or dropped (null). */
    @Test
    fun theWaitRunningOutGoesToTheDeadline() = runTest {
        val late = at.plus(Duration.ofMinutes(12))
        var asked: Duration? = null
        val timeout: suspend (Duration) -> String? = { left ->
            asked = left
            kotlinx.coroutines.withTimeout(1) { CompletableDeferred<String?>().await() }
        }
        assertEquals(TransferInResume.Approved("m-1"), TransferInResume.awaitApproval({ shown }, { late }, timeout) { paired })
        assertEquals("past the deadline: the least wait", Duration.ofSeconds(1), asked)
        assertEquals(null, TransferInResume.awaitApproval({ shown }, { late }, timeout) { null })
    }
}
