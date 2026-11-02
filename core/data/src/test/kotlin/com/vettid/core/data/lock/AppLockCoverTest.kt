package com.vettid.core.data.lock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which app-lock states put the cover up (it decides whether notifications show names). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockCoverTest {
    @Test
    fun onlyLockedAndPendingCover() {
        assertEquals(true, AppLockState.LOCKED.coversApp())
        assertEquals(true, AppLockState.PENDING.coversApp())
        assertEquals(false, AppLockState.UNLOCKED.coversApp())
        assertEquals(false, AppLockState.DISABLED.coversApp())
    }

    @Test
    fun theFlowStartsCoveredAndFollowsTheState() = runTest {
        val state = MutableStateFlow(AppLockState.DISABLED)
        val covered = state.coverFlow(backgroundScope)
        assertEquals("covered until the state is read", true, covered.value)
        runCurrent()
        assertEquals(false, covered.value)
        for ((s, c) in listOf(AppLockState.LOCKED to true, AppLockState.UNLOCKED to false, AppLockState.PENDING to true)) {
            state.value = s
            runCurrent()
            assertEquals(s.name, c, covered.value)
        }
    }
}
