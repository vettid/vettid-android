package com.vettid.core.data.lock

import android.hardware.biometrics.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One app-lock prompt at a time (owner report 2026-10-09: a second prompt followed the first). */
class UnlockPromptGateTest {
    private val activity = Any()

    @Test
    fun onePromptAcrossStopStartAndRecomposition() {
        val g = UnlockPromptGate()
        assertTrue(g.tryStart(auto = true, owner = activity))
        // The lock screen composed again, the app came back (onStart/onResume), "Unlock" pressed: no second prompt.
        assertFalse(g.tryStart(auto = true, owner = activity))
        assertFalse(g.tryStart(auto = true, owner = activity))
        assertFalse(g.tryStart(auto = false, owner = activity))
        assertTrue(g.prompting)
    }

    @Test
    fun theDeviceCredentialFallbackUnlocksWithoutASecondPrompt() {
        val g = UnlockPromptGate()
        assertTrue(g.tryStart(auto = true, owner = activity))
        // The phone's PIN in the prompt's fallback: a success like a fingerprint.
        g.finished(PromptOutcome.SUCCEEDED)
        assertFalse(g.prompting)
        // Unlocked: nothing asks again until the next lock, whose first prompt is automatic.
        g.newLock()
        assertTrue(g.tryStart(auto = true, owner = activity))
    }

    @Test
    fun aCancelledPromptIsNotRelaunchedByItself() {
        for (code in listOf(BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED)) {
            val g = UnlockPromptGate()
            assertTrue(g.tryStart(auto = true, owner = activity))
            g.finished(PromptOutcome.ofError(code))
            assertFalse(g.tryStart(auto = true, owner = activity)) // a return to the app: the "Unlock" button instead
            assertTrue(g.tryStart(auto = false, owner = activity)) // the member pressed "Unlock"
        }
    }

    @Test
    fun errorCodes() {
        assertEquals(PromptOutcome.SYSTEM_CANCELED, PromptOutcome.ofError(BiometricPrompt.BIOMETRIC_ERROR_CANCELED))
        assertEquals(PromptOutcome.DISMISSED, PromptOutcome.ofError(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED))
        assertEquals(PromptOutcome.DISMISSED, PromptOutcome.ofError(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT))
    }

    @Test
    fun aDestroyedActivityReleasesItsPromptOnly() {
        val g = UnlockPromptGate()
        val other = Any()
        assertTrue(g.tryStart(auto = true, owner = activity))
        assertFalse(g.ownerGone(other))
        assertTrue(g.prompting)
        assertTrue(g.ownerGone(activity))
        assertTrue(g.tryStart(auto = true, owner = other))
    }

    @Test
    fun aNewLockPromptsByItselfAgain() {
        val g = UnlockPromptGate()
        g.tryStart(auto = true, owner = activity)
        g.finished(PromptOutcome.DISMISSED)
        g.newLock()
        assertTrue(g.tryStart(auto = true, owner = activity))
    }
}
