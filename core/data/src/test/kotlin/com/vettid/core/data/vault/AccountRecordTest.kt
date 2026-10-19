package com.vettid.core.data.vault

import com.vettid.core.vault.AccountSnapshot
import com.vettid.core.vault.NameRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writes the vault manager makes to the account record ([AccountRecord]), against a lock, a reset or a wipe on
 * another thread, taken step by step.
 */
class AccountRecordTest {
    private val old = LocalAccount(userGuid = "u-old", vaultId = "v-old", emailHint = "o***@example.com", setupComplete = true)

    /** The record in memory and on the phone ([disk], null: erased). */
    private class Fixture(initial: LocalAccount) {
        var disk: LocalAccount? = initial
        val record = AccountRecord(initial, readable = true, persist = { disk = it })

        fun wipe() {
            record.beginWipe()
            disk = null
            record.endWipe(complete = true)
        }
    }

    @Test
    fun anOfferAfterAMoveIsShownAndKept() {
        val f = Fixture(old)
        assertTrue(f.record.offerAfterMove(f.record.generation))
        assertTrue(f.record.rotateOffer.value)
        assertTrue(f.disk!!.rotateOffered)
    }

    @Test
    fun anOfferAfterALockIsNotMade() {
        val f = Fixture(old)
        val gen = f.record.generation
        // The member locked after the move's own lock: the generation moved.
        f.record.bumpGeneration()
        assertFalse(f.record.offerAfterMove(gen))
        assertFalse(f.record.rotateOffer.value)
        assertFalse(f.disk!!.rotateOffered)
    }

    @Test
    fun theWipeTakesTheOfferAway() {
        val f = Fixture(old)
        f.record.offerAfterMove(f.record.generation)
        f.wipe()
        assertFalse(f.record.rotateOffer.value)
        assertEquals(LocalAccount(), f.record.value)
    }

    @Test
    fun aResetTakesTheOfferAway() {
        val f = Fixture(old.copy(rotateOffered = true))
        assertTrue(f.record.rotateOffer.value)
        f.record.reset(LocalAccount(userGuid = "u-new"))
        assertFalse(f.record.rotateOffer.value)
        assertEquals(LocalAccount(userGuid = "u-new"), f.disk)
    }

    @Test
    fun anUnlockThatAWipeOvertookWritesNoOpenedTime() {
        val f = Fixture(old)
        val since = f.record.recordGeneration
        f.wipe()
        // The unlock's answer arrives after the wipe: its time is the erased record's.
        assertFalse(f.record.opened(since, atMs = 42))
        assertNull(f.disk)
        assertEquals(LocalAccount(), f.record.value)
    }

    @Test
    fun anUnlockWritesItsOpenedTime() {
        val f = Fixture(old)
        assertTrue(f.record.opened(f.record.recordGeneration, atMs = 42))
        assertEquals(42L, f.disk!!.lastOpenedAtMs)
    }

    @Test
    fun anAccountAnswerThatAWipeOvertookIsNotKept() {
        val f = Fixture(old)
        val since = f.record.recordGeneration
        // `account.get` is on its way when `device.unlinked` wipes the phone.
        f.wipe()
        val u = AccountUpdate(
            AccountSnapshot(asOf = "2026-10-16T00:00:00Z", emailHint = "o***@example.com"),
            NameRequest(seq = 3, firstName = "Old", lastName = "Name", state = "pending"),
        )
        assertFalse(f.record.refreshed(since, u))
        assertNull(f.disk)
        assertEquals(LocalAccount(), f.record.value)
        assertNull(f.record.value.toInfo())
    }

    @Test
    fun anAccountAnswerIsKept() {
        val f = Fixture(old)
        val u = AccountUpdate(AccountSnapshot(asOf = "2026-10-16T00:00:00Z", emailHint = "n***@example.com"), null)
        assertTrue(f.record.refreshed(f.record.recordGeneration, u))
        assertEquals("n***@example.com", f.disk!!.emailHint)
    }

    @Test
    fun theLegacyLockFieldsAreKeptByEveryWrite() {
        val f = Fixture(old.copy(lockAskedAtMs = 7, relayLockUntilMs = 9))
        f.record.update(f.record.recordGeneration) { it.copy(lockAskedAtMs = null, relayLockUntilMs = null, movedIn = true) }
        assertEquals(7L, f.disk!!.lockAskedAtMs)
        assertEquals(9L, f.disk!!.relayLockUntilMs)
        assertTrue(f.disk!!.movedIn)
    }

