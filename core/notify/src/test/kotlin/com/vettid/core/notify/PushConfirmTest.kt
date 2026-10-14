// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.notify

import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.PushMemory
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.PushConfirmOutcome
import com.vettid.core.data.vault.PushRegisterOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-step registration (VAULT-MESSAGING 0.30.0 §14.1, PUSH-GATEWAY 0.5.0 §5.1, §11.1): `push.register` answers
 * `pending`, the gateway pushes a challenge code, and the app returns it with `push.confirm`. Fakes for Firebase and
 * the vault ([FakePush], [FakePushVault]): no Firebase, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushConfirmTest {
    private val log = mutableListOf<String>()
    private val push = FakePush()
    private val vault = FakePushVault(log = log)
    private val memory = FakeMemory()
    private val lines = mutableListOf<String>()

    private fun TestScope.registrar() =
        PushRegistrar(backgroundScope, push, vault, APP_ID, memory, mode = { NotificationMode.PUSH }, nowMs = { testScheduler.currentTime }, elapsedMs = { testScheduler.currentTime }, log = { lines += it }).also {
            it.start()
            runCurrent()
        }

    /** Enabled, and `push.register` answered `pending`. */
    private fun TestScope.pendingRegistrar(): PushRegistrar {
        vault.answers += PushRegisterOutcome.Registered(FakePushVault.pending())
        val r = registrar()
        r.enable()
        runCurrent()
        return r
    }

    @Test
    fun pendingWaitsForTheChallengeWithTheServiceAsFallbackThenConfirms() = runTest {
        val r = pendingRegistrar()
        assertEquals(PushRegState.VERIFYING, r.status.value.state)
        assertFalse("not registered before the confirmation", r.status.value.active)
        assertTrue("the on-phone service runs until the registration is active", r.status.value.fallback)
        assertFalse(memory.value.registered)
        push.onChallenge!!.invoke("Q2hhbGxlbmdlLWNvZGUtMQ")
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID", "confirm Q2hhbGxlbmdlLWNvZGUtMQ"), log)
        assertEquals(PushStatus(PushRegState.ACTIVE), r.status.value)
        assertEquals("confirmPending cleared", PushMemory(registered = true), memory.value)
        assertEquals("a challenge is not a wake: nothing is collected", 0, vault.collected)
        // The window's end no longer does anything.
        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals(2, log.size)
    }

    @Test
    fun aChallengeThatArrivesBeforeThePendingAnswerIsConfirmed() = runTest {
        // The gateway sends the challenge before it answers 202: FCM may be faster than the vault's answer.
        vault.answers += PushRegisterOutcome.Registered(FakePushVault.pending())
        val r = registrar()
        vault.duringRegister = { r.onChallenge("early-code") }
        r.enable()
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID", "confirm early-code"), log)
        assertTrue(r.status.value.active)
    }

    @Test
    fun onlyTheNewestCodeIsSent() = runTest {
        val r = pendingRegistrar()
        r.onChallenge("older-code")
        r.onChallenge("newer-code")
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID", "confirm newer-code"), log)
        assertTrue(r.status.value.active)
    }

    @Test
    fun aCodeNobodyExpectsIsDropped() = runTest {
        val r = registrar()
        r.onChallenge("stray-code") // not in the mode
        r.enable()
        runCurrent()
        r.onChallenge("late-code") // active: no registration is pending
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID"), log)
        assertTrue(r.status.value.active)
        assertEquals(0, vault.collected)
    }

    @Test
    fun aCodeThatIsNotACodeIsDropped() = runTest {
        val r = pendingRegistrar()
        r.onChallenge("")
        r.onChallenge("not a code!")
        r.onChallenge("x".repeat(65))
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID"), log)
        assertEquals(PushRegState.VERIFYING, r.status.value.state)
    }

    @Test
    fun noSecondRegisterStartsWhileOneIsPending() = runTest {
        val r = pendingRegistrar()
        // An open: the vault would answer the same pending registration without a new code. (A rotated token is
        // registered at once: aRotatedTokenWhilePendingIsRegisteredAtOnce.)
        vault.phase.value = AppPhase.Locked
        runCurrent()
        assertEquals("locked, it still waits (the vault keeps its pending registration)", PushRegState.VERIFYING, r.status.value.state)
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID"), log)
        r.onChallenge("the-code")
        runCurrent()
        assertTrue(r.status.value.active)
    }

    @Test
    fun noCodeWithinTenMinutesRegistersAgainWithBackoff() = runTest {
        val r = pendingRegistrar()
        advanceTimeBy(PushRegistrar.CHALLENGE_WINDOW_MS - 1)
        runCurrent()
        assertEquals(PushRegState.VERIFYING, r.status.value.state)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(PushRegState.RETRYING, r.status.value.state)
        assertTrue(r.status.value.fallback)
        // A code after the window is not sent.
        r.onChallenge("too-late")
        runCurrent()
        assertEquals(1, log.size)
        // The registration's backoff: 1 minute, then doubling (a pending answer does not reset it).
        vault.answers += PushRegisterOutcome.Registered(FakePushVault.pending())
        advanceTimeBy(MINUTE)
        runCurrent()
        assertEquals(2, log.size)
        assertEquals(PushRegState.VERIFYING, r.status.value.state)
        advanceTimeBy(PushRegistrar.CHALLENGE_WINDOW_MS + 2 * MINUTE - 1)
        runCurrent()
        assertEquals("then 2 minutes", 2, log.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, log.size)
        assertTrue("the default answer is active", r.status.value.active)
    }

    @Test
    fun aCodeWhileTheVaultIsLockedIsKeptAndConfirmedWhenItOpens() = runTest {
        val r = pendingRegistrar()
        vault.phase.value = AppPhase.Locked
        runCurrent()
        r.onChallenge("locked-code")
        runCurrent()
        assertEquals("nothing is sent to a locked vault", 1, log.size)
        assertEquals(PushRegState.VERIFYING, r.status.value.state)
        advanceTimeBy(5 * MINUTE)
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals("the kept code, not a second registration", listOf("register fcm tok-1 $APP_ID", "confirm locked-code"), log)
        assertTrue(r.status.value.active)
    }

    @Test
    fun aKeptCodeEndsWithTheWindowAndTheNextOpenRegisters() = runTest {
        val r = pendingRegistrar()
        vault.phase.value = AppPhase.Locked
        runCurrent()
        r.onChallenge("locked-code")
        runCurrent()
        advanceTimeBy(PushRegistrar.CHALLENGE_WINDOW_MS + MINUTE)
        runCurrent()
        assertEquals(1, log.size)
        assertTrue("never confirmed: still the fallback while locked", r.status.value.fallback)
        vault.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID", "register fcm tok-1 $APP_ID"), log)
        assertTrue(r.status.value.active)
    }

    @Test
    fun aRotatedTokenWhilePendingIsRegisteredAtOnce() = runTest {
        val r = pendingRegistrar()
        vault.answers += PushRegisterOutcome.Registered(FakePushVault.pending())
        vault.confirms += PushConfirmOutcome.ChallengeFailed
        push.generation = 2 // Firebase's onNewToken
        push.onNewToken!!.invoke()
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID", "register fcm tok-2 $APP_ID"), log)
        assertEquals(PushRegState.VERIFYING, r.status.value.state)
        // The old token's code: the vault replaced that pending registration, so the gateway refuses it.
        r.onChallenge("old-token-code")
        runCurrent()
        assertEquals(PushRegState.RETRYING, r.status.value.state)
        assertFalse(memory.value.registered)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertEquals("register fcm tok-2 $APP_ID", log.last())
        assertTrue(r.status.value.active)
    }

    @Test
    fun aNewProcessAfterAnUnconfirmedRegistrationFallsBackUntilItIsActive() = runTest {
        // The process died (or the app was updated) during the window: only the memory is left.
        pendingRegistrar()
        assertEquals(PushMemory(confirmPending = true), memory.value)
        vault.phase.value = AppPhase.Locked
        val log2 = mutableListOf<String>()
        val v2 = FakePushVault(AppPhase.Locked, log2)
        val r = PushRegistrar(backgroundScope, FakePush(), v2, APP_ID, memory, mode = { NotificationMode.PUSH }, nowMs = { testScheduler.currentTime }, elapsedMs = { testScheduler.currentTime })
        r.start()
        r.enable()
        runCurrent()
        assertEquals(PushRegState.VERIFYING, r.status.value.state)
        assertTrue("the on-phone service runs", r.status.value.fallback)
        assertTrue(log2.isEmpty())
        // The open registers (no window in this process); the vault's answer decides.
        v2.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID"), log2)
        assertTrue(r.status.value.active)
        assertEquals(PushMemory(registered = true), memory.value)
    }

    @Test
    fun aRefusedOrExpiredChallengeRegistersAgain() = runTest {
        for (failed in listOf(PushConfirmOutcome.ChallengeFailed, PushConfirmOutcome.NoPending)) {
            log.clear()
            val r = pendingRegistrar()
            vault.confirms += failed
            r.onChallenge("refused-code")
            runCurrent()
            assertEquals("$failed", PushRegState.RETRYING, r.status.value.state)
            assertFalse(memory.value.registered)
            advanceTimeBy(MINUTE)
            runCurrent()
            assertEquals("$failed", listOf("register fcm tok-1 $APP_ID", "confirm refused-code", "register fcm tok-1 $APP_ID"), log)
            assertTrue(r.status.value.active)
            r.disable(wiped = false)
            runCurrent()
            memory.value = PushMemory()
            push.generation = 1 // leaving the mode deleted the token
        }
    }

    @Test
    fun aGatewayErrorTheVaultKeepsItsPendingRegistrationForIsConfirmedAgain() = runTest {
        val r = pendingRegistrar()
        vault.confirms += PushConfirmOutcome.GatewayError()
        vault.confirms += PushConfirmOutcome.NoAnswer
        r.onChallenge("same-code")
        runCurrent()
        assertEquals(PushRegState.VERIFYING, r.status.value.state)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertEquals("then 2 minutes", 3, log.size)
        advanceTimeBy(2 * MINUTE)
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID", "confirm same-code", "confirm same-code", "confirm same-code"), log)
        assertTrue(r.status.value.active)
    }

    @Test
    fun aRetryAfterBeyondTheWindowWaitsForTheWindowsEnd() = runTest {
        val r = pendingRegistrar()
        vault.confirms += PushConfirmOutcome.GatewayError(retryAfterSeconds = 900)
        r.onChallenge("the-code")
        runCurrent()
        advanceTimeBy(PushRegistrar.CHALLENGE_WINDOW_MS)
        runCurrent()
        assertEquals("no confirmation after the window", 2, log.size)
        assertEquals(PushRegState.RETRYING, r.status.value.state)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertEquals("register fcm tok-1 $APP_ID", log.last())
    }

    @Test
    fun otherAnswersToTheConfirmation() = runTest {
        val r = pendingRegistrar()
        vault.confirms += PushConfirmOutcome.Unavailable
        r.onChallenge("the-code")
        runCurrent()
        assertEquals(PushRegState.UNAVAILABLE, r.status.value.state)
        r.disable(wiped = false)
        runCurrent()
        log.clear()
        val again = pendingRegistrar()
        vault.confirms += PushConfirmOutcome.Refused("forbidden")
        again.onChallenge("the-code")
        runCurrent()
        assertEquals(PushRegState.REFUSED, again.status.value.state)
        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals("not retried before the next open", 2, log.size)
    }

    @Test
    fun anExpiredBlobLeavesThePendingRegistrationAlone() = runTest {
        val r = pendingRegistrar()
        vault.pushTokenNeeded.emit("blob_invalid")
        runCurrent()
        assertEquals(PushRegState.VERIFYING, r.status.value.state)
        assertEquals(1, log.size)
        r.onChallenge("the-code")
        runCurrent()
        assertTrue(r.status.value.active)
    }

    @Test
    fun leavingTheModeWhilePendingForgetsTheCode() = runTest {
        val r = pendingRegistrar()
        r.disable(wiped = false)
        runCurrent()
        r.onChallenge("the-code")
        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals(listOf("register fcm tok-1 $APP_ID", "unregister"), log)
        assertEquals(PushRegState.OFF, r.status.value.state)
    }

    @Test
    fun theLogNeverHoldsTheCode() = runTest {
        val r = pendingRegistrar()
        vault.confirms += PushConfirmOutcome.GatewayError()
        r.onChallenge("c0de-never-logged")
        runCurrent()
        advanceTimeBy(MINUTE)
        runCurrent()
        r.onChallenge("c0de-never-logged-2") // not expected any more
        runCurrent()
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.toString(), lines.none { "c0de" in it || "tok-" in it })
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
