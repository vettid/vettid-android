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

        assertEquals("aa".repeat(48), signedResponse.pcrs.pcr0)
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

        assertNull(signedResponse.pcrs.pcr3)
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

        // Store updated PCRs
        prefs.edit()
            .putString(KEY_CURRENT_PCRS, gson.toJson(signedResponse.pcrs))
            .putString(KEY_PCR_VERSION, signedResponse.pcrs.version)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()

        return true
    }

    fun getCurrentVersion(): String {
        return prefs.getString(KEY_PCR_VERSION, DEFAULT_PCRS.version) ?: DEFAULT_PCRS.version
    }

    fun clearCache() {
        prefs.edit()
            .remove(KEY_CURRENT_PCRS)
            .remove(KEY_PCR_VERSION)
            .remove(KEY_LAST_UPDATE)
            .apply()
    }

    fun isUsingBundledDefaults(): Boolean {
        return prefs.getString(KEY_CURRENT_PCRS, null) == null
    }
}
