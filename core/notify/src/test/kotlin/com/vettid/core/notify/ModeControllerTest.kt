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

    /** The registration as [ModeController] sees it; every call in [calls], shared with the service's. */
    private class Registration(private val calls: MutableList<String>) : PushControl {
        override val status = MutableStateFlow(PushStatus())

        override fun enable() {
            calls += "push-enable"
        }

        override fun disable(wiped: Boolean) {
            calls += if (wiped) "push-drop-wiped" else "push-unregister"
            status.value = PushStatus()
        }
    }

    private fun Service.withPush(mode: MutableStateFlow<NotificationMode>, phase: MutableStateFlow<AppPhase>, scope: kotlinx.coroutines.CoroutineScope): Pair<ModeController, Registration> {
        val reg = Registration(calls)
        return ModeController(scope, mode, phase, this, FakePush(), reg) to reg
    }

    @Test
    fun googlePushRegistersFirstAndThenStopsTheService() = runTest {
        // PUSH-GATEWAY §11.1 step 1: the app sends push.register; then it stops the on-phone service.
        val mode = MutableStateFlow(NotificationMode.SERVICE)
        val phase = MutableStateFlow<AppPhase>(AppPhase.Unlocked)
        val s = Service()
        val (c, reg) = s.withPush(mode, phase, backgroundScope)
        c.start()
        runCurrent()
        mode.value = NotificationMode.PUSH
        runCurrent()
        assertEquals("the service keeps the phone notified until the vault holds the registration", listOf("start", "push-enable"), s.calls)
        reg.status.value = PushStatus(PushRegState.REGISTERING)
        runCurrent()
        assertTrue(c.running)
        reg.status.value = PushStatus(PushRegState.ACTIVE)
        runCurrent()
        assertEquals(listOf("start", "push-enable", "stop"), s.calls)
        assertFalse(c.running)
    }

    @Test
    fun leavingGooglePushUnregistersBeforeTheServiceStarts() = runTest {
        val mode = MutableStateFlow(NotificationMode.PUSH)
        val phase = MutableStateFlow<AppPhase>(AppPhase.Unlocked)
        val s = Service()
        val (c, reg) = s.withPush(mode, phase, backgroundScope)
        c.start()
        runCurrent()
        reg.status.value = PushStatus(PushRegState.ACTIVE)
        runCurrent()
        assertEquals("a process in the mode with nothing to fall back from starts no service", listOf("push-enable"), s.calls)
        // The old path stops first, then the new one starts.
        mode.value = NotificationMode.SERVICE
        runCurrent()
        assertEquals(listOf("push-enable", "push-unregister", "start"), s.calls)
        // And Off stops everything.
        mode.value = NotificationMode.PUSH
        runCurrent()
        reg.status.value = PushStatus(PushRegState.ACTIVE)
        runCurrent()
        mode.value = NotificationMode.OFF
        runCurrent()
        assertEquals(listOf("push-enable", "push-unregister", "start", "push-enable", "stop", "push-unregister"), s.calls)
        assertFalse(c.running)
    }

    @Test
    fun theServiceIsTheFallbackWhilePushCannotDeliver() = runTest {
        val mode = MutableStateFlow(NotificationMode.PUSH)
        val phase = MutableStateFlow<AppPhase>(AppPhase.Unlocked)
        val s = Service()
        val (c, reg) = s.withPush(mode, phase, backgroundScope)
        c.start()
        runCurrent()
        reg.status.value = PushStatus(PushRegState.ACTIVE)
        runCurrent()
        // `limit`: "Push unavailable, using the on-phone service" (owner decision 2026-10-10).
        reg.status.value = PushStatus(PushRegState.LIMITED)
        runCurrent()
        assertTrue(c.running)
        // "Registering again" after push.token-needed is no reason to start it; the 24 hours over, push takes over.
        reg.status.value = PushStatus(PushRegState.REGISTERING)
        runCurrent()
        assertTrue(c.running)
        reg.status.value = PushStatus(PushRegState.ACTIVE)
        runCurrent()
        assertFalse(c.running)
        reg.status.value = PushStatus(PushRegState.REGISTERING_AGAIN)
        runCurrent()
        assertFalse(c.running)
        for (st in listOf(PushRegState.UNAVAILABLE, PushRegState.RETRYING, PushRegState.REFUSED)) {
            reg.status.value = PushStatus(st)
            runCurrent()
            assertTrue("$st", c.running)
            reg.status.value = PushStatus(PushRegState.ACTIVE)
            runCurrent()
            assertFalse(c.running)
        }
        assertEquals("the registration is not touched by the fallback", 1, s.calls.count { it.startsWith("push-") })
    }

    @Test
    fun aWipeDropsTheRegistrationWithoutAVaultAndNothingRunsAfterIt() = runTest {
        val mode = MutableStateFlow(NotificationMode.PUSH)
        val phase = MutableStateFlow<AppPhase>(AppPhase.Unlocked)
        val s = Service()
        val (c, reg) = s.withPush(mode, phase, backgroundScope)
        c.start()
        runCurrent()
        reg.status.value = PushStatus(PushRegState.LIMITED) // the fallback service runs
        runCurrent()
        phase.value = AppPhase.SignedOut
        runCurrent()
        assertEquals(listOf("push-enable", "start", "push-drop-wiped", "stop"), s.calls)
        assertFalse(c.running)
    }

    @Test
    fun pushIsNotStartedWithoutAVaultOrWhereItIsNotAvailable() = runTest {
        val s = Service()
        val reg = Registration(s.calls)
        val c = ModeController(backgroundScope, MutableStateFlow(NotificationMode.PUSH), MutableStateFlow(AppPhase.SignedOut), s, FakePush(), reg)
        c.start()
        runCurrent()
        assertTrue(s.calls.isEmpty())
        // GrapheneOS without Google Play services: nothing of push is asked for.
        val missing = ModeController(backgroundScope, MutableStateFlow(NotificationMode.PUSH), MutableStateFlow(AppPhase.Unlocked), s, FakePush(PushAvailability.MISSING_SERVICES), reg)
        missing.start()
        runCurrent()
        assertTrue(s.calls.isEmpty())
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
