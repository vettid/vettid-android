package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * (#189 recheck, A6 audit N1) [VaultManager] has no unit test harness, so its call site is read from the source: the
 * transfer-in wait goes through [TransferInResume.awaitApproval] with the device's stored transfer (its start, taken
 * just before the hs.init), and nothing in that call changes the start or keeps another one.
 */
class TransferInDeadlineCallTest {
    private val code: List<String> = File("src/main/kotlin/com/vettid/core/data/vault/VaultManager.kt").readLines()
        .map { it.substringBefore("//").trim() }
        .filterNot { it.startsWith("*") || it.startsWith("/*") }

    private fun body(signature: String): List<String> {
        val i = code.indexOfFirst { it.startsWith(signature) }
        assertTrue("found: $signature", i >= 0)
        val end = (i + 1 until code.size).first { code[it].startsWith("override ") || code[it].startsWith("private ") }
        return code.subList(i, end)
    }

    @Test
    fun theWaitCountsFromTheStoredStartOfTheHsInit() {
        val b = body("override suspend fun awaitTransferIn()")
        assertEquals(1, b.count { it.contains("TransferInResume.awaitApproval(") })
        assertTrue(b.any { it == "pending = { s.device.transferInPending }," })
        assertTrue(b.any { it == "now = Instant::now," })
        assertTrue(b.any { it == "atDeadline = { atTransferDeadline(s) }," })
        assertFalse("no start of its own", b.any { Regex("""\b(minus|plus)\w*\(""").containsMatchIn(it) })
        assertFalse("no in-memory start", code.any { it.contains("transferStartedAt") })
    }

    @Test
    fun aStartPastTheDeadlineShowsTheWaitAndSettlesInTheBackground() {
        val b = body("private suspend fun evaluate()")
        assertTrue(b.any { it.contains("TransferInResume.decide(pendingIn, d.deviceId, Instant.now())") })
        assertTrue(b.any { it.contains("TransferInResume.Action.WAIT, TransferInResume.Action.DEADLINE -> return@guard") })
        assertFalse("never blocks the start for the window", b.any { it.contains("atTransferDeadline(") })
    }

    /** (VAULT-MESSAGING 0.84.2) No abandon and no new code while the settlement window runs. */
    @Test
    fun noAbandonAndNoNewCodeDuringTheWindow() {
        val guard = "if (settlements.active) throw VaultFailure(FailureKind.BUSY, MoveRepository.CODE_TRANSFER_SETTLING)"
        assertTrue(body("override suspend fun transferIn(code: String): String {").any { it == guard })
        assertTrue(body("override suspend fun abandonTransferIn()").any { it == guard })
    }

    /**
     * (#187 recheck) A start past the deadline does its collect before the collection can lease the waiting device.paired;
     * the deadline waits for a round in flight; and a paired transfer never reaches credential.create.
     */
    @Test
    fun theDeadlineCannotMissALeasedDevicePaired() {
        val deferred = "if (TransferInResume.decide(device.transferInPending, device.deviceId, Instant.now()) !="
        val start = code.indexOfFirst { it.startsWith(deferred) }
        assertTrue("the collection is deferred at a start past the deadline", start >= 0)
        assertEquals("device.start(scope)", code[start + 1])
        val dl = body("private suspend fun atTransferDeadline(")
        assertTrue("single-flight in the manager's scope", dl.any { it.startsWith("return settlements.run(") })
        val wiring = "settleTransfer(DeviceSettle(d), { session?.device === d }, transferDeadline)"
        assertTrue("the wiring is the tested unit", dl.any { it.contains(wiring) })
        val gone = "Settled.SessionGone -> throw VaultFailure(FailureKind.RETRY"
        assertTrue("a session that went away is no drop", dl.any { it.contains(gone) })
        assertTrue(dl.any { it.contains("d.start(scope)") })
        val after = body("private suspend fun afterUnlocked(")
        val take = after.indexOfFirst { it.startsWith("s.device.transferInPending?.takeIf { it.paired }") }
        val create = after.indexOfFirst { it == "return AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)" }
        assertTrue("a paired transfer is taken over before NEEDS_CREDENTIAL", take in 0 until create)
    }
}
