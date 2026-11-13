package com.vettid.core.data.vault

import org.robolectric.RuntimeEnvironment
import com.vettid.core.data.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * VAULT-MESSAGING 0.88.17 §11.4: the unlock's `state_unreadable` is its own failure with the owner's text, never a PIN
 * failure; the other sealed refusals keep their attempts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StateUnreadableTest {
    @Test
    fun theUnlocksStateUnreadableIsItsOwnFailure() {
        assertEquals(
            UnlockAttempt.Failed(FailureKind.STATE_UNREADABLE, "state_unreadable"),
            sealedUnlockRefusal("state_unreadable", 0),
        )
    }

    @Test
    fun theOtherSealedRefusalsAreAsBefore() {
        assertEquals(UnlockAttempt.BadPin(30), sealedUnlockRefusal("bad_pin", 30))
        assertEquals(UnlockAttempt.Backoff(60), sealedUnlockRefusal("backoff", 60))
        assertEquals(UnlockAttempt.RecoveryPending, sealedUnlockRefusal("recovery_pending", 0))
        assertEquals(UnlockAttempt.SignerRefused, sealedUnlockRefusal(SignerWire.CODE_SIGNER_REFUSED, 0))
        assertEquals(UnlockAttempt.StateRollback, sealedUnlockRefusal("state_rollback", 0))
        assertEquals(UnlockAttempt.Failed(FailureKind.ATTESTATION, "attestation"), sealedUnlockRefusal("attestation", 0))
        assertEquals(UnlockAttempt.Failed(FailureKind.MANIFEST, "manifest"), sealedUnlockRefusal("manifest", 0))
        assertEquals(
            UnlockAttempt.Failed(FailureKind.TRANSFER_INCOMPLETE, "transfer_incomplete"),
            sealedUnlockRefusal("transfer_incomplete", 0),
        )
        assertEquals(UnlockAttempt.Failed(FailureKind.RETRY, "retry"), sealedUnlockRefusal("retry", 0))
        assertEquals(UnlockAttempt.Failed(FailureKind.OTHER, "state_unsupported"), sealedUnlockRefusal("state_unsupported", 0))
        assertEquals(UnlockAttempt.Failed(FailureKind.OTHER, null), sealedUnlockRefusal(null, 0))
    }

    @Test
    fun itsTextIsTheApprovedOne() {
        val ctx = RuntimeEnvironment.getApplication()
        assertEquals(R.string.data_failure_state_unreadable, FailureKind.STATE_UNREADABLE.messageRes())
        assertEquals(
            "Your vault can't be opened right now. Nothing in it has been changed. Please try again later.",
            ctx.getString(FailureKind.STATE_UNREADABLE.messageRes()),
        )
    }
}
