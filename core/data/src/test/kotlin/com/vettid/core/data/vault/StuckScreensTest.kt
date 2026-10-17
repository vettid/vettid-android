package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Erase-or-recover beside "Try again" once nothing has reached the vault service for 7 days. */
class StuckScreensTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    @Test
    fun aVaultUnreachableForSevenDaysOffersAWayOut() {
        assertTrue(StuckScreens.longUnreachable(FailureKind.NETWORK, now - 7 * day, now))
        assertFalse(StuckScreens.longUnreachable(FailureKind.NETWORK, now - 7 * day + 1, now))
    }

    @Test
    fun noLastContactKnownOffersNothing() {
        assertFalse(StuckScreens.longUnreachable(FailureKind.NETWORK, null, now))
    }

    @Test
    fun failuresWithTheirOwnWayOrNoneAreNotChanged() {
        val own = setOf(
            FailureKind.CLOCK, FailureKind.NO_VAULT, FailureKind.LOCAL_STATE, FailureKind.ERASE_FAILED, FailureKind.UNAUTHORIZED,
        )
        for (k in FailureKind.entries) assertEquals(k.name, k !in own, StuckScreens.longUnreachable(k, now - 30 * day, now))
    }

    @Test
    fun aContactIsWrittenAtMostHourly() {
        val hour = 60 * 60 * 1000L
        assertTrue(StuckScreens.writeContact(null, now))
        assertFalse(StuckScreens.writeContact(now - hour + 1, now))
        assertTrue(StuckScreens.writeContact(now - hour, now))
        assertTrue("a clock set back", StuckScreens.writeContact(now + hour, now))
    }

    @Test
    fun aContactIsKeptForTheRecordItBelongsTo() {
        var disk: LocalAccount? = null
        val r = AccountRecord(LocalAccount(userGuid = "u"), readable = true, persist = { disk = it })
        val since = r.recordGeneration
        assertTrue(r.contacted(since, now))
        assertEquals(now, disk?.lastContactAtMs)
        r.beginWipe()
        r.endWipe(complete = true)
        assertFalse("not into the record after a wipe", r.contacted(since, now + 2 * day))
    }
}
