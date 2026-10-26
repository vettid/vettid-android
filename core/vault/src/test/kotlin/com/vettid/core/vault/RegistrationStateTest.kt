package com.vettid.core.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ANDROID-PLAN 0.1.80: the handshake's ctx is the recovery_id an identity LAST registered for. */
class RegistrationStateTest {
    private val r2 = RecoveryRecord("R2", "q2")
    private val r3 = RecoveryRecord("R3", "q3")

    @Test
    fun aFailedReRegisterRestoresTheLastAcceptedRegistration() {
        val accepted = RegistrationState(r2, null)
        val trying = accepted.begin(r3)
        assertEquals("R3", trying.recovery?.recoveryId)
        assertEquals("R2", trying.drop().recovery?.recoveryId)
        assertNull(trying.drop().prior)
    }

    @Test
    fun anAcceptedReRegisterIsTheNewOne() {
        val done = RegistrationState(r2, null).begin(r3).confirm()
        assertEquals("R3", done.recovery?.recoveryId)
        assertNull(done.prior)
    }

    @Test
    fun aFirstRegistrationThatFailsLeavesNone() {
        assertNull(RegistrationState(null, null).begin(r2).drop().recovery)
    }

    @Test
    fun twoUndecidedAttemptsStillRestoreTheLastAcceptedOne() {
        val twice = RegistrationState(r2, null).begin(r3).begin(RecoveryRecord("R4", "q4"))
        assertEquals("R2", twice.drop().recovery?.recoveryId)
    }
}
