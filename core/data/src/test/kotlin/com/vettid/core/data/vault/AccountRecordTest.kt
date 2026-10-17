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
            record.endWipe()
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
    fun theLocksPartIsKeptByEveryOtherWrite() {
        val f = Fixture(old)
        f.record.saveLock(pendingSinceMs = 7, relayLockUntilMs = 9)
        f.record.update(f.record.recordGeneration) { it.copy(lockAskedAtMs = null, relayLockUntilMs = null, movedIn = true) }
        assertEquals(7L, f.disk!!.lockAskedAtMs)
        assertEquals(9L, f.disk!!.relayLockUntilMs)
        assertTrue(f.disk!!.movedIn)
    }
}
