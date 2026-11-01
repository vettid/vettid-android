package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/** [UnlockEnd] (#244 review B3): an unlock whose vault call succeeded but that opened nothing is never Success. */
class UnlockEndTest {
    @Test
    fun nothingOpenedIsNeverSuccess() {
        assertEquals(
            UnlockAttempt.Failed(FailureKind.OTHER, MoveRepository.CODE_LOCK_PENDING),
            UnlockEnd.attempt(opened = false, lockPending = true, refusedUpdate = null),
        )
        assertEquals(
            UnlockAttempt.Failed(FailureKind.OTHER, MoveRepository.CODE_NOT_OPENED),
            UnlockEnd.attempt(opened = false, lockPending = false, refusedUpdate = null),
        )
        // Not even with an update's answer: nothing opened comes first.
        assertEquals(
            UnlockAttempt.Failed(FailureKind.OTHER, MoveRepository.CODE_NOT_OPENED),
            UnlockEnd.attempt(opened = false, lockPending = false, refusedUpdate = "refused"),
        )
    }

    @Test
    fun anOpenedUnlockIsSuccessOrTheUpdatesRefusal() {
        assertEquals(UnlockAttempt.Success, UnlockEnd.attempt(opened = true, lockPending = false, refusedUpdate = null))
        assertEquals(
            UnlockAttempt.UpdateRefused("refused"),
            UnlockEnd.attempt(opened = true, lockPending = false, refusedUpdate = "refused"),
        )
    }
}
