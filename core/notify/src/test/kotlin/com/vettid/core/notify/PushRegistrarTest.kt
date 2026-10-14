// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.notify

import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.PushMemory
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PushConfirmOutcome
import com.vettid.core.data.vault.PushRegisterOutcome
import com.vettid.core.data.vault.PushStateView
import com.vettid.core.data.vault.PushVault
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A push service without Firebase: tokens `tok-1`, `tok-2`, … (a new one after each delete). */
internal class FakePush(var available: PushAvailability = PushAvailability.AVAILABLE) : PushProvider {
    val log = mutableListOf<String>()
    var generation = 1
    var noToken = false

    override fun availability() = available

    override suspend fun token(): String? = if (noToken || available != PushAvailability.AVAILABLE) null else "tok-$generation"

    override suspend fun deleteToken() {
        log += "delete-token"
        generation++
    }

    override var onWake: (suspend () -> Boolean)? = null
    override var onNewToken: (() -> Unit)? = null
    override var onChallenge: ((String) -> Unit)? = null
}

/** The vault's side of §14 without a vault: answers from [answers] (then "registered"), every call in [log]. */
internal class FakePushVault(start: AppPhase = AppPhase.Unlocked, private val log: MutableList<String> = mutableListOf()) : PushVault {
    override val phase = MutableStateFlow(start)
    override val pushTokenNeeded = MutableSharedFlow<String>(replay = 1)
    val answers = ArrayDeque<PushRegisterOutcome>()

    /** The answers to `push.confirm` (then "active"). */
    val confirms = ArrayDeque<PushConfirmOutcome>()

    /** Runs inside `push.register`, before its answer: a challenge that arrives before `pending` does. */
    var duringRegister: (() -> Unit)? = null
    val calls: List<String> get() = log
    var listed: PushStateView? = active()
    var listFails = false
    var collected = 0
    var collectDone = true

    override suspend fun pushRegister(platform: String, token: String, appId: String): PushRegisterOutcome {
        if (phase.value != AppPhase.Unlocked) return PushRegisterOutcome.NoAnswer
        log += "register $platform $token $appId"
        duringRegister?.invoke()
        return answers.removeFirstOrNull() ?: PushRegisterOutcome.Registered(active())
    }

    override suspend fun pushConfirm(code: String): PushConfirmOutcome {
        if (phase.value != AppPhase.Unlocked) return PushConfirmOutcome.NoAnswer
        log += "confirm $code"
        return confirms.removeFirstOrNull() ?: PushConfirmOutcome.Confirmed(active())
    }

    override suspend fun pushUnregister(): Boolean {
        if (phase.value != AppPhase.Unlocked) return false
        log += "unregister"
        return true
    }

    override suspend fun pushState(): PushStateView? {
        if (listFails) throw VaultFailure(FailureKind.NETWORK)
        log += "device.list"
        return listed
    }

    override suspend fun collectForWake(): Boolean {
        collected++
        return collectDone
    }

    companion object {
        fun active(lastResult: String? = null) = PushStateView("fcm", APP_ID, "active", lastResult)

        fun pending() = PushStateView("fcm", APP_ID, "pending")
    }
}

internal const val APP_ID = "com.vettid.app.test"

internal class FakeMemory(var value: PushMemory = PushMemory()) : PushMemoryStore {
    override suspend fun load() = value

    override suspend fun save(m: PushMemory) {
        value = m
    }
}

