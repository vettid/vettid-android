// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.push.fcm

import com.vettid.core.notify.PushAvailability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FCM provider (ANDROID-PLAN 0.1.30, Notification modes 4 and 11) with a fake for Firebase: no Firebase, no
 * Google Play services, no network. The option values are made up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FcmPushProviderTest {
    private class Firebase(var services: PlayServices = PlayServices.OK, var accepts: Boolean = true) : FcmClient {
        val log = mutableListOf<String>()
        var token: String? = "fake-token-1"
        var hang = false

        override fun init(config: FcmConfig): Boolean {
            log += "init ${config.projectId}"
            return accepts
        }

        override fun playServices(): PlayServices = services

        override suspend fun token(): String? {
            log += "token"
            if (hang) awaitCancellation()
            return token
        }

        override suspend fun deleteToken(): Boolean {
            log += "delete"
            return true
        }
    }

    private class Later : WakeContinuation {
        val log = mutableListOf<String>()

        override fun enqueue() {
            log += "enqueue"
        }
    }

    private val fake = FcmConfig("1:000000000000:android:fake0000", "fake-project", "fake-api-key", "000000000000")
    private val firebase = Firebase()
    private val later = Later()

    private fun provider(installed: Boolean = true, config: FcmConfig? = fake) =
        FcmPushProvider(context = android.app.Application(), config = config, client = firebase, installed = { installed }, continuation = later)

    // --- without the Firebase options: as a build without push ---

    @Test
    fun notAvailableYetWithoutConfigurationAndFirebaseIsNeverTouched() = runTest {
        val p = provider(config = null)
        p.start()
        assertEquals(PushAvailability.NOT_AVAILABLE_YET, p.availability())
        assertNull(p.token())
        p.deleteToken()
        assertTrue("no Firebase call at all", firebase.log.isEmpty())
        assertTrue("and nothing of WorkManager", later.log.isEmpty())
    }

    @Test
    fun theOptionsAreAllOrNothing() {
        assertEquals(fake, FcmConfig.of(" 1:000000000000:android:fake0000 ", "fake-project", "fake-api-key", "000000000000"))
        assertNull(FcmConfig.of("", "", "", ""))
        assertNull(FcmConfig.of(null, null, null, null))
        assertNull("three of four is none", FcmConfig.of("1:000000000000:android:fake0000", "fake-project", "fake-api-key", " "))
    }

    // --- Google Play services ---

    @Test
    fun playServicesMissingIsSaid() = runTest {
        // GrapheneOS without sandboxed Play: the package is not there.
        assertEquals(PushAvailability.MISSING_SERVICES, provider(installed = false, config = null).availability())
        val p = provider(installed = false)
        p.start()
        assertEquals(PushAvailability.MISSING_SERVICES, p.availability())
        assertNull(p.token())
        assertTrue("Firebase is not initialised on a phone without them", firebase.log.isEmpty())
        // The package is there but Google Play services say they are not usable.
        firebase.services = PlayServices.MISSING
        assertEquals(PushAvailability.MISSING_SERVICES, provider().availability())
    }

    @Test
    fun outdatedPlayServicesAreSaid() = runTest {
        firebase.services = PlayServices.OUTDATED
        val p = provider()
        assertEquals(PushAvailability.SERVICES_OUTDATED, p.availability())
        assertNull(p.token())
        assertFalse("token" in firebase.log)
    }

    // --- with the options and Google Play services ---

    @Test
    fun availableWithTheOptionsAndFirebaseIsInitialisedOnceByHand() = runTest {
        val p = provider()
        p.start()
        assertEquals(listOf("init fake-project"), firebase.log)
        assertEquals(PushAvailability.AVAILABLE, p.availability())
        assertEquals(PushAvailability.AVAILABLE, p.availability())
        assertEquals("fake-token-1", p.token())
        assertEquals("no token is asked for before the registration needs one", listOf("init fake-project", "token"), firebase.log)
        p.deleteToken()
        assertEquals("delete", firebase.log.last())
    }

    @Test
    fun optionsFirebaseRefusesAreNotAvailableYet() = runTest {
        firebase.accepts = false
        val p = provider()
        p.start()
        assertEquals(PushAvailability.NOT_AVAILABLE_YET, p.availability())
        assertNull(p.token())
        p.deleteToken()
        assertEquals(listOf("init fake-project"), firebase.log)
        assertTrue(later.log.isEmpty())
    }

    @Test
    fun aTokenGoogleDoesNotGiveIsNoToken() = runTest {
        val p = provider()
        firebase.token = null
        assertNull(p.token())
        firebase.hang = true
        assertNull("no answer within 30 s", p.token())
    }

    // --- a wake ---

    @Test
    fun aWakeRunsTheCollectAndNothingElse() = runTest {
        val p = provider()
        var wakes = 0
        p.onWake = {
            wakes++
            true
        }
        assertTrue(p.wake())
        assertEquals(1, wakes)
        assertTrue(later.log.isEmpty())
        assertFalse("a wake asks Google for nothing", "token" in firebase.log)
    }

    @Test
    fun aCollectLongerThanTheMessagesTimeGoesOnAsExpeditedWork() = runTest {
        val p = provider()
        p.onWake = { awaitCancellation() }
        assertFalse(p.wake())
        assertEquals(FcmPushProvider.WAKE_BUDGET_MS, testScheduler.currentTime)
        assertEquals(listOf("enqueue"), later.log)
        // So does one that could not reach the vault.
        p.onWake = { false }
        assertFalse(p.wake())
        assertEquals(listOf("enqueue", "enqueue"), later.log)
        // The continued collect is not continued again.
        assertFalse(p.continueWake(1_000))
        assertEquals(2, later.log.size)
        p.onWake = { true }
        assertTrue(p.continueWake(1_000))
    }

    @Test
    fun aWakeBeforeAnythingListensDoesNothing() = runTest {
        assertTrue(provider().wake())
        assertTrue(later.log.isEmpty())
    }

    // --- a registration's challenge (PUSH-GATEWAY 0.5.0 §6) ---

    @Test
    fun aChallengeGoesToTheRegistrationAndIsNotAWake() = runTest {
        val p = provider()
        var wakes = 0
        val codes = mutableListOf<String>()
        p.onWake = {
            wakes++
            true
        }
        p.onChallenge = { codes += it }
        assertTrue(p.received(mapOf("c" to "AAAAAAAAAAAAAAAAAAAAAA")))
        assertEquals(listOf("AAAAAAAAAAAAAAAAAAAAAA"), codes)
        assertEquals("nothing is collected for a challenge", 0, wakes)
        assertTrue("nor continued", later.log.isEmpty())
        assertFalse("and Google is asked for nothing", "token" in firebase.log)
    }

    @Test
    fun theWakeAndEveryOtherMessageAreWakes() = runTest {
        val p = provider()
        var wakes = 0
        val codes = mutableListOf<String>()
        p.onWake = {
            wakes++
            true
        }
        p.onChallenge = { codes += it }
        assertTrue(p.received(mapOf("w" to "1")))
        assertTrue("`c` with `w` is a wake", p.received(mapOf("c" to "abc", "w" to "1")))
        assertTrue(p.received(emptyMap()))
        assertEquals(3, wakes)
        assertTrue(codes.isEmpty())
    }

    @Test
    fun aChallengeThatIsNotACodeIsDropped() = runTest {
        val p = provider()
        var wakes = 0
        val codes = mutableListOf<String>()
        p.onWake = {
            wakes++
            true
        }
        p.onChallenge = { codes += it }
        for (bad in listOf("", "has space", "pad==", "x".repeat(65), "Ünïcode")) assertTrue(p.received(mapOf("c" to bad)))
        assertEquals("neither a wake", 0, wakes)
        assertTrue("nor a challenge", codes.isEmpty())
        // Before anything listens, a challenge is dropped.
        assertTrue(provider().received(mapOf("c" to "abc")))
    }

    @Test
    fun aRotatedTokenIsAnnouncedWithoutItsValue() {
        val p = provider()
        var rotated = 0
        p.tokenRotated() // nobody listens yet
        p.onNewToken = { rotated++ }
        p.tokenRotated()
        assertEquals(1, rotated)
    }
}
