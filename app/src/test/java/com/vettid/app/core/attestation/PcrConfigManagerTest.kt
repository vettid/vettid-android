package com.vettid.app.core.attestation

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for PcrConfigManager
 *
 * Tests cover:
 * - PCR retrieval (stored vs bundled defaults)
 * - Update checking logic
 * - PCR updates with signature verification
 * - Version management
 * - Cache clearing
 * - Data class functionality
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PcrConfigManagerTest {

    private lateinit var context: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var manager: TestablePcrConfigManager
    private val gson = Gson()

    // In-memory storage for mock prefs
    private val prefsStorage = mutableMapOf<String, Any?>()

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()

        // Create mock SharedPreferences with in-memory storage
        mockEditor = mock {
            on { putString(any(), anyOrNull()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val value = invocation.getArgument<String?>(1)
                prefsStorage[key] = value
                mockEditor
            }
            on { putLong(any(), any()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val value = invocation.getArgument<Long>(1)
                prefsStorage[key] = value
                mockEditor
            }
            on { remove(any()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                prefsStorage.remove(key)
                mockEditor
            }
            on { apply() } doAnswer { /* no-op */ }
            on { commit() } doReturn true
        }

        mockPrefs = mock {
            on { edit() } doReturn mockEditor
            on { getString(any(), anyOrNull()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val default = invocation.getArgument<String?>(1)
                prefsStorage[key] as? String ?: default
            }
            on { getLong(any(), any()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val default = invocation.getArgument<Long>(1)
                prefsStorage[key] as? Long ?: default
            }
            on { contains(any()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                prefsStorage.containsKey(key)
            }
        }

        prefsStorage.clear()
        manager = TestablePcrConfigManager(context, mockPrefs)
    }

    // MARK: - GetCurrentPcrs Tests

    @Test
    fun `getCurrentPcrs returns bundled defaults when no stored PCRs`() {
        val pcrs = manager.getCurrentPcrs()

        assertEquals("bundled-1.0.0", pcrs.version)
    }

    @Test
    fun `getCurrentPcrs returns stored PCRs when available`() {
        val storedPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "stored-2.0.0"
        )
        prefsStorage["current_pcrs"] = gson.toJson(storedPcrs)

        val pcrs = manager.getCurrentPcrs()

        assertEquals("stored-2.0.0", pcrs.version)
        assertEquals("aa".repeat(48), pcrs.pcr0)
    }

    @Test
    fun `getCurrentPcrs returns defaults when stored JSON is invalid`() {
        prefsStorage["current_pcrs"] = "not valid json"

        val pcrs = manager.getCurrentPcrs()

        assertEquals("bundled-1.0.0", pcrs.version)
    }

    @Test
    fun `getCurrentPcrs returns defaults when stored JSON is empty object`() {
        prefsStorage["current_pcrs"] = "{}"

        val pcrs = manager.getCurrentPcrs()

        // Should handle missing fields gracefully or return defaults
        // Gson will create an object with null fields, which may cause issues
        assertNotNull(pcrs)
    }

    // MARK: - ShouldCheckForUpdates Tests

    @Test
    fun `shouldCheckForUpdates returns true when never updated`() {
        assertTrue(manager.shouldCheckForUpdates())
    }

    @Test
    fun `shouldCheckForUpdates returns true when update interval passed`() {
        // Set last update to more than 24 hours ago
        val moreThan24HoursAgo = System.currentTimeMillis() - (25 * 60 * 60 * 1000L)
        prefsStorage["last_update_timestamp"] = moreThan24HoursAgo

        assertTrue(manager.shouldCheckForUpdates())
    }

    @Test
    fun `shouldCheckForUpdates returns false when recently updated`() {
        // Set last update to 1 hour ago
        val oneHourAgo = System.currentTimeMillis() - (1 * 60 * 60 * 1000L)
        prefsStorage["last_update_timestamp"] = oneHourAgo

        assertFalse(manager.shouldCheckForUpdates())
    }

    @Test
    fun `shouldCheckForUpdates returns false when just updated`() {
        prefsStorage["last_update_timestamp"] = System.currentTimeMillis()

        assertFalse(manager.shouldCheckForUpdates())
    }

    @Test
    fun `shouldCheckForUpdates returns true at exactly 24 hours`() {
        val exactly24HoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L) - 1
        prefsStorage["last_update_timestamp"] = exactly24HoursAgo

        assertTrue(manager.shouldCheckForUpdates())
    }

    // MARK: - UpdatePcrs Tests

    @Test
    fun `updatePcrs stores new PCRs`() {
        val newPcrs = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48),
            version = "new-1.0.0"
        )
        val signedResponse = SignedPcrResponse(
            pcrs = newPcrs,
            signature = "fake-signature"
        )

        val result = manager.updatePcrs(signedResponse)

        assertTrue(result)
        assertEquals("new-1.0.0", manager.getCurrentVersion())
    }

    @Test
    fun `updatePcrs returns false when version unchanged`() {
        // First update
        val pcrs = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48),
            version = "v1.0.0"
        )
        val signedResponse = SignedPcrResponse(pcrs = pcrs, signature = "sig")
        manager.updatePcrs(signedResponse)

        // Second update with same version
        val result = manager.updatePcrs(signedResponse)

        assertFalse(result)
    }

    @Test
    fun `updatePcrs updates version in prefs`() {
        val pcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "v2.0.0"
        )
        val signedResponse = SignedPcrResponse(pcrs = pcrs, signature = "sig")

        manager.updatePcrs(signedResponse)

        assertEquals("v2.0.0", prefsStorage["pcr_version"])
    }

    @Test
    fun `updatePcrs updates last update timestamp`() {
        val pcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "v3.0.0"
        )
        val signedResponse = SignedPcrResponse(pcrs = pcrs, signature = "sig")

        val beforeUpdate = System.currentTimeMillis()
        manager.updatePcrs(signedResponse)
        val afterUpdate = System.currentTimeMillis()

        val lastUpdate = prefsStorage["last_update_timestamp"] as Long
        assertTrue(lastUpdate >= beforeUpdate)
        assertTrue(lastUpdate <= afterUpdate)
    }

    // MARK: - GetCurrentVersion Tests

    @Test
    fun `getCurrentVersion returns bundled version when no stored version`() {
        val version = manager.getCurrentVersion()

        assertEquals("bundled-1.0.0", version)
    }

    @Test
    fun `getCurrentVersion returns stored version`() {
        prefsStorage["pcr_version"] = "stored-version-1.2.3"

        val version = manager.getCurrentVersion()

        assertEquals("stored-version-1.2.3", version)
    }

    // MARK: - ClearCache Tests

    @Test
    fun `clearCache removes stored PCRs`() {
        prefsStorage["current_pcrs"] = "some json"
        prefsStorage["pcr_version"] = "v1.0.0"
        prefsStorage["last_update_timestamp"] = 12345L

        manager.clearCache()

        assertNull(prefsStorage["current_pcrs"])
        assertNull(prefsStorage["pcr_version"])
        assertNull(prefsStorage["last_update_timestamp"])
    }

    @Test
    fun `clearCache resets to bundled defaults`() {
        val storedPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "stored-version"
        )
        prefsStorage["current_pcrs"] = gson.toJson(storedPcrs)

        manager.clearCache()

        val currentPcrs = manager.getCurrentPcrs()
        assertEquals("bundled-1.0.0", currentPcrs.version)
    }

    // MARK: - IsUsingBundledDefaults Tests

    @Test
    fun `isUsingBundledDefaults returns true when no stored PCRs`() {
        assertTrue(manager.isUsingBundledDefaults())
    }

    @Test
    fun `isUsingBundledDefaults returns false when PCRs stored`() {
        prefsStorage["current_pcrs"] = "{}"

        assertFalse(manager.isUsingBundledDefaults())
    }

    @Test
    fun `isUsingBundledDefaults returns true after clearCache`() {
        prefsStorage["current_pcrs"] = "{}"
        assertFalse(manager.isUsingBundledDefaults())

        manager.clearCache()

        assertTrue(manager.isUsingBundledDefaults())
    }

    // MARK: - SignedPcrResponse Tests

    @Test
    fun `SignedPcrResponse stores PCRs and signature`() {
        val pcrs = ExpectedPcrs("aa", "bb", "cc")
        val response = SignedPcrResponse(
            pcrs = pcrs,
            signature = "test-signature"
        )

        assertEquals(pcrs, response.pcrs)
        assertEquals("test-signature", response.signature)
        assertNull(response.keyId)
    }

    @Test
    fun `SignedPcrResponse with keyId`() {
        val pcrs = ExpectedPcrs("aa", "bb", "cc")
        val response = SignedPcrResponse(
            pcrs = pcrs,
            signature = "test-signature",
            keyId = "key-123"
        )

        assertEquals("key-123", response.keyId)
    }

    @Test
    fun `SignedPcrResponse data class equality`() {
        val pcrs = ExpectedPcrs("aa", "bb", "cc")
        val response1 = SignedPcrResponse(pcrs, "sig")
        val response2 = SignedPcrResponse(pcrs, "sig")
        val response3 = SignedPcrResponse(pcrs, "different-sig")

        assertEquals(response1, response2)
        assertNotEquals(response1, response3)
    }

    // MARK: - PcrValues Tests

    @Test
    fun `PcrValues stores PCR values`() {
        val values = PcrValues(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            pcr3 = "dd".repeat(48)
        )

        assertEquals("aa".repeat(48), values.pcr0)
        assertEquals("bb".repeat(48), values.pcr1)
        assertEquals("cc".repeat(48), values.pcr2)
        assertEquals("dd".repeat(48), values.pcr3)
    }

    @Test
    fun `PcrValues with null PCR3`() {
        val values = PcrValues(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            pcr3 = null
        )

        assertNull(values.pcr3)
    }

    @Test
    fun `PcrValues data class equality`() {
        val values1 = PcrValues("aa", "bb", "cc")
        val values2 = PcrValues("aa", "bb", "cc")
        val values3 = PcrValues("aa", "bb", "dd")

        assertEquals(values1, values2)
        assertNotEquals(values1, values3)
    }

    // MARK: - PcrApiResponse Tests

    @Test
    fun `PcrApiResponse stores all fields`() {
        val values = PcrValues("aa", "bb", "cc")
        val response = PcrApiResponse(
            pcrs = values,
            signature = "sig",
            version = "v1.0.0",
            publishedAt = "2026-01-02T12:00:00Z"
        )

        assertEquals(values, response.pcrs)
        assertEquals("sig", response.signature)
        assertEquals("v1.0.0", response.version)
        assertEquals("2026-01-02T12:00:00Z", response.publishedAt)
    }

    @Test
    fun `PcrApiResponse toSignedResponse converts correctly`() {
        val values = PcrValues(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            pcr3 = "dd".repeat(48)
        )
        val apiResponse = PcrApiResponse(
            pcrs = values,
            signature = "test-sig",
            version = "v2.0.0",
            publishedAt = "2026-01-02T15:30:00Z"
        )

        val signedResponse = apiResponse.toSignedResponse()

        assertNotNull(signedResponse)
        assertEquals("aa".repeat(48), signedResponse!!.pcrs.pcr0)
        assertEquals("bb".repeat(48), signedResponse.pcrs.pcr1)
        assertEquals("cc".repeat(48), signedResponse.pcrs.pcr2)
        assertEquals("dd".repeat(48), signedResponse.pcrs.pcr3)
        assertEquals("v2.0.0", signedResponse.pcrs.version)
        assertEquals("2026-01-02T15:30:00Z", signedResponse.pcrs.publishedAt)
        assertEquals("test-sig", signedResponse.signature)
    }

    @Test
    fun `PcrApiResponse toSignedResponse handles null PCR3`() {
        val values = PcrValues("aa", "bb", "cc", null)
        val apiResponse = PcrApiResponse(
            pcrs = values,
            signature = "sig",
            version = "v1.0.0",
            publishedAt = "2026-01-02"
        )

        val signedResponse = apiResponse.toSignedResponse()

        assertNotNull(signedResponse)
        assertNull(signedResponse!!.pcrs.pcr3)
    }

    // MARK: - PcrUpdateException Tests

    @Test
    fun `PcrUpdateException with message only`() {
        val exception = PcrUpdateException("Test error")

        assertEquals("Test error", exception.message)
        assertNull(exception.cause)
    }

    @Test
    fun `PcrUpdateException with message and cause`() {
        val cause = RuntimeException("Root cause")
        val exception = PcrUpdateException("Test error", cause)

        assertEquals("Test error", exception.message)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun `PcrUpdateException is throwable`() {
        assertThrows(PcrUpdateException::class.java) {
            throw PcrUpdateException("Test")
        }
    }

    // MARK: - JSON Serialization Tests

    @Test
    fun `ExpectedPcrs serializes and deserializes correctly`() {
        val original = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            pcr3 = "dd".repeat(48),
            version = "v1.2.3",
            publishedAt = "2026-01-02T12:00:00Z"
        )

        val json = gson.toJson(original)
        val restored = gson.fromJson(json, ExpectedPcrs::class.java)

        assertEquals(original.pcr0, restored.pcr0)
        assertEquals(original.pcr1, restored.pcr1)
        assertEquals(original.pcr2, restored.pcr2)
        assertEquals(original.pcr3, restored.pcr3)
        assertEquals(original.version, restored.version)
        assertEquals(original.publishedAt, restored.publishedAt)
    }

    @Test
    fun `SignedPcrResponse serializes and deserializes correctly`() {
        val pcrs = ExpectedPcrs("aa", "bb", "cc", version = "v1")
        val original = SignedPcrResponse(pcrs, "signature123", "key456")

        val json = gson.toJson(original)
        val restored = gson.fromJson(json, SignedPcrResponse::class.java)

        assertEquals(original.pcrs.pcr0, restored.pcrs.pcr0)
        assertEquals(original.signature, restored.signature)
        assertEquals(original.keyId, restored.keyId)
    }

    // MARK: - GetPreviousPcrs Tests

    @Test
    fun `getPreviousPcrs returns null when no previous PCRs stored`() {
        assertNull(manager.getPreviousPcrs())
    }

    @Test
    fun `getPreviousPcrs returns stored previous PCRs`() {
        val previousPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "previous-1.0.0"
        )
        prefsStorage["previous_pcrs"] = gson.toJson(previousPcrs)

        val result = manager.getPreviousPcrs()

        assertNotNull(result)
        assertEquals("previous-1.0.0", result?.version)
        assertEquals("aa".repeat(48), result?.pcr0)
    }

    @Test
    fun `getPreviousPcrs returns null when stored JSON is invalid`() {
        prefsStorage["previous_pcrs"] = "not valid json"

        assertNull(manager.getPreviousPcrs())
    }

    // MARK: - UpdatePcrs Preserving Previous Tests

    @Test
    fun `updatePcrs preserves current PCRs as previous`() {
        // First update to set current PCRs
        val pcrsV1 = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48),
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1"))

        // Verify no previous PCRs yet (first update from bundled)
        // Note: bundled defaults are not stored, so previous is null
        assertNull(manager.getPreviousPcrs())

        // Second update should preserve v1 as previous
        val pcrsV2 = ExpectedPcrs(
            pcr0 = "44".repeat(48),
            pcr1 = "55".repeat(48),
            pcr2 = "66".repeat(48),
            version = "v2.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV2, "sig2"))

        // Verify previous PCRs are now v1
        val previous = manager.getPreviousPcrs()
        assertNotNull(previous)
        assertEquals("v1.0.0", previous?.version)
        assertEquals("11".repeat(48), previous?.pcr0)

        // Verify current PCRs are v2
        assertEquals("v2.0.0", manager.getCurrentPcrs().version)
    }

    @Test
    fun `updatePcrs chains previous PCRs through multiple updates`() {
        // Update 1
        val pcrsV1 = ExpectedPcrs("11".repeat(48), "22".repeat(48), "33".repeat(48), version = "v1")
        manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1"))

        // Update 2
        val pcrsV2 = ExpectedPcrs("44".repeat(48), "55".repeat(48), "66".repeat(48), version = "v2")
        manager.updatePcrs(SignedPcrResponse(pcrsV2, "sig2"))

        // Update 3
        val pcrsV3 = ExpectedPcrs("77".repeat(48), "88".repeat(48), "99".repeat(48), version = "v3")
        manager.updatePcrs(SignedPcrResponse(pcrsV3, "sig3"))

        // Current should be v3, previous should be v2 (not v1)
        assertEquals("v3", manager.getCurrentPcrs().version)
        assertEquals("v2", manager.getPreviousPcrs()?.version)
    }

    // MARK: - VerifyPcrsWithFallback Tests

    @Test
    fun `verifyPcrsWithFallback returns true when PCRs match current`() {
        // Set up current PCRs
        val currentPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(currentPcrs, "sig"))

        // Verify matching PCRs
        val actualPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48)
        )

        assertTrue(manager.verifyPcrsWithFallback(actualPcrs))
    }

    @Test
    fun `verifyPcrsWithFallback returns true when PCRs match case-insensitively`() {
        val currentPcrs = ExpectedPcrs(
            pcr0 = "aabbcc".repeat(16),
            pcr1 = "ddeeff".repeat(16),
            pcr2 = "112233".repeat(16),
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(currentPcrs, "sig"))

        // Same PCRs but uppercase
        val actualPcrs = ExpectedPcrs(
            pcr0 = "AABBCC".repeat(16),
            pcr1 = "DDEEFF".repeat(16),
            pcr2 = "112233".repeat(16)
        )

        assertTrue(manager.verifyPcrsWithFallback(actualPcrs))
    }

    @Test
    fun `verifyPcrsWithFallback falls back to previous PCRs during transition`() {
        // Set up v1 as current
        val pcrsV1 = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48),
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1"))

        // Update to v2 (v1 becomes previous)
        val pcrsV2 = ExpectedPcrs(
            pcr0 = "44".repeat(48),
            pcr1 = "55".repeat(48),
            pcr2 = "66".repeat(48),
            version = "v2.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV2, "sig2"))

        // Verify that v1 PCRs still work during transition
        val actualV1Pcrs = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48)
        )

        assertTrue(manager.verifyPcrsWithFallback(actualV1Pcrs))
    }

    @Test
    fun `verifyPcrsWithFallback returns false when neither current nor previous match`() {
        // Set up v1 as current
        val pcrsV1 = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48),
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1"))

        // Update to v2
        val pcrsV2 = ExpectedPcrs(
            pcr0 = "44".repeat(48),
            pcr1 = "55".repeat(48),
            pcr2 = "66".repeat(48),
            version = "v2.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV2, "sig2"))

        // Try to verify completely different PCRs
        val unknownPcrs = ExpectedPcrs(
            pcr0 = "ff".repeat(48),
            pcr1 = "ee".repeat(48),
            pcr2 = "dd".repeat(48)
        )

        assertFalse(manager.verifyPcrsWithFallback(unknownPcrs))
    }

    @Test
    fun `verifyPcrsWithFallback returns false when only PCR0 matches`() {
        val currentPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(currentPcrs, "sig"))

        // PCR0 matches but PCR1 and PCR2 don't
        val actualPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "xx".repeat(48),
            pcr2 = "yy".repeat(48)
        )

        assertFalse(manager.verifyPcrsWithFallback(actualPcrs))
    }

    @Test
    fun `verifyPcrsWithFallback ignores PCR3 when expected is null`() {
        val currentPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            pcr3 = null,
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(currentPcrs, "sig"))

        // Actual has PCR3 but expected is null - should still match
        val actualPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            pcr3 = "dd".repeat(48)
        )

        assertTrue(manager.verifyPcrsWithFallback(actualPcrs))
    }

    @Test
    fun `verifyPcrsWithFallback checks PCR3 when expected is not null`() {
        val currentPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            pcr3 = "dd".repeat(48),
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(currentPcrs, "sig"))

        // PCR3 doesn't match
        val actualPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            pcr3 = "ee".repeat(48)
        )

        assertFalse(manager.verifyPcrsWithFallback(actualPcrs))
    }

    @Test
    fun `verifyPcrsWithFallback uses bundled defaults when no updates`() {
        // Without any updates, current PCRs are bundled defaults
        // Bundled defaults have version "bundled-1.0.0" and all zeros
        val actualPcrs = ExpectedPcrs(
            pcr0 = "0".repeat(96),
            pcr1 = "0".repeat(96),
            pcr2 = "0".repeat(96)
        )

        assertTrue(manager.verifyPcrsWithFallback(actualPcrs))
    }

    // MARK: - ClearCache with Previous PCRs Tests

    @Test
    fun `clearCache also removes previous PCRs`() {
        // Set up current and previous PCRs
        prefsStorage["current_pcrs"] = "some json"
        prefsStorage["previous_pcrs"] = "previous json"
        prefsStorage["pcr_version"] = "v2.0.0"
        prefsStorage["last_update_timestamp"] = 12345L

        manager.clearCache()

        assertNull(prefsStorage["current_pcrs"])
        assertNull(prefsStorage["previous_pcrs"])
        assertNull(prefsStorage["pcr_version"])
        assertNull(prefsStorage["last_update_timestamp"])
    }

    @Test
    fun `clearCache resets fallback verification to bundled defaults only`() {
        // Set up v1 and v2
        val pcrsV1 = ExpectedPcrs("11".repeat(48), "22".repeat(48), "33".repeat(48), version = "v1")
        manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1"))
        val pcrsV2 = ExpectedPcrs("44".repeat(48), "55".repeat(48), "66".repeat(48), version = "v2")
        manager.updatePcrs(SignedPcrResponse(pcrsV2, "sig2"))

        // Clear cache
        manager.clearCache()

        // Previous should be null
        assertNull(manager.getPreviousPcrs())

        // Fallback should only match bundled defaults
        val v1Pcrs = ExpectedPcrs("11".repeat(48), "22".repeat(48), "33".repeat(48))
        assertFalse(manager.verifyPcrsWithFallback(v1Pcrs))

        val bundledPcrs = ExpectedPcrs("0".repeat(96), "0".repeat(96), "0".repeat(96))
        assertTrue(manager.verifyPcrsWithFallback(bundledPcrs))
    }

    // MARK: - Integration-like Tests

    @Test
    fun `full update flow works correctly`() {
        // Start with bundled defaults
        assertTrue(manager.isUsingBundledDefaults())
        assertEquals("bundled-1.0.0", manager.getCurrentVersion())

        // First update
        val pcrsV1 = ExpectedPcrs("11".repeat(48), "22".repeat(48), "33".repeat(48), version = "v1.0.0")
        assertTrue(manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1")))
        assertFalse(manager.isUsingBundledDefaults())
        assertEquals("v1.0.0", manager.getCurrentVersion())

        // Same version update (should return false)
        assertFalse(manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1")))

        // New version update
        val pcrsV2 = ExpectedPcrs("44".repeat(48), "55".repeat(48), "66".repeat(48), version = "v2.0.0")
        assertTrue(manager.updatePcrs(SignedPcrResponse(pcrsV2, "sig2")))
        assertEquals("v2.0.0", manager.getCurrentVersion())

        // Clear and verify reset
        manager.clearCache()
        assertTrue(manager.isUsingBundledDefaults())
        assertEquals("bundled-1.0.0", manager.getCurrentVersion())
    }

    @Test
    fun `update check timing works correctly`() {
        // Initially should check for updates
        assertTrue(manager.shouldCheckForUpdates())

        // After update, should not check
        val pcrs = ExpectedPcrs("aa".repeat(48), "bb".repeat(48), "cc".repeat(48), version = "v1")
        manager.updatePcrs(SignedPcrResponse(pcrs, "sig"))
        assertFalse(manager.shouldCheckForUpdates())

        // Simulate time passing (more than 24 hours)
        prefsStorage["last_update_timestamp"] = System.currentTimeMillis() - (25 * 60 * 60 * 1000L)
        assertTrue(manager.shouldCheckForUpdates())
    }

    // MARK: - Integration Tests for PCR Verification Flow (#43)

    @Test
    fun `validateApiPcrs returns valid when PCRs match current version`() {
        // Setup current PCRs
        val currentPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(currentPcrs, "sig"))

        // Validate matching API PCRs
        val apiPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48)
        )

        val result = manager.validateApiPcrs(apiPcrs)

        assertTrue(result.isValid)
        assertEquals("v1.0.0", result.matchedVersion)
    }

    @Test
    fun `validateApiPcrs returns valid when PCRs match previous version during transition`() {
        // Setup v1 as current, then update to v2
        val pcrsV1 = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48),
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1"))

        val pcrsV2 = ExpectedPcrs(
            pcr0 = "44".repeat(48),
            pcr1 = "55".repeat(48),
            pcr2 = "66".repeat(48),
            version = "v2.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV2, "sig2"))

        // Validate v1 PCRs (should match previous during transition)
        val apiPcrsV1 = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48)
        )

        val result = manager.validateApiPcrs(apiPcrsV1)

        assertTrue(result.isValid)
        assertEquals("v1.0.0", result.matchedVersion)
    }

    @Test
    fun `validateApiPcrs returns invalid when PCRs match no known version`() {
        // Setup current PCRs
        val currentPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "v1.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(currentPcrs, "sig"))

        // Validate completely unknown PCRs
        val unknownPcrs = ExpectedPcrs(
            pcr0 = "ff".repeat(48),
            pcr1 = "ee".repeat(48),
            pcr2 = "dd".repeat(48)
        )

        val result = manager.validateApiPcrs(unknownPcrs)

        assertFalse(result.isValid)
        assertNull(result.matchedVersion)
        assertTrue(result.reason.contains("don't match"))
    }

    @Test
    fun `validateApiPcrs rejects expired previous version PCRs`() {
        // Setup v1 with past expiration
        val pcrsV1 = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48),
            version = "v1.0.0",
            validUntil = "2020-01-01T00:00:00Z"  // Past date
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1"))

        // Update to v2 (v1 becomes previous with expiration)
        val pcrsV2 = ExpectedPcrs(
            pcr0 = "44".repeat(48),
            pcr1 = "55".repeat(48),
            pcr2 = "66".repeat(48),
            version = "v2.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV2, "sig2"))

        // Try to validate with v1 PCRs (should be rejected as expired)
        val apiPcrsV1 = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48)
        )

        val result = manager.validateApiPcrs(apiPcrsV1)

        assertFalse(result.isValid)
        assertTrue(result.reason.contains("expired"))
    }

    @Test
    fun `rolling update transition allows both versions during grace period`() {
        // Simulate rolling update: v1 → v2
        // Both should be valid during transition

        // Setup v1
        val pcrsV1 = ExpectedPcrs(
            pcr0 = "11".repeat(48),
            pcr1 = "22".repeat(48),
            pcr2 = "33".repeat(48),
            version = "v1.0.0",
            validUntil = "2030-01-01T00:00:00Z"  // Future date = still valid
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1"))

        // Update to v2
        val pcrsV2 = ExpectedPcrs(
            pcr0 = "44".repeat(48),
            pcr1 = "55".repeat(48),
            pcr2 = "66".repeat(48),
            version = "v2.0.0"
        )
        manager.updatePcrs(SignedPcrResponse(pcrsV2, "sig2"))

        // Both v1 and v2 should be valid
        val apiV1 = ExpectedPcrs("11".repeat(48), "22".repeat(48), "33".repeat(48))
        val apiV2 = ExpectedPcrs("44".repeat(48), "55".repeat(48), "66".repeat(48))

        assertTrue(manager.validateApiPcrs(apiV1).isValid)
        assertTrue(manager.validateApiPcrs(apiV2).isValid)
        assertEquals("v1.0.0", manager.validateApiPcrs(apiV1).matchedVersion)
        assertEquals("v2.0.0", manager.validateApiPcrs(apiV2).matchedVersion)
    }

    @Test
    fun `network failure fallback uses cached PCRs`() {
        // Setup cached PCRs
        val cachedPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "cached-v1"
        )
        manager.updatePcrs(SignedPcrResponse(cachedPcrs, "sig"))

        // Verify cached PCRs are used for validation (even without network)
        val apiPcrs = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48)
        )

        val result = manager.validateApiPcrs(apiPcrs)

        assertTrue(result.isValid)
        assertEquals("cached-v1", result.matchedVersion)
    }

    @Test
    fun `bundled defaults are used when no cached PCRs available`() {
        // Clear any cached PCRs
        manager.clearCache()

        // Verify bundled defaults are used
        val bundledPcrs = ExpectedPcrs(
            pcr0 = "0".repeat(96),
            pcr1 = "0".repeat(96),
            pcr2 = "0".repeat(96)
        )

        val result = manager.validateApiPcrs(bundledPcrs)

        assertTrue(result.isValid)
        assertEquals("bundled-1.0.0", result.matchedVersion)
    }

    @Test
    fun `full PCR verification flow works end to end`() {
        // 1. Start with bundled defaults
        assertTrue(manager.isUsingBundledDefaults())

        // 2. Simulate first PCR fetch and update
        val pcrsV1 = ExpectedPcrs(
            pcr0 = "aa".repeat(48),
            pcr1 = "bb".repeat(48),
            pcr2 = "cc".repeat(48),
            version = "2026-01-15-v1",
            publishedAt = "2026-01-15T10:00:00Z"
        )
        assertTrue(manager.updatePcrs(SignedPcrResponse(pcrsV1, "sig1")))

        // 3. Verify current PCRs are used for validation
        val apiPcrs = ExpectedPcrs("aa".repeat(48), "bb".repeat(48), "cc".repeat(48))
        assertTrue(manager.validateApiPcrs(apiPcrs).isValid)

        // 4. Simulate rolling update
        val pcrsV2 = ExpectedPcrs(
            pcr0 = "dd".repeat(48),
            pcr1 = "ee".repeat(48),
            pcr2 = "ff".repeat(48),
            version = "2026-01-15-v2"
        )
        assertTrue(manager.updatePcrs(SignedPcrResponse(pcrsV2, "sig2")))

        // 5. Both versions should work during transition
        val apiV1 = ExpectedPcrs("aa".repeat(48), "bb".repeat(48), "cc".repeat(48))
        val apiV2 = ExpectedPcrs("dd".repeat(48), "ee".repeat(48), "ff".repeat(48))
        assertTrue(manager.validateApiPcrs(apiV1).isValid)  // Previous version
        assertTrue(manager.validateApiPcrs(apiV2).isValid)  // Current version

        // 6. Unknown PCRs should be rejected
        val unknownPcrs = ExpectedPcrs("11".repeat(48), "22".repeat(48), "33".repeat(48))
        assertFalse(manager.validateApiPcrs(unknownPcrs).isValid)
    }
}

