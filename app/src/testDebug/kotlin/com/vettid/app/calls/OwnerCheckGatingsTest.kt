package com.vettid.app.calls

import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Owner decision a, 2026-11-17: no ring while the daily owner check is due or held. The calls learn it from the owner
 * check's view as it changes and when its deadline passes with no new view (the vault says nothing then).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerCheckGatingsTest {
    private val t0 = Instant.parse("2026-11-17T10:00:00Z")

    private fun view(state: OwnerCheckState, deadline: Instant?) =
        OwnerCheckView(state, deadline, intervalSeconds = 86_400, failures = 0, hold = true, holdOffUntil = null)

    @Test
    fun gatedAtTheDeadlineAndByTheVaultsWord() = runTest {
        val views = MutableStateFlow<OwnerCheckView?>(view(OwnerCheckState.OK, t0.plusSeconds(60)))
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { ownerCheckGatings(views) { t0.plusMillis(testScheduler.currentTime) }.collect { seen += it } }
        runCurrent()
        assertEquals(listOf(false), seen)
        advanceTimeBy(59_000)
        assertEquals("not before the deadline", listOf(false), seen)
        advanceTimeBy(1_001)
        assertEquals("the deadline passed", listOf(false, true), seen)
        views.value = view(OwnerCheckState.OK, t0.plusSeconds(86_400)) // the check was done
        runCurrent()
        assertEquals(listOf(false, true, false), seen)
        views.value = view(OwnerCheckState.HELD, t0.plusSeconds(86_400)) // the vault says it is held
        runCurrent()
        assertEquals(listOf(false, true, false, true), seen)
        views.value = null
        runCurrent()
        assertEquals("no view: not gated", listOf(false, true, false, true, false), seen)
    }
}
