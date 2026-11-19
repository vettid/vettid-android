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
        assertTrue(b.any { it.contains("TransferInResume.Action.WAIT -> return@guard AppPhase.Setup(SetupStage.TRANSFER_WAITING)") })
        assertTrue(b.any { it == "startSettlement(s)" })
        assertFalse("never blocks the start for the window", b.any { it.contains("atTransferDeadline(") })
    }

    /** (VAULT-MESSAGING 0.85.2) No abandon and no new code while the settlement window runs. */
    @Test
    fun noAbandonAndNoNewCodeDuringTheWindow() {
        val guard = "if (settlements.active) throw VaultFailure(FailureKind.BUSY, MoveRepository.CODE_TRANSFER_SETTLING)"
        assertTrue(body("override suspend fun transferIn(code: String): String {").any { it == guard })
        val abandon = body("override suspend fun abandonTransferIn()")
        assertTrue(abandon.any { it.contains("settlements.active || TransferInResume.pastVaultWindow(") })
        assertTrue(abandon.any { it.contains("MoveRepository.CODE_TRANSFER_SETTLING") })
    }

    /**
     * (the recheck of #302) Nothing forgets an unpaired device that holds an unsettled transfer: not a setup-code redeem,
     * not a recovery, not a new code (all through freshDeviceIfUnpaired), not forgetSetupCode. And a start past the
     * deadline starts the settlement itself.
     */
    @Test
    fun theSetupLinkPathsAreGuardedAndAStartPastTheDeadlineSettlesOnItsOwn() {
        val guard = "if (transferPinned(d)) throw VaultFailure(FailureKind.BUSY, MoveRepository.CODE_TRANSFER_SETTLING)"
        assertTrue(body("private suspend fun freshDeviceIfUnpaired()").any { it == guard })
        val forget = "if (session?.device?.let(::transferPinned) == true) throw VaultFailure(FailureKind.BUSY, " +
            "MoveRepository.CODE_TRANSFER_SETTLING)"
        assertTrue(body("override suspend fun forgetSetupCode()").any { it == forget })
        val pinned = body("private fun transferPinned(")
        assertTrue(pinned.any { it.contains("settlements.active") && it.contains("TransferInResume.deadlineDue(") })
        val ev = body("private suspend fun evaluate()")
        val at = ev.indexOfFirst { it == "TransferInResume.Action.DEADLINE -> {" }
        assertTrue("the start settles at once", at >= 0 && ev[at + 1] == "startSettlement(s)")
        val start = body("private fun startSettlement(")
        assertTrue("every outcome: the phase is read again", start.any { it == "refresh()" })
        assertFalse("not only when paired", start.any { it.contains("paired != null") })
        val wait = body("override suspend fun awaitTransferIn()")
        val done = "return@guard completeTransferIn(s, rec, guid)"
        assertTrue("completed in the background: the completed result", wait.any { it.startsWith(done) })
        // Never without the member's user_guid (VAULT-MESSAGING §6.7.1): a failed transfer.
        val noGuid = "val guid = TransferInResume.usableGuid(local.userGuid) ?: throw TransferWithoutUserGuidException()"
        assertTrue("completed without a user_guid: failed", wait.any { it == noGuid })
        assertTrue("a transfer with nothing to wait for is not waited for", body("override suspend fun awaitTransferIn()")
            .any { it.contains("MoveRepository.CODE_TRANSFER_SESSION_ENDED") })
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
        // A paired transfer is taken over, never created (TransferInResume.withoutCredential, tested on its own).
        val take = after.indexOfFirst { it.contains("TransferInResume.withoutCredential(s.device.transferInPending)") }
        val needs = "TransferInResume.NoCredential.NeedsCredential -> AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)"
        val create = after.indexOfFirst { it == needs }
        assertTrue("a paired transfer is taken over before NEEDS_CREDENTIAL", take in 0 until create)
        assertTrue(after.none { it == "return AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)" })
    }

    /**
     * (VAULT-MESSAGING §6.7.1, §11.4) A phone without the member's user_guid fails closed: the unlock refuses before
     * anything is asked or sent (`no_user_guid`; a damaged record, or a transfer an older app build completed without
     * one), a paired transfer stored without one is never completed (at the start or after an unlock), and a transfer's
     * wait never completes one either.
     */
    @Test
    fun aPhoneWithoutAUserGuidFailsClosed() {
        val unlock = body("private suspend fun unlockStepHeld(")
        val at = unlock.indexOf("if (!guidUsable()) {")
        assertTrue("the unlock's guard is kept", at >= 0)
        assertEquals(
            "return StepOutcome(UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID))",
            unlock[at + 1],
        )
        val opens = unlock.indexOf("): StepOutcome {")
        assertEquals(
            "only the owner check's counters come before it",
            listOf("ownerCheck.beginUnlock()", "ownerCheck.unlockStarting()"),
            unlock.subList(opens + 1, at).filter { it.isNotEmpty() },
        )
        assertTrue("before the unlock is sent", at < unlock.indexOfFirst { it.contains("unlockWithSecret(") })
        assertEquals("no_user_guid", UnlockAttempt.CODE_NO_USER_GUID)
        // One check for the unlock's guard and the two flows that lock before their unlock ("Finish the move", the
        // release update): each is handed that check, so neither can lock a vault this phone can never open again.
        assertEquals(
            listOf("private fun guidUsable(): Boolean = TransferInResume.usableGuid(local.userGuid) != null"),
            code.filter { it.contains("fun guidUsable(") },
        )
        assertEquals(listOf("userGuidUsable = { guidUsable() },"), code.filter { it.startsWith("userGuidUsable =") })
        assertEquals(listOf("guidUsable = { guidUsable() },"), code.filter { it.startsWith("guidUsable =") })
        // The unlock screen reads the same check when it opens (recover-or-erase at once, no PIN first).
        assertTrue(code.any { it == "override val userGuidUsable: Boolean get() = guidUsable()" })
        // "Lock and unlock now" (Settings and the open app's banner) goes through the same check before its lock.
        val settle = body("override suspend fun lockToSettlePin()")
        assertEquals(
            listOf("if (!guidUsable()) return false", "lock()", "return true"),
            settle.drop(1).filter { it.isNotEmpty() && it != "}" },
        )
        val update = code.indexOfFirst { it.startsWith("val releaseUpdate = ReleaseUpdateManager(") }
        assertTrue(code.subList(update, update + 4).contains("userGuidUsable = { guidUsable() },"))
        val finish = code.indexOfFirst { it.startsWith("private val finishMoveRunner = FinishMoveRunner(") }
        assertTrue(code.subList(finish, finish + 5).contains("guidUsable = { guidUsable() },"))
        val ev = body("private suspend fun evaluate()")
        assertTrue(ev.any { it == "TransferInResume.Action.NO_USER_GUID -> Unit" })
        val completes = "completeTransferIn(s, record.recordGeneration, checkNotNull(TransferInResume.completingGuid(pendingIn)))"
        assertTrue(ev.any { it == completes })
        val after = body("private suspend fun afterUnlocked(")
        assertTrue(after.any { it == "return when (val step = TransferInResume.withoutCredential(s.device.transferInPending)) {" })
        assertTrue(after.any { it == "completeTransferIn(s, record.recordGeneration, step.userGuid)" })
        assertTrue("never completed without a user_guid", after.any { it == "TransferInResume.NoCredential.Locked -> AppPhase.Locked" })
        assertEquals("completed in one place", 1, after.count { it.startsWith("completeTransferIn(") })
        // The settlement reads the device's own mark (a drop for the user_guid ends as that failure, never as "dropped").
        assertTrue(code.any { it == "override val droppedNoUserGuid get() = d.droppedNoUserGuid" })
        val complete = body("private suspend fun completeTransferIn(")
        assertTrue(complete.first().contains("guid: String)"))
        assertFalse(complete.any { it.contains("guid != null") || it.contains("String?") })
    }

    /**
     * (§6.7.1) The untold drop for the user_guid outlives a restart: the start shows the transfer screen for it, before
     * any other setup stage, and the transfer screen's wait clears it whenever it says the move stopped.
     */
    @Test
    fun anUntoldStoppedMoveIsShownAtTheStartAndClearedWhenTold() {
        val ev = body("private suspend fun evaluate()")
        val tells = ev.indexOf("val untold = StoppedMove.tells(d.deviceId, d.transferInPending, d.droppedNoUserGuid)")
        assertTrue(tells >= 0)
        assertEquals("if (untold) return@guard AppPhase.Setup(SetupStage.TRANSFER_WAITING)", ev[tells + 1])
        assertTrue("before the stages of a phone without a vault", tells < ev.indexOf("if (d.deviceId == null) {"))
        val wait = body("override suspend fun awaitTransferIn()")
        assertEquals("told on both ways the screen says it", 2, wait.count { it == "tellNoUserGuidDrop(s)" })
        val told = wait.indexOf("} catch (e: TransferWithoutUserGuidException) {")
        assertEquals("tellNoUserGuidDrop(s)", wait[told + 1])
        assertTrue(body("private suspend fun tellNoUserGuidDrop(").any { it == "s.device.takeNoUserGuidDrop()" })
        val take = "override suspend fun takeTransferStopped(): Boolean = guard { session().device.takeNoUserGuidDrop() }"
        assertTrue(code.any { it == take })
    }
}