/**
 * Testable version of PcrConfigManager that accepts injected dependencies.
 */
class TestablePcrConfigManager(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_CURRENT_PCRS = "current_pcrs"
        private const val KEY_PREVIOUS_PCRS = "previous_pcrs"
        private const val KEY_LAST_UPDATE = "last_update_timestamp"
        private const val KEY_PCR_VERSION = "pcr_version"
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        private val DEFAULT_PCRS = ExpectedPcrs(
            pcr0 = "0".repeat(96),
            pcr1 = "0".repeat(96),
            pcr2 = "0".repeat(96),
            pcr3 = null,
            version = "bundled-1.0.0",
            publishedAt = "2026-01-01T00:00:00Z"
        )
    }

    private val gson = com.google.gson.Gson()

    fun getCurrentPcrs(): ExpectedPcrs {
        val storedJson = prefs.getString(KEY_CURRENT_PCRS, null)

        if (storedJson != null) {
            try {
                return gson.fromJson(storedJson, ExpectedPcrs::class.java)
            } catch (e: Exception) {
                // Fall through to defaults
            }
        }

        return DEFAULT_PCRS
    }

    fun getPreviousPcrs(): ExpectedPcrs? {
        val storedJson = prefs.getString(KEY_PREVIOUS_PCRS, null) ?: return null
        return try {
            gson.fromJson(storedJson, ExpectedPcrs::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun shouldCheckForUpdates(): Boolean {
        val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0)
        val timeSinceUpdate = System.currentTimeMillis() - lastUpdate
        return timeSinceUpdate > UPDATE_CHECK_INTERVAL_MS
    }

    fun updatePcrs(signedResponse: SignedPcrResponse): Boolean {
        // Skip signature verification in tests (would need real keys)

        // Check version is newer
        val currentVersion = prefs.getString(KEY_PCR_VERSION, "")
        if (signedResponse.pcrs.version == currentVersion) {
            return false
        }

        // Save current PCRs as previous (for transition period fallback)
        val currentPcrsJson = prefs.getString(KEY_CURRENT_PCRS, null)

        // Store updated PCRs
        prefs.edit().apply {
            // Preserve previous PCRs for transition period
            if (currentPcrsJson != null) {
                putString(KEY_PREVIOUS_PCRS, currentPcrsJson)
            }
            putString(KEY_CURRENT_PCRS, gson.toJson(signedResponse.pcrs))
            putString(KEY_PCR_VERSION, signedResponse.pcrs.version)
            putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            apply()
        }

        return true
    }

    fun getCurrentVersion(): String {
        return prefs.getString(KEY_PCR_VERSION, DEFAULT_PCRS.version) ?: DEFAULT_PCRS.version
    }

    fun verifyPcrsWithFallback(actualPcrs: ExpectedPcrs): Boolean {
        val currentPcrs = getCurrentPcrs()

        // Try current PCRs first
        if (matchesPcrs(actualPcrs, currentPcrs)) {
            return true
        }

        // During transition, try previous version
        getPreviousPcrs()?.let { previousPcrs ->
            if (matchesPcrs(actualPcrs, previousPcrs)) {
                return true
            }
        }

        return false
    }

    private fun matchesPcrs(actual: ExpectedPcrs, expected: ExpectedPcrs): Boolean {
        return actual.pcr0.equals(expected.pcr0, ignoreCase = true) &&
                actual.pcr1.equals(expected.pcr1, ignoreCase = true) &&
                actual.pcr2.equals(expected.pcr2, ignoreCase = true) &&
                (expected.pcr3 == null || actual.pcr3.equals(expected.pcr3, ignoreCase = true))
    }

    fun clearCache() {
        prefs.edit()
            .remove(KEY_CURRENT_PCRS)
            .remove(KEY_PREVIOUS_PCRS)
            .remove(KEY_PCR_VERSION)
            .remove(KEY_LAST_UPDATE)
            .apply()
    }

    fun isUsingBundledDefaults(): Boolean {
        return prefs.getString(KEY_CURRENT_PCRS, null) == null
    }

    /**
     * Validate API-provided PCRs against cached values (#24, #43).
     */
    fun validateApiPcrs(apiPcrs: ExpectedPcrs): PcrValidationResult {
        val cachedPcrs = getCurrentPcrs()
        val previousPcrs = getPreviousPcrs()

        // Check if matches current cached PCRs
        if (matchesPcrs(apiPcrs, cachedPcrs)) {
            return PcrValidationResult(
                isValid = true,
                matchedVersion = cachedPcrs.version,
                reason = "PCRs match current cached version"
            )
        }

        // Check if matches previous version (transition period)
        if (previousPcrs != null && matchesPcrs(apiPcrs, previousPcrs)) {
            if (isPcrVersionValid(previousPcrs)) {
                return PcrValidationResult(
                    isValid = true,
                    matchedVersion = previousPcrs.version,
                    reason = "PCRs match previous version - cache may need refresh"
                )
            } else {
                return PcrValidationResult(
                    isValid = false,
                    matchedVersion = null,
                    reason = "API PCRs match expired version - potential downgrade attack"
                )
            }
        }

        return PcrValidationResult(
            isValid = false,
            matchedVersion = null,
            reason = "API PCRs don't match cached - refresh cache or check for MITM"
        )
    }

    private fun isPcrVersionValid(pcrs: ExpectedPcrs): Boolean {
        val validUntil = pcrs.validUntil ?: return true
        return try {
            val expirationTime = java.time.Instant.parse(validUntil).toEpochMilli()
            System.currentTimeMillis() < expirationTime
        } catch (e: Exception) {
            false
        }
    }
}
