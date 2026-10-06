@file:Suppress("MaxLineLength") // fixtures stay on one line

package com.vettid.core.altchan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup code's QR, App Link and typed form (VAULT-MESSAGING §11.12.1, ENROLLMENT-CODES §3). */
class SetupCodeTest {
    private val api = "https://account.vettid.org"
    private val secret = "AbCdEfGhIjKlMnOpQr_-Uv"

    @Test
    fun theQrIsRedeemedOnlyForThisBuildsOrigin() {
        assertEquals(SetupCodes.Scanned.Secret(secret), SetupCodes.parseScanned("""{"v":1,"t":"e","api":"$api","s":"$secret"}""", api))
        assertEquals(SetupCodes.Scanned.Secret(secret), SetupCodes.parseScanned("""{"v":1,"t":"e","api":"$api","s":"$secret"}""", "$api/"))
        // `api` is compared exactly: another environment, another scheme, a trailing path are all refused.
        for (other in listOf("https://account.staging.vettid.org", "http://account.vettid.org", "https://account.vettid.org/", "https://evil.example")) {
            assertEquals(other, SetupCodes.Scanned.OtherEnvironment(other), SetupCodes.parseScanned("""{"v":1,"t":"e","api":"$other","s":"$secret"}""", api))
        }
    }

    @Test
    fun notASetupQr() {
        val bad = listOf(
            "", "hello", """{"v":2,"t":"e","api":"$api","s":"$secret"}""", """{"v":1,"t":"r","api":"$api","s":"$secret"}""",
            """{"v":1,"t":"e","api":"$api","s":"short"}""", """{"v":1,"t":"e","api":"$api","s":"${secret}x"}""",
            """{"v":1,"t":"e","api":"$api","s":"AbCdEfGhIjKlMnOpQr+/Uv"}""", """{"v":1,"t":"e","s":"$secret"}""",
        )
        for (b in bad) assertEquals(b, SetupCodes.Scanned.NotACode, SetupCodes.parseScanned(b, api))
    }

    @Test
    fun theAppLinkCarriesTheSecretInTheFragmentOnTheOwnHost() {
        assertEquals(secret, SetupCodes.parseLink("https://account.vettid.org/vault/enroll/#s=$secret", api))
        assertNull(SetupCodes.parseLink("https://account.staging.vettid.org/vault/enroll/#s=$secret", api))
        assertNull(SetupCodes.parseLink("https://account.vettid.org/vault/enroll/?s=$secret", api))
        assertNull(SetupCodes.parseLink("https://account.vettid.org/auth/#s=$secret", api))
        assertNull(SetupCodes.parseLink("https://account.vettid.org/vault/enroll/#s=K7QM4XRP", api))
        assertTrue(SetupCodes.isSetupLink("https://account.staging.vettid.org/vault/enroll/#s=$secret"))
        assertFalse(SetupCodes.isSetupLink("https://relay.vettid.org/connect#abc"))
    }

    @Test
    fun typedCodesAreNormalisedAndRefusedBeforeAnythingIsSent() {
        assertEquals("K7QM4XRP", SetupCodes.normalize("k7qm-4xrp"))
        assertEquals("K7QM4XRP", SetupCodes.normalize(" K7QM 4XRP "))
        assertNull(SetupCodes.normalize("K7QM-4XR0")) // 0 is never in a code
        assertNull(SetupCodes.normalize("K7QM-4XRI"))
        assertNull(SetupCodes.normalize("K7QM-4XR"))
        assertNull(SetupCodes.normalize("K7QM-4XRPP"))
        assertTrue(SetupCodes.hasForeignCharacter("abcO"))
        assertTrue(SetupCodes.hasForeignCharacter("ab1"))
        assertFalse(SetupCodes.hasForeignCharacter("k7qm-4x"))
        assertEquals("K7QM-4XRP", SetupCodes.grouped("K7QM4XRP"))
        assertEquals("sam@example.org", SetupCodes.normalizeEmail("  Sam@Example.ORG "))
        assertEquals(31, SetupCodes.ALPHABET.length)
        assertTrue(SetupCodes.ALPHABET.none { it in "01ILO" })
    }
}