    @Test
    fun theLockFileInUseClearsTheLegacyFields() {
        val f = Fixture(old.copy(lockAskedAtMs = 7, relayLockUntilMs = 9))
        assertTrue(f.record.markLockFileInUse())
        assertEquals(null, f.disk!!.lockAskedAtMs)
        assertEquals(null, f.disk!!.relayLockUntilMs)
        assertTrue(f.disk!!.lockFileInUse)
        // Kept by every other write, which cannot change it.
        f.record.update(f.record.recordGeneration) { it.copy(lockFileInUse = false, movedIn = true) }
        assertTrue(f.disk!!.lockFileInUse)
    }

    @Test
    fun aResetCarriesNoLegacyLockFieldsButKeepsTheLockFileInUse() {
        val f = Fixture(old.copy(lockAskedAtMs = 7, relayLockUntilMs = 9, lockFileInUse = true))
        f.record.reset(LocalAccount(userGuid = "u-new"))
        assertEquals(LocalAccount(userGuid = "u-new", lockFileInUse = true), f.disk)
        val g = Fixture(old.copy(lockAskedAtMs = 7, relayLockUntilMs = 9))
        g.record.reset(LocalAccount())
        assertEquals(LocalAccount(), g.disk)
    }

    // --- a lost answer to a PIN or password change (VAULT-MESSAGING 0.44.0 §11.14.9) ---

    private val pinChange = UnresolvedChange(com.vettid.core.vault.ChangeTypes.PIN_CHANGE, "01JPIN", 7, 1_000, fromEpoch = 3)

    /** The marker is stored before the send; a second change is refused in the same step (L4); nothing is overwritten. */
    @Test
    fun aChangeMarkerIsStoredOnceAndASecondChangeIsRefused() {
        val f = Fixture(old.copy(phoneEpoch = 3))
        assertEquals(AccountRecord.ChangeStored.STORED, f.record.storeChange(f.record.recordGeneration, pinChange))
        assertEquals(pinChange, f.disk!!.unresolvedChange)
        val other = pinChange.copy(type = com.vettid.core.vault.ChangeTypes.PASSWORD_CHANGE, innerId = "01JPW")
        assertEquals(AccountRecord.ChangeStored.CONFLICT, f.record.storeChange(f.record.recordGeneration, other))
        assertEquals(pinChange, f.disk!!.unresolvedChange)
        assertEquals(pinChange, f.record.value.unresolvedChange)
    }

    /** A marker that cannot be written stops the change, and nothing of it stays in memory. */
    @Test
    fun aChangeMarkerThatCannotBeWrittenLeavesNothing() {
        var fail = false
        val record = AccountRecord(old, readable = true, persist = { if (fail) throw java.io.IOException("disk full") })
        fail = true
        assertEquals(AccountRecord.ChangeStored.FAILED, record.storeChange(record.recordGeneration, pinChange))
        assertNull(record.value.unresolvedChange)
        // After a wipe or reset since the operation began: dropped, also FAILED.
        fail = false
        val since = record.recordGeneration
        record.reset(old)
        assertEquals(AccountRecord.ChangeStored.FAILED, record.storeChange(since, pinChange))
        assertNull(record.value.unresolvedChange)
    }

    /**
     * Applied: the candidate (the next epoch) becomes current and the marker goes, as one write, with the rotate offer;
     * only while the current record is still the one the change was sent from (an unlock may have confirmed it already).
     */
    @Test
    fun anAppliedChangeConfirmsTheCandidateOnce() {
        val f = Fixture(old.copy(phoneEpoch = 3))
        f.record.storeChange(f.record.recordGeneration, pinChange)
        assertTrue(f.record.confirmChange(pinChange))
        assertEquals(4L, f.disk!!.phoneEpoch)
        assertNull(f.disk!!.unresolvedChange)
        assertTrue(f.record.rotateOffer.value)
        assertFalse("no marker: nothing more", f.record.confirmChange(pinChange))
        assertEquals(4L, f.disk!!.phoneEpoch)
        // The unlock already moved to the candidate: the check's confirmation does not move it again.
        val g = Fixture(old.copy(phoneEpoch = 4, unresolvedChange = pinChange))
        assertTrue(g.record.confirmChange(pinChange))
        assertEquals(4L, g.disk!!.phoneEpoch)
        // A password change never moves the epoch.
        val pw = pinChange.copy(type = com.vettid.core.vault.ChangeTypes.PASSWORD_CHANGE)
        val h = Fixture(old.copy(phoneEpoch = 3, unresolvedChange = pw))
        assertTrue(h.record.confirmChange(pw))
        assertEquals(3L, h.disk!!.phoneEpoch)
    }

    /** Not applied, refused or ended: the marker goes and the epoch stays; another change's marker is left alone. */
    @Test
    fun aChangeThatDidNotApplyClearsOnlyItsMarker() {
        val f = Fixture(old.copy(phoneEpoch = 3, unresolvedChange = pinChange))
        assertFalse(f.record.clearChange(pinChange.copy(innerId = "01JOTHER")))
        assertEquals(pinChange, f.disk!!.unresolvedChange)
        assertTrue(f.record.clearChange(pinChange))
        assertNull(f.disk!!.unresolvedChange)
        assertEquals(3L, f.disk!!.phoneEpoch)
    }

