package com.vettid.app.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vettid.app.core.network.LegacyCredentialPackage
import com.vettid.app.core.network.LegacyLedgerAuthToken
import com.vettid.app.core.network.TransactionKeyInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for CredentialStore
 *
 * These tests require EncryptedSharedPreferences which needs Android KeyStore.
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var credentialStore: CredentialStore

    private val testCredentialPackage = LegacyCredentialPackage(
        userGuid = "test-user-guid-12345",
        encryptedBlob = "dGVzdC1lbmNyeXB0ZWQtYmxvYg==",
        cekVersion = 1,
        ledgerAuthToken = LegacyLedgerAuthToken(
            latId = "lat-id-123",
            token = "abcdef123456789",
            version = 1
        ),
        transactionKeys = listOf(
            TransactionKeyInfo(
                keyId = "tk-1",
                publicKey = "dGVzdC1wdWJsaWMta2V5LTE=",
                algorithm = "X25519"
            ),
            TransactionKeyInfo(
                keyId = "tk-2",
                publicKey = "dGVzdC1wdWJsaWMta2V5LTI=",
                algorithm = "X25519"
            ),
            TransactionKeyInfo(
                keyId = "tk-3",
                publicKey = "dGVzdC1wdWJsaWMta2V5LTM=",
                algorithm = "X25519"
            )
        )
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        credentialStore = CredentialStore(context)
        credentialStore.clearAll()
    }

    // MARK: - Credential Storage Tests

    @Test
    fun storeCredentialPackage_storesAllFields() {
        credentialStore.storeCredentialPackage(testCredentialPackage, "test-salt-base64")

        val stored = credentialStore.getStoredCredential()

        assertNotNull("Stored credential should not be null", stored)
        assertEquals("User GUID should match", testCredentialPackage.userGuid, stored?.userGuid)
        assertEquals("Encrypted blob should match", testCredentialPackage.encryptedBlob, stored?.encryptedBlob)
        assertEquals("CEK version should match", testCredentialPackage.cekVersion, stored?.cekVersion)
    }

    @Test
    fun hasStoredCredential_returnsFalseWhenEmpty() {
        assertFalse("Should return false when no credential stored", credentialStore.hasStoredCredential())
    }

    @Test
    fun hasStoredCredential_returnsTrueAfterStoring() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        assertTrue("Should return true after storing credential", credentialStore.hasStoredCredential())
    }

    @Test
    fun getUserGuid_returnsCorrectGUID() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        assertEquals(testCredentialPackage.userGuid, credentialStore.getUserGuid())
    }

    @Test
    fun getEncryptedBlob_returnsCorrectBlob() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        assertEquals(testCredentialPackage.encryptedBlob, credentialStore.getEncryptedBlob())
    }

    @Test
    fun getCekVersion_returnsCorrectVersion() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        assertEquals(testCredentialPackage.cekVersion, credentialStore.getCekVersion())
    }

    // MARK: - LAT Storage Tests

    @Test
    fun lat_isStoredCorrectly() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val storedToken = credentialStore.getStoredLatToken()

        assertEquals(testCredentialPackage.ledgerAuthToken.token, storedToken)
    }

    @Test
    fun verifyLat_returnsTrueForMatchingLAT() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val matchingLat = LegacyLedgerAuthToken(
            latId = "lat-id-123",
            token = "abcdef123456789",
            version = 1
        )

        assertTrue("Should verify matching LAT", credentialStore.verifyLat(matchingLat))
    }

    @Test
    fun verifyLat_returnsFalseForDifferentToken() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val differentLat = LegacyLedgerAuthToken(
            latId = "lat-id-123",
            token = "different-token",
            version = 1
        )

        assertFalse("Should reject different token", credentialStore.verifyLat(differentLat))
    }

    @Test
    fun verifyLat_returnsFalseForDifferentLatId() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val differentLat = LegacyLedgerAuthToken(
            latId = "different-lat-id",
            token = "abcdef123456789",
            version = 1
        )

        assertFalse("Should reject different LAT ID", credentialStore.verifyLat(differentLat))
    }

    @Test
    fun updateLat_updatesTokenCorrectly() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val newLat = LegacyLedgerAuthToken(
            latId = "new-lat-id",
            token = "new-token-value",
            version = 2
        )

        credentialStore.updateLat(newLat)

        assertTrue("Should verify new LAT after update", credentialStore.verifyLat(newLat))
    }

    // MARK: - UTK Pool Tests

    @Test
    fun utkPool_isStoredCorrectly() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val pool = credentialStore.getUtkPool()

        assertEquals("UTK pool should have 3 keys", 3, pool.size)
    }

    @Test
    fun getUtk_returnsCorrectKeyById() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val key = credentialStore.getUtk("tk-2")

        assertNotNull("Should find key by ID", key)
        assertEquals("tk-2", key?.keyId)
        assertEquals("dGVzdC1wdWJsaWMta2V5LTI=", key?.publicKey)
    }

    @Test
    fun getUtk_returnsNullForNonExistentKey() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val key = credentialStore.getUtk("non-existent-key")

        assertNull("Should return null for non-existent key", key)
    }

    @Test
    fun removeUtk_removesKeyFromPool() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        credentialStore.removeUtk("tk-1")

        val pool = credentialStore.getUtkPool()
        assertEquals("Pool should have 2 keys after removal", 2, pool.size)
        assertNull("Removed key should not be found", credentialStore.getUtk("tk-1"))
    }

    @Test
    fun addUtks_addsNewKeysToPool() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val newKeys = listOf(
            TransactionKeyInfo(
                keyId = "tk-4",
                publicKey = "dGVzdC1wdWJsaWMta2V5LTQ=",
                algorithm = "X25519"
            ),
            TransactionKeyInfo(
                keyId = "tk-5",
                publicKey = "dGVzdC1wdWJsaWMta2V5LTU=",
                algorithm = "X25519"
            )
        )

        credentialStore.addUtks(newKeys)

        val pool = credentialStore.getUtkPool()
        assertEquals("Pool should have 5 keys after adding", 5, pool.size)
        assertNotNull("New key tk-4 should be found", credentialStore.getUtk("tk-4"))
        assertNotNull("New key tk-5 should be found", credentialStore.getUtk("tk-5"))
    }

    @Test
    fun getUtkCount_returnsCorrectCount() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        assertEquals(3, credentialStore.getUtkCount())

        credentialStore.removeUtk("tk-1")

        assertEquals(2, credentialStore.getUtkCount())
    }

    // MARK: - Password Salt Tests

    @Test
    fun passwordSalt_isStoredDuringEnrollment() {
        credentialStore.storeCredentialPackage(testCredentialPackage, "test-salt-base64")

        assertEquals("test-salt-base64", credentialStore.getPasswordSalt())
    }

    @Test
    fun setPasswordSalt_updatesSalt() {
        credentialStore.setPasswordSalt("new-salt-value")

        assertEquals("new-salt-value", credentialStore.getPasswordSalt())
    }

    // MARK: - Clear Tests

    @Test
    fun clearAll_removesAllData() {
        credentialStore.storeCredentialPackage(testCredentialPackage, "test-salt")

        credentialStore.clearAll()

        assertFalse("Should have no credential after clear", credentialStore.hasStoredCredential())
        assertNull("User GUID should be null after clear", credentialStore.getUserGuid())
        assertNull("LAT token should be null after clear", credentialStore.getStoredLatToken())
        assertEquals("UTK pool should be empty after clear", 0, credentialStore.getUtkCount())
        assertNull("Password salt should be null after clear", credentialStore.getPasswordSalt())
    }

    // MARK: - Timestamp Tests

    @Test
    fun createdAt_isSetOnFirstStore() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val stored = credentialStore.getStoredCredential()

        assertTrue("Created at should be set", (stored?.createdAt ?: 0) > 0)
    }

    @Test
    fun lastUsedAt_isUpdatedOnStore() {
        credentialStore.storeCredentialPackage(testCredentialPackage)

        val stored = credentialStore.getStoredCredential()

        assertTrue("Last used at should be set", (stored?.lastUsedAt ?: 0) > 0)
    }
}
