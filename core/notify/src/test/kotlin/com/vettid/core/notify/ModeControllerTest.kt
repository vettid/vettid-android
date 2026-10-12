// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.notify

import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.PushMemory
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
    fun aPhoneWithoutAVaultStopsAServiceThisProcessNeverStartedOnce() = runTest {
        // Android restarted the service in a new process, or an earlier build left its notification behind (owner
        // report 2026-10-11): the first look at a phone without a vault stops it, which also cancels the notification.
        val mode = MutableStateFlow(NotificationMode.SERVICE)
        val phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
        val s = Service()
        val c = ModeController(backgroundScope, mode, phase, s, NoPushProvider)
        c.start()
        runCurrent()
        assertTrue(s.calls.isEmpty())
        phase.value = AppPhase.SignedOut
        runCurrent()
        assertEquals(listOf("stop"), s.calls)
        mode.value = NotificationMode.OFF
        phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
        runCurrent()
        assertEquals("once", listOf("stop"), s.calls)
        mode.value = NotificationMode.SERVICE
        phase.value = AppPhase.Unlocked
        runCurrent()
        phase.value = AppPhase.SignedOut // the vault moved to another phone: this one erased itself
        runCurrent()
        assertEquals(listOf("stop", "start", "stop"), s.calls)
        assertFalse(c.running)
    }

    @Test
    fun googlePushInABuildWithoutItKeepsTheServiceRunning() {
        // ANDROID-PLAN 0.1.32 (owner decision 2026-10-11): the mode is Google push, the build has none ("Not
        // available yet", or a build without Google code): the on-phone service is the fallback.
        val s = Service()
        val c = ModeController(kotlinx.coroutines.test.TestScope(), MutableStateFlow(NotificationMode.PUSH), MutableStateFlow(AppPhase.Locked), s, NoPushProvider)
        c.apply(NotificationMode.SERVICE, enrolled = true)
        c.apply(NotificationMode.PUSH, enrolled = true)
        assertEquals(listOf("start"), s.calls)
        assertTrue(c.running)
        c.apply(NotificationMode.OFF, enrolled = true)
        assertEquals(listOf("start", "stop"), s.calls)
    }

    @Test
    fun aColdStartInGooglePushWithoutThePushServiceFallsBackToTheService() = runTest {
        // The process starts (boot, an app update, a relaunch) with Google push chosen earlier, and Google Play
        // services are disabled, removed or outdated, or the build has no Google push any more.
        for (no in PushAvailability.entries - PushAvailability.AVAILABLE) {
            val mode = MutableStateFlow(NotificationMode.PUSH)
            val phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
            val s = Service()
            val reg = Registration(s.calls)
            val c = ModeController(backgroundScope, mode, phase, s, FakePush(no), reg)
            c.start()
            runCurrent()
            assertTrue("$no: nothing while the phase is unknown", s.calls.isEmpty())
            phase.value = AppPhase.Locked
            runCurrent()
            // A registration from before may still exist: it is dropped (at the vault's next open), once; no
            // registration is started; the on-phone service runs.
            assertEquals("$no", listOf("push-unregister", "start"), s.calls)
            assertTrue(c.running)
            phase.value = AppPhase.Unlocked
            runCurrent()
            c.retry() // the app comes to the foreground
            reg.status.value = PushStatus(PushRegState.OFF, "x")
            runCurrent()
            assertEquals("$no: nothing is repeated", listOf("push-unregister", "start"), s.calls)
            assertEquals("the member's choice is never changed", NotificationMode.PUSH, mode.value)
        }
    }

    @Test
    fun thePushServiceGoingAwayWhileRunningFallsBackAndComingBackResumes() = runTest {
        val mode = MutableStateFlow(NotificationMode.PUSH)
        val phase = MutableStateFlow<AppPhase>(AppPhase.Unlocked)
        val s = Service()
        val push = FakePush()
        val reg = Registration(s.calls)
        val c = ModeController(backgroundScope, mode, phase, s, push, reg)
        c.start()
        runCurrent()
        reg.status.value = PushStatus(PushRegState.ACTIVE)
        runCurrent()
        assertEquals(listOf("push-enable"), s.calls)
        assertFalse(c.running)
        // Google Play services are disabled while the app runs; the app next comes to the foreground.
        push.available = PushAvailability.MISSING_SERVICES
        c.retry()
        // The old path stops first (push.unregister, so the vault stops waking a phone that cannot be woken), then
        // the on-phone service starts.
        assertEquals(listOf("push-enable", "push-unregister", "start"), s.calls)
        assertTrue(c.running)
        c.retry()
        phase.value = AppPhase.Locked
        runCurrent()
        assertEquals("once", listOf("push-enable", "push-unregister", "start"), s.calls)
        assertEquals(NotificationMode.PUSH, mode.value)
        // They are back: the registration resumes by itself, and the service runs until the vault holds it.
        push.available = PushAvailability.AVAILABLE
        c.retry()
        assertEquals(listOf("push-enable", "push-unregister", "start", "push-enable"), s.calls)
        assertTrue(c.running)
        reg.status.value = PushStatus(PushRegState.ACTIVE)
        runCurrent()
        assertEquals(listOf("push-enable", "push-unregister", "start", "push-enable", "stop"), s.calls)
        assertFalse(c.running)
    }

    @Test
    fun thePushServiceGoingAwayIsAlsoSeenWhenTheRegistrationSaysItCannotGetAToken() = runTest {
        val s = Service()
        val push = FakePush()
        val reg = Registration(s.calls)
        val c = ModeController(backgroundScope, MutableStateFlow(NotificationMode.PUSH), MutableStateFlow(AppPhase.Unlocked), s, push, reg)
        c.start()
        runCurrent()
        reg.status.value = PushStatus(PushRegState.ACTIVE)
        runCurrent()
        push.available = PushAvailability.SERVICES_OUTDATED
        reg.status.value = PushStatus(PushRegState.RETRYING) // PushRegistrar: no token from a push service that is gone
        runCurrent()
        assertEquals(listOf("push-enable", "push-unregister", "start"), s.calls)
        assertTrue(c.running)
    }

    @Test
    fun aWipeWhileFallenBackStopsTheServiceAndAsksNothingMore() = runTest {
        val phase = MutableStateFlow<AppPhase>(AppPhase.Locked)
        val s = Service()
        val reg = Registration(s.calls)
        val c = ModeController(backgroundScope, MutableStateFlow(NotificationMode.PUSH), phase, s, FakePush(PushAvailability.MISSING_SERVICES), reg)
        c.start()
        runCurrent()
        phase.value = AppPhase.SignedOut
        runCurrent()
        assertEquals(listOf("push-unregister", "start", "stop"), s.calls)
        assertFalse(c.running)
    }

    /** With the real registration: what the vault is sent, and when. */
    @Test
    fun aRegistrationFromBeforeIsDroppedAtTheNextVaultOpenAndOnlyThen() = runTest {
        val log = mutableListOf<String>()
        val vault = FakePushVault(start = AppPhase.Locked, log = log)
        val memory = FakeMemory(PushMemory(registered = true))
        val push = FakePush(PushAvailability.NOT_AVAILABLE_YET) // an update to a build without Google push
        val mode = MutableStateFlow(NotificationMode.PUSH)
        val registrar = PushRegistrar(backgroundScope, push, vault, APP_ID, memory, mode = { mode.value }, nowMs = { testScheduler.currentTime })
        registrar.start()
        val s = Service()
        val c = ModeController(backgroundScope, mode, vault.phase, s, push, registrar)
        c.start()
        runCurrent()
        assertEquals(listOf("start"), s.calls)
        assertTrue("the vault is locked: nothing can be sent yet", log.isEmpty())
        assertEquals(PushMemory(unregisterPending = true), memory.value)
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(listOf("unregister"), log)
        assertEquals(PushMemory(), memory.value)
        // Every later open sends nothing more, and never a registration.
        vault.phase.value = AppPhase.Locked
        runCurrent()
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(listOf("unregister"), log)
        assertEquals(PushRegState.OFF, registrar.status.value.state)
        assertTrue(c.running)
        assertEquals(NotificationMode.PUSH, mode.value)
        // The push service is back (a build with Google push again): registered at the next look, service until then.
        push.available = PushAvailability.AVAILABLE
        c.retry()
        runCurrent()
        assertEquals(2, log.size)
        assertTrue("a new token (the old one was deleted when the registration was dropped)", log[1].startsWith("register fcm tok-") && log[1] != "register fcm tok-1 $APP_ID")
        assertTrue(registrar.status.value.active)
        assertFalse("push delivers again", c.running)
        assertEquals(listOf("start", "stop"), s.calls)
    }

    @Test
    fun aPhoneThatNeverRegisteredSendsTheVaultNothingWhenItFallsBack() = runTest {
        val log = mutableListOf<String>()
        val vault = FakePushVault(log = log)
        val memory = FakeMemory()
        val push = FakePush(PushAvailability.MISSING_SERVICES)
        val mode = MutableStateFlow(NotificationMode.PUSH)
        val registrar = PushRegistrar(backgroundScope, push, vault, APP_ID, memory, mode = { mode.value }, nowMs = { testScheduler.currentTime })
        registrar.start()
        val s = Service()
        ModeController(backgroundScope, mode, vault.phase, s, push, registrar).start()
        runCurrent()
        assertEquals(listOf("start"), s.calls)
        assertTrue(log.isEmpty())
        assertTrue("no token to delete either", push.log.isEmpty())
        assertEquals(PushMemory(), memory.value)
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
        assertEquals("no push; a service left from before is stopped", listOf("stop"), s.calls)
        // GrapheneOS without Google Play services: no registration is started (the service is the fallback, 0.1.32).
        val missing = ModeController(backgroundScope, MutableStateFlow(NotificationMode.PUSH), MutableStateFlow(AppPhase.Unlocked), s, FakePush(PushAvailability.MISSING_SERVICES), reg)
        missing.start()
        runCurrent()
        assertFalse("push-enable" in s.calls)
        assertTrue(missing.running)
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