    /**
     * (0.45.0 step 3, 5) A PIN change's check that ended leaves the PIN uncertain (a password change's does not); an
     * unlock settles it: `ok` with the current record keeps the epoch, a confirmed candidate moves to it. Either way an
     * unconfirmed renew is settled too.
     */
    @Test
    fun anEndedPinChangeLeavesThePinUncertainUntilAnUnlock() {
        val renew = UnconfirmedRenew("01JRENEW", 6, 1_000, 3)
        val f = Fixture(old.copy(phoneEpoch = 3, unresolvedChange = pinChange, unconfirmedRenew = renew))
        assertTrue(f.record.endChange(pinChange))
        assertNull(f.disk!!.unresolvedChange)
        assertEquals(3L, f.disk!!.uncertainPinFromEpoch)
        assertTrue(f.record.settlePin(f.record.recordGeneration))
        assertNull(f.disk!!.uncertainPinFromEpoch)
        assertNull(f.disk!!.unconfirmedRenew)
        assertEquals(3L, f.disk!!.phoneEpoch)
        val g = Fixture(old.copy(phoneEpoch = 3, uncertainPinFromEpoch = 3))
        assertTrue(g.record.settlePin(g.record.recordGeneration, epoch = 4))
        assertEquals(4L, g.disk!!.phoneEpoch)
        assertNull(g.disk!!.uncertainPinFromEpoch)
        val pw = pinChange.copy(type = com.vettid.core.vault.ChangeTypes.PASSWORD_CHANGE)
        val h = Fixture(old.copy(phoneEpoch = 3, unresolvedChange = pw))
        assertTrue(h.record.endChange(pw))
        assertNull(h.disk!!.uncertainPinFromEpoch)
    }

    /**
     * (0.45.0 step 5, N1) A renew is stored before it is sent; its answer or the version check decides it: applied moves
     * the phone to its candidate (only from the epoch it was sent from), not applied leaves the epoch.
     */
    @Test
    fun aRenewIsStoredBeforeTheSendAndDecidedOnce() {
        val f = Fixture(old.copy(phoneEpoch = 3))
        val r = UnconfirmedRenew("01JRENEW", 6, 1_000, 3)
        assertTrue(f.record.storeRenew(f.record.recordGeneration, r))
        assertEquals(r, f.disk!!.unconfirmedRenew)
        assertTrue(f.record.value.renewUnconfirmed)
        assertFalse("another renew's answer", f.record.decideRenew("01JOTHER", applied = true))
        assertTrue(f.record.decideRenew(r.innerId, applied = true))
        assertEquals(4L, f.disk!!.phoneEpoch)
        assertNull(f.disk!!.unconfirmedRenew)
        val g = Fixture(old.copy(phoneEpoch = 3, unconfirmedRenew = r))
        assertTrue(g.record.decideRenew(r.innerId, applied = false))
        assertEquals(3L, g.disk!!.phoneEpoch)
        var fail = false
        val h = AccountRecord(old, readable = true, persist = { if (fail) throw java.io.IOException("disk full") })
        fail = true
        assertFalse("not stored: nothing may be sent", h.storeRenew(h.recordGeneration, r))
    }

    /** (N5) A decision the member has not seen is kept on the phone until it is seen. */
    @Test
    fun aDecisionIsKeptUntilSeen() {
        val f = Fixture(old)
        val d = SavedDecision.of(ChangeCheck.Refused(ChangeKind.PIN, FailureKind.BAD_PIN))!!
        assertTrue(f.record.saveDecision(d))
        assertEquals(ChangeCheck.Refused(ChangeKind.PIN, FailureKind.BAD_PIN), f.disk!!.changeDecision?.toCheck())
        assertTrue(f.record.saveDecision(null))
        assertNull(f.disk!!.changeDecision)
        assertNull(SavedDecision.of(ChangeCheck.Checking(ChangeKind.PIN)))
    }

    /** (P4) Whether the kept decision was notified survives the same decision written again. */
    @Test
    fun aNotifiedDecisionStaysNotified() {
        val f = Fixture(old)
        val d = SavedDecision.of(ChangeCheck.Applied(ChangeKind.PIN))!!
        f.record.saveDecision(d)
        assertTrue(f.record.decisionNotified())
        f.record.saveDecision(d)
        assertTrue(f.disk!!.changeDecision!!.notified)
        f.record.saveDecision(SavedDecision.of(ChangeCheck.NotApplied(ChangeKind.PIN)))
        assertFalse(f.disk!!.changeDecision!!.notified)
    }
}