/**
 * The Google push registration (ANDROID-PLAN 0.1.30, Notification modes 4; VAULT-MESSAGING 0.24.1 §14; PUSH-GATEWAY
 * 0.4.1 §11.1), with fakes for Firebase and the vault: no Firebase, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrarTest {
    private val log = mutableListOf<String>()
    private val push = FakePush().also { p -> p.log.clear() }
    private val vault = FakePushVault(log = log)
    private val memory = FakeMemory()
    private var mode = NotificationMode.PUSH

    private fun TestScope.registrar(v: FakePushVault = vault) =
        PushRegistrar(backgroundScope, push, v, APP_ID, memory, mode = { mode }, nowMs = { testScheduler.currentTime }).also {
            it.start()
            runCurrent()
        }

    /** Everything the registration did, in order: the vault's calls and the token deletions. */
    private fun all(): List<String> = log + push.log

    @Test
    fun choosingGooglePushSendsTheTokenToTheVault() = runTest {
        val r = registrar()
        assertEquals(PushRegState.OFF, r.status.value.state)
        assertTrue("nothing before the member chooses the mode", all().isEmpty())
        r.enable()
        runCurrent()
        // platform "fcm", the token, and app_id = the build's application id; no environment for fcm.
        assertEquals(listOf("register fcm tok-1 $APP_ID"), log)
        assertEquals(PushStatus(PushRegState.ACTIVE), r.status.value)
        assertEquals(PushMemory(registered = true), memory.value)
    }

    @Test
    fun aLockedVaultIsRegisteredWithWhenItOpens() = runTest {
        vault.phase.value = AppPhase.Locked
        val r = registrar()
        r.enable()
        runCurrent()
        assertTrue(log.isEmpty())
        assertEquals(PushRegState.WAITING, r.status.value.state)
        assertFalse("waiting for the unlock is no reason for the fallback", r.status.value.fallback)
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID"), log)
        assertTrue(r.status.value.active)
    }

    @Test
    fun everyOpenRegistersAgainWithTheSameToken() = runTest {
        // The vault answers an unchanged token from its state without the gateway (§14.1 check 4), so this is free.
        val r = registrar()
        r.enable()
        runCurrent()
        vault.phase.value = AppPhase.Locked
        runCurrent()
        assertTrue("a registration is known: still the working path while locked", r.status.value.active)
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID", "register fcm tok-1 $APP_ID"), log)
    }

    @Test
    fun aRotatedTokenIsRegistered() = runTest {
        val r = registrar()
        r.enable()
        runCurrent()
        push.generation = 2 // Firebase's onNewToken
        push.onNewToken!!.invoke()
        runCurrent()
        assertEquals("register fcm tok-2 $APP_ID", log.last())
        assertTrue(r.status.value.active)
    }

    @Test
    fun aRotatedTokenOutsideTheModeGoesNowhere() = runTest {
        registrar()
        push.onNewToken!!.invoke()
        runCurrent()
        assertTrue(all().isEmpty())
    }

    @Test
    fun tokenNeededSendsAFreshRegistration() = runTest {
        val r = registrar()
        r.enable()
        runCurrent()
        vault.phase.value = AppPhase.Locked // so that "Registering again" can be seen before the answer
        runCurrent()
        vault.pushTokenNeeded.emit("token_gone")
        runCurrent()
        assertEquals(PushRegState.REGISTERING_AGAIN, r.status.value.state)
        assertFalse(memory.value.registered)
        assertEquals("the dead token is deleted at once", listOf("delete-token"), push.log)
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals("a new token", "register fcm tok-2 $APP_ID", log.last())
        assertTrue(r.status.value.active)
    }

    @Test
    fun anInvalidBlobKeepsTheToken() = runTest {
        val r = registrar()
        r.enable()
        runCurrent()
        vault.pushTokenNeeded.emit("blob_invalid")
        runCurrent()
        assertTrue("the token is fine: only the gateway's blob is renewed", push.log.isEmpty())
        assertEquals(listOf("register fcm tok-1 $APP_ID", "register fcm tok-1 $APP_ID"), log)
        assertTrue(r.status.value.active)
    }

    @Test
    fun tokenNeededOutsideTheModeSendsNothing() = runTest {
        memory.value = PushMemory(registered = true, unregisterPending = true)
        vault.phase.value = AppPhase.Locked
        val r = registrar()
        vault.pushTokenNeeded.emit("token_gone")
        runCurrent()
        assertTrue(all().isEmpty())
        assertEquals("the vault erased the registration itself: nothing is left to drop", PushMemory(), memory.value)
        assertEquals(PushRegState.OFF, r.status.value.state)
    }

    @Test
    fun unavailableIsNotRetriedBeforeTheNextOpenAndFallsBack() = runTest {
        vault.answers += PushRegisterOutcome.Unavailable
        val r = registrar()
        r.enable()
        runCurrent()
        assertEquals(PushRegState.UNAVAILABLE, r.status.value.state)
        assertTrue(r.status.value.fallback)
        assertFalse(memory.value.registered)
        advanceTimeBy(6 * HOUR)
        assertEquals(1, log.size)
        // The vault moved to a release with a gateway: the next open registers.
        vault.phase.value = AppPhase.Locked
        runCurrent()
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(2, log.size)
        assertTrue(r.status.value.active)
    }

    @Test
    fun aGatewayErrorIsRetriedOneMinuteDoublingToOneHour() = runTest {
        repeat(9) { vault.answers += PushRegisterOutcome.GatewayError() }
        val r = registrar()
        r.enable()
        runCurrent()
        assertEquals(PushRegState.RETRYING, r.status.value.state)
        assertTrue(r.status.value.fallback)
        val waits = listOf(1, 2, 4, 8, 16, 32, 60, 60).map { it * MINUTE }
        waits.forEachIndexed { i, w ->
            advanceTimeBy(w - 1)
            assertEquals("not before ${w / MINUTE} min", i + 1, log.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(i + 2, log.size)
        }
        // The ninth answer is still an error; the tenth registers.
        advanceTimeBy(60 * MINUTE)
        runCurrent()
        assertTrue(r.status.value.active)
        assertEquals(PushMemory(registered = true), memory.value)
    }

    @Test
    fun aGatewayErrorsRetryAfterIsFollowed() = runTest {
        vault.answers += PushRegisterOutcome.GatewayError(retryAfterSeconds = 300)
        val r = registrar()
        r.enable()
        runCurrent()
        advanceTimeBy(300_000 - 1)
        assertEquals(1, log.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, log.size)
        assertTrue(r.status.value.active)
    }

    @Test
    fun limitStopsEveryRegistrationForTwentyFourHours() = runTest {
        vault.answers += PushRegisterOutcome.Limit
        val r = registrar()
        r.enable()
        runCurrent()
        assertEquals(PushRegState.LIMITED, r.status.value.state)
        assertTrue("the on-phone service runs meanwhile", r.status.value.fallback)
        assertEquals(24 * HOUR, memory.value.limitedUntilMs)
        // Nothing is sent meanwhile: not at an unlock, not for a new token, not when the vault asks.
        advanceTimeBy(23 * HOUR)
        vault.phase.value = AppPhase.Locked
        runCurrent()
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        push.onNewToken!!.invoke()
        runCurrent()
        vault.pushTokenNeeded.emit("blob_invalid")
        runCurrent()
        assertEquals(1, log.size)
        assertEquals(PushRegState.LIMITED, r.status.value.state)
        // After the 24 hours it registers by itself.
        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals(2, log.size)
        assertTrue(r.status.value.active)
        assertEquals(0, memory.value.limitedUntilMs)
    }

    @Test
    fun theLimitsPauseSurvivesARestart() = runTest {
        memory.value = PushMemory(limitedUntilMs = 5 * HOUR)
        val r = registrar() // a new process: only the memory is left
        r.enable()
        runCurrent()
        assertEquals(PushRegState.LIMITED, r.status.value.state)
        assertTrue(log.isEmpty())
        advanceTimeBy(5 * HOUR)
        runCurrent()
        assertEquals(1, log.size)
    }

    @Test
    fun aRefusalWaitsForTheNextOpenAndABadTokenIsReplaced() = runTest {
        vault.answers += PushRegisterOutcome.Refused("bad_request")
        val r = registrar()
        r.enable()
        runCurrent()
        assertEquals(PushRegState.REFUSED, r.status.value.state)
        assertTrue(r.status.value.fallback)
        assertEquals(listOf("delete-token"), push.log)
        advanceTimeBy(6 * HOUR)
        assertEquals(1, log.size)
    }

    @Test
    fun noAnswerKeepsAKnownRegistrationAndIsTriedAgain() = runTest {
        memory.value = PushMemory(registered = true)
        vault.answers += PushRegisterOutcome.NoAnswer
        val r = registrar()
        r.enable()
        runCurrent()
        // A failed request changes nothing at the vault (§14.1): what was registered still is.
        assertTrue(r.status.value.active)
        assertTrue(memory.value.registered)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertEquals(2, log.size)
    }

    @Test
    fun withoutATokenFromThePushServiceItIsTriedAgain() = runTest {
        push.noToken = true
        val r = registrar()
        r.enable()
        runCurrent()
        assertTrue("the vault is not asked without a token", log.isEmpty())
        assertEquals(PushRegState.RETRYING, r.status.value.state)
        push.noToken = false
        advanceTimeBy(MINUTE)
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID"), log)
    }

    @Test
    fun aPushServiceThatIsGoneIsSaidEvenOfAKnownRegistration() = runTest {
        // ANDROID-PLAN 0.1.32: Google Play services disabled or removed while the app runs. The registration the
        // vault holds cannot wake the phone any more, so the status leaves "active" (the mode controller then falls
        // back to the on-phone service and has the registration dropped).
        val r = registrar()
        r.enable()
        runCurrent()
        assertTrue(r.status.value.active)
        push.available = PushAvailability.MISSING_SERVICES
        vault.phase.value = AppPhase.Locked
        runCurrent()
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals("no token: the vault is not asked", 1, log.size)
        assertEquals(PushRegState.RETRYING, r.status.value.state)
        assertTrue(r.status.value.fallback)
        // What the mode controller then asks for: push.unregister, now, since the vault is open.
        r.disable(wiped = false)
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID", "unregister"), log)
        assertEquals(PushMemory(), memory.value)
        assertEquals(PushRegState.OFF, r.status.value.state)
        // A transient "no token" of a push service that is there keeps a known registration active, as before.
        push.available = PushAvailability.AVAILABLE
        r.enable()
        runCurrent()
        assertTrue(r.status.value.active)
        push.noToken = true
        vault.phase.value = AppPhase.Locked
        runCurrent()
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertTrue(r.status.value.active)
    }

    @Test
    fun aColdStartWithoutThePushServiceDropsAnOldRegistrationAtTheNextOpen() = runTest {
        // The process starts in the Google push mode with the push service unusable: the registration is never
        // enabled; the mode controller asks for the drop.
        memory.value = PushMemory(registered = true)
        push.available = PushAvailability.NOT_AVAILABLE_YET
        vault.phase.value = AppPhase.Locked
        val r = registrar()
        r.disable(wiped = false)
        runCurrent()
        assertTrue(log.isEmpty())
        assertEquals(PushMemory(unregisterPending = true), memory.value)
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(listOf("unregister"), log)
        assertEquals(PushMemory(), memory.value)
        assertEquals(PushRegState.OFF, r.status.value.state)
    }

    @Test
    fun leavingTheModeUnregistersThenDeletesTheToken() = runTest {
        val r = registrar()
        r.enable()
        runCurrent()
        log.clear()
        r.disable(wiped = false)
        runCurrent()
        assertEquals(listOf("unregister", "delete-token"), all())
        assertEquals(PushMemory(), memory.value)
        assertEquals(PushRegState.OFF, r.status.value.state)
        // And nothing more is sent: not at the next open, not for a new token.
        vault.phase.value = AppPhase.Locked
        runCurrent()
        vault.phase.value = AppPhase.Unlocked
        push.onNewToken!!.invoke()
        runCurrent()
        assertEquals(listOf("unregister", "delete-token"), all())
    }

    @Test
    fun leavingTheModeWhileTheVaultIsLockedTellsItAtTheNextOpen() = runTest {
        val r = registrar()
        r.enable()
        runCurrent()
        log.clear()
        vault.phase.value = AppPhase.Locked
        runCurrent()
        r.disable(wiped = false)
        runCurrent()
        assertEquals("the token is deleted all the same: Google can no longer wake this phone", listOf("delete-token"), all())
        assertEquals(PushMemory(unregisterPending = true), memory.value)
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals("unregister", log.single())
        assertEquals(PushMemory(), memory.value)
    }

    @Test
    fun aPendingUnregisterSurvivesARestart() = runTest {
        memory.value = PushMemory(unregisterPending = true)
        vault.phase.value = AppPhase.Locked
        registrar()
        assertTrue(log.isEmpty())
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(listOf("unregister"), log)
        assertFalse(memory.value.unregisterPending)
    }

    @Test
    fun comingBackToTheModeForgetsAPendingUnregister() = runTest {
        memory.value = PushMemory(unregisterPending = true)
        val r = registrar()
        log.clear()
        r.enable()
        runCurrent()
        assertEquals(PushMemory(registered = true), memory.value)
        assertFalse("unregister" in log)
    }

    @Test
    fun aWipeDeletesTheTokenAndAsksNoVault() = runTest {
        val r = registrar()
        r.enable()
        runCurrent()
        log.clear()
        vault.phase.value = AppPhase.SignedOut
        r.disable(wiped = true)
        runCurrent()
        // The vault that removed this app erased the registration itself (§14.1): there is no vault to tell.
        assertEquals(listOf("delete-token"), all())
        assertEquals(PushMemory(), memory.value)
        assertEquals(PushRegState.OFF, r.status.value.state)
    }

    @Test
    fun aPhoneThatNeverUsedPushDeletesNothing() = runTest {
        val r = registrar()
        log.clear()
        r.disable(wiped = true)
        r.disable(wiped = false)
        runCurrent()
        assertTrue("Firebase is not touched on a phone that never chose Google push", all().isEmpty())
    }

    @Test
    fun aWakeCollectsInTheModeOnly() = runTest {
        val r = registrar()
        r.enable()
        runCurrent()
        assertTrue(push.onWake!!.invoke())
        assertEquals(1, vault.collected)
        vault.collectDone = false
        assertFalse("not finished: the provider continues it as expedited work", r.onWake())
        assertEquals(2, vault.collected)
    }

    @Test
    fun aWakeInAProcessItStartedCollectsBeforeTheModeIsApplied() = runTest {
        // The mode preference says Google push, but nothing enabled the registration yet (the phase is not known).
        registrar(FakePushVault(AppPhase.Starting, log))
        assertTrue(push.onWake!!.invoke())
    }

    @Test
    fun aWakeOutsideTheModeCollectsNothingAndDropsTheStrayRegistration() = runTest {
        mode = NotificationMode.SERVICE
        registrar()
        log.clear()
        assertTrue(push.onWake!!.invoke())
        runCurrent()
        assertEquals("Google push drives nothing in another mode", 0, vault.collected)
        assertEquals(listOf("unregister", "delete-token"), all())
    }

    @Test
    fun theStatusFollowsPushInDeviceList() = runTest {
        val r = registrar()
        r.enable()
        runCurrent()
        log.clear()
        vault.listed = FakePushVault.active("gateway_error")
        r.refresh()
        assertTrue(r.status.value.gatewayUnreachable)
        vault.listed = FakePushVault.active("sent")
        r.refresh()
        assertEquals(PushStatus(PushRegState.ACTIVE, "sent"), r.status.value)
        // A read that fails changes nothing.
        vault.listFails = true
        r.refresh()
        assertEquals(PushStatus(PushRegState.ACTIVE, "sent"), r.status.value)
        // The vault erased the registration (device.list shows no push): registered again.
        vault.listFails = false
        vault.listed = null
        r.refresh()
        assertEquals("register fcm tok-1 $APP_ID", log.last())
        assertTrue(r.status.value.active)
    }

    @Test
    fun theStatusIsNotReadOutsideTheModeOrWhileLocked() = runTest {
        val r = registrar()
        r.refresh()
        r.enable()
        runCurrent()
        vault.phase.value = AppPhase.Locked
        runCurrent()
        log.clear()
        r.refresh()
        assertTrue(log.isEmpty())
    }

    @Test
    fun theRetrySchedule() {
        val b = PushRetryBackoff()
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 60, 60), (1..8).map { b.nextDelayMs() / MINUTE })
        b.reset()
        assertEquals(MINUTE, b.nextDelayMs())
        assertEquals("the vault's retry_after", 90_000L, b.nextDelayMs(90))
        assertEquals("at most an hour", HOUR, b.nextDelayMs(86_400))
        assertEquals(1_000L, b.nextDelayMs(0))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
