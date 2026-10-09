// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.notify

import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.SetupStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mode state machine (ANDROID-PLAN 0.1.23, Notification modes 1 and 11). */
@OptIn(ExperimentalCoroutinesApi::class)
class ModeControllerTest {
    private class Service(var allow: Boolean = true) : ServiceControl {
        val calls = mutableListOf<String>()

        override fun start(): Boolean {
            calls += if (allow) "start" else "start-refused"
            return allow
        }

        override fun stop() {
            calls += "stop"
        }
    }

    @Test
    fun theServiceRunsOnlyWithAVaultInServiceModeAndOffStopsIt() = runTest {
        val mode = MutableStateFlow(NotificationMode.SERVICE)
        val phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
        val s = Service()
        val c = ModeController(backgroundScope, mode, phase, s, NoPushProvider)
        c.start()
        runCurrent()
        assertTrue("nothing while the phase is unknown", s.calls.isEmpty())
        phase.value = AppPhase.Locked
        runCurrent()
        assertEquals(listOf("start"), s.calls)
        phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(listOf("start"), s.calls) // already running
        mode.value = NotificationMode.OFF
        runCurrent()
        assertEquals(listOf("start", "stop"), s.calls)
        mode.value = NotificationMode.SERVICE
        runCurrent()
        phase.value = AppPhase.SignedOut // a wipe
        runCurrent()
        assertEquals(listOf("start", "stop", "start", "stop"), s.calls)
        assertFalse(c.running)
    }

    @Test
    fun googlePushNotAvailableYetRunsNothing() {
        val s = Service()
        val c = ModeController(kotlinx.coroutines.test.TestScope(), MutableStateFlow(NotificationMode.PUSH), MutableStateFlow(AppPhase.Locked), s, NoPushProvider)
        c.apply(NotificationMode.SERVICE, enrolled = true)
        // Switching away stops the old path before the new one starts.
        c.apply(NotificationMode.PUSH, enrolled = true)
        assertEquals(listOf("start", "stop"), s.calls)
    }

    @Test
    fun aStartRefusedInTheBackgroundIsTriedAgainInFront() {
        val s = Service(allow = false)
        val c = ModeController(kotlinx.coroutines.test.TestScope(), MutableStateFlow(NotificationMode.SERVICE), MutableStateFlow(AppPhase.Locked), s, NoPushProvider)
        c.apply(NotificationMode.SERVICE, enrolled = true)
        assertFalse(c.running)
        s.allow = true
        c.retry()
        assertTrue(c.running)
        assertEquals(listOf("start-refused", "start"), s.calls)
    }

    @Test
    fun enrolledPhases() {
        assertNull(ModeController.enrolled(AppPhase.Starting))
        assertEquals(true, ModeController.enrolled(AppPhase.Unreachable(FailureKind.NETWORK)))
        assertEquals(false, ModeController.enrolled(AppPhase.Setup(SetupStage.NEW_VAULT)))
        assertEquals(false, ModeController.enrolled(AppPhase.SignedOut))
    }
}
