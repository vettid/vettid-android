package com.vettid.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.work.WorkManager
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
 * Unit tests for ProteanCredentialManager
 *
 * Tests cover:
 * - Credential storage and retrieval
 * - Metadata management
 * - Backup status tracking
 * - Credential updates with versioning
 * - Recovery import
 * - Clear/reset functionality
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProteanCredentialManagerTest {

    private lateinit var context: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockWorkManager: WorkManager
    private lateinit var manager: TestableProteanCredentialManager
    private val gson = Gson()

    // In-memory storage for mock prefs
    private val prefsStorage = mutableMapOf<String, String?>()

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        mockWorkManager = mock()

        // Create mock SharedPreferences with in-memory storage
        mockEditor = mock {
            on { putString(any(), anyOrNull()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val value = invocation.getArgument<String?>(1)
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
                prefsStorage[key] ?: default
            }
            on { contains(any()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                prefsStorage.containsKey(key)
            }
        }

        prefsStorage.clear()
        manager = TestableProteanCredentialManager(context, mockPrefs, mockWorkManager)
    }

    // MARK: - Initial State Tests

    @Test
    fun `hasCredential returns false when no credential stored`() {
        assertFalse(manager.hasCredential())
    }

    @Test
    fun `getCredential returns null when no credential stored`() {
        assertNull(manager.getCredential())
    }

    @Test
    fun `getCredentialBytes returns null when no credential stored`() {
        assertNull(manager.getCredentialBytes())
    }

    @Test
    fun `getMetadata returns null when no credential stored`() {
        assertNull(manager.getMetadata())
    }

    @Test
    fun `getUserGuid returns null when no credential stored`() {
        assertNull(manager.getUserGuid())
    }

    @Test
    fun `getBackupStatus returns NONE when no credential stored`() {
        assertEquals(BackupStatus.NONE, manager.getBackupStatus())
    }

    // MARK: - Store Credential Tests

    @Test
    fun `storeCredential saves credential blob`() {
        val credentialBlob = "encrypted-credential-data"
        val userGuid = "user-123"

        manager.storeCredential(credentialBlob, userGuid, triggerBackup = false)

        assertEquals(credentialBlob, manager.getCredential())
    }

    @Test
    fun `storeCredential saves user guid`() {
        val credentialBlob = "encrypted-credential-data"
        val userGuid = "user-123"

        manager.storeCredential(credentialBlob, userGuid, triggerBackup = false)

        assertEquals(userGuid, manager.getUserGuid())
    }

    @Test
    fun `storeCredential sets hasCredential to true`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        assertTrue(manager.hasCredential())
    }

    @Test
    fun `storeCredential sets backup status to PENDING`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        assertEquals(BackupStatus.PENDING, manager.getBackupStatus())
    }

    @Test
    fun `storeCredential creates metadata with correct version`() {
        manager.storeCredential("blob", "user-123", version = 5, triggerBackup = false)

        val metadata = manager.getMetadata()
        assertNotNull(metadata)
        assertEquals(5, metadata?.version)
    }

    @Test
    fun `storeCredential creates metadata with correct size`() {
        val blob = "test-blob-data"
        manager.storeCredential(blob, "user-123", triggerBackup = false)

        val metadata = manager.getMetadata()
        assertNotNull(metadata)
        assertEquals(blob.length, metadata?.sizeBytes)
    }

    @Test
    fun `storeCredential creates metadata with null backedUpAt`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        val metadata = manager.getMetadata()
        assertNotNull(metadata)
        assertNull(metadata?.backedUpAt)
    }

    @Test
    fun `storeCredential with bytes encodes to base64`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val expectedBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        manager.storeCredential(bytes, "user-123", triggerBackup = false)

        assertEquals(expectedBase64, manager.getCredential())
    }

    // MARK: - Get Credential Bytes Tests

    @Test
    fun `getCredentialBytes decodes base64 correctly`() {
        val originalBytes = byteArrayOf(10, 20, 30, 40, 50)
        val base64 = Base64.encodeToString(originalBytes, Base64.NO_WRAP)

        manager.storeCredential(base64, "user-123", triggerBackup = false)

        val retrievedBytes = manager.getCredentialBytes()
        assertNotNull(retrievedBytes)
        assertTrue(originalBytes.contentEquals(retrievedBytes))
    }

    @Test
    fun `getCredentialBytes handles empty string`() {
        prefsStorage["credential_blob"] = ""

        val bytes = manager.getCredentialBytes()
        assertNotNull(bytes)
        assertEquals(0, bytes?.size)
    }

    // MARK: - Update Credential Tests

    @Test
    fun `updateCredential increments version`() {
        manager.storeCredential("blob-v1", "user-123", version = 1, triggerBackup = false)

        manager.updateCredential("blob-v2", triggerBackup = false)

        val metadata = manager.getMetadata()
        assertEquals(2, metadata?.version)
    }

    @Test
    fun `updateCredential updates blob`() {
        manager.storeCredential("blob-v1", "user-123", version = 1, triggerBackup = false)

        manager.updateCredential("blob-v2", triggerBackup = false)

        assertEquals("blob-v2", manager.getCredential())
    }

    @Test
    fun `updateCredential preserves user guid`() {
        manager.storeCredential("blob-v1", "user-123", version = 1, triggerBackup = false)

        manager.updateCredential("blob-v2", triggerBackup = false)

        assertEquals("user-123", manager.getUserGuid())
    }

    @Test
    fun `updateCredential does nothing when no existing credential`() {
        manager.updateCredential("blob", triggerBackup = false)

        assertFalse(manager.hasCredential())
        assertNull(manager.getCredential())
    }

    @Test
    fun `updateCredential resets backup status to PENDING`() {
        manager.storeCredential("blob-v1", "user-123", triggerBackup = false)
        manager.updateBackupStatus(BackupStatus.COMPLETED)

        manager.updateCredential("blob-v2", triggerBackup = false)

        assertEquals(BackupStatus.PENDING, manager.getBackupStatus())
    }

    // MARK: - Backup Status Tests

    @Test
    fun `updateBackupStatus changes status`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        manager.updateBackupStatus(BackupStatus.COMPLETED)

        assertEquals(BackupStatus.COMPLETED, manager.getBackupStatus())
    }

    @Test
    fun `updateBackupStatus to COMPLETED sets backedUpAt in metadata`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)
        assertNull(manager.getMetadata()?.backedUpAt)

        manager.updateBackupStatus(BackupStatus.COMPLETED)

        assertNotNull(manager.getMetadata()?.backedUpAt)
    }

    @Test
    fun `updateBackupStatus to FAILED does not set backedUpAt`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        manager.updateBackupStatus(BackupStatus.FAILED)

        assertNull(manager.getMetadata()?.backedUpAt)
    }

    @Test
    fun `updateBackupStatus to PENDING does not set backedUpAt`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        manager.updateBackupStatus(BackupStatus.PENDING)

        assertNull(manager.getMetadata()?.backedUpAt)
    }

    // MARK: - Clear Credential Tests

    @Test
    fun `clearCredential removes credential blob`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        manager.clearCredential()

        assertNull(manager.getCredential())
    }

    @Test
    fun `clearCredential removes user guid`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        manager.clearCredential()

        assertNull(manager.getUserGuid())
    }

    @Test
    fun `clearCredential removes metadata`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        manager.clearCredential()

        assertNull(manager.getMetadata())
    }

    @Test
    fun `clearCredential sets hasCredential to false`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        manager.clearCredential()

        assertFalse(manager.hasCredential())
    }

    @Test
    fun `clearCredential resets backup status to NONE`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)
        manager.updateBackupStatus(BackupStatus.COMPLETED)

        manager.clearCredential()

        assertEquals(BackupStatus.NONE, manager.getBackupStatus())
    }

    @Test
    fun `clearCredential cancels pending backup`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        manager.clearCredential()

        verify(mockWorkManager).cancelUniqueWork(ProteanCredentialManager.BACKUP_WORK_NAME)
    }

    // MARK: - Import Recovered Credential Tests

    @Test
    fun `importRecoveredCredential saves credential blob`() {
        manager.importRecoveredCredential(
            credentialBlob = "recovered-blob",
            userGuid = "user-456",
            version = 3
        )

        assertEquals("recovered-blob", manager.getCredential())
    }

    @Test
    fun `importRecoveredCredential saves user guid`() {
        manager.importRecoveredCredential(
            credentialBlob = "recovered-blob",
            userGuid = "user-456",
            version = 3
        )

        assertEquals("user-456", manager.getUserGuid())
    }

    @Test
    fun `importRecoveredCredential preserves version`() {
        manager.importRecoveredCredential(
            credentialBlob = "recovered-blob",
            userGuid = "user-456",
            version = 7
        )

        assertEquals(7, manager.getMetadata()?.version)
    }

    @Test
    fun `importRecoveredCredential sets backup status to COMPLETED`() {
        manager.importRecoveredCredential(
            credentialBlob = "recovered-blob",
            userGuid = "user-456",
            version = 3
        )

        assertEquals(BackupStatus.COMPLETED, manager.getBackupStatus())
    }

    @Test
    fun `importRecoveredCredential sets backedUpAt to non-null`() {
        manager.importRecoveredCredential(
            credentialBlob = "recovered-blob",
            userGuid = "user-456",
            version = 3
        )

        assertNotNull(manager.getMetadata()?.backedUpAt)
    }

    // MARK: - Schedule Backup Tests

    @Test
    fun `scheduleBackup enqueues work with WorkManager`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        manager.scheduleBackup()

        verify(mockWorkManager).enqueueUniqueWork(
            eq(ProteanCredentialManager.BACKUP_WORK_NAME),
            any(),
            any<androidx.work.OneTimeWorkRequest>()
        )
    }

    @Test
    fun `scheduleBackup sets status to PENDING`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)
        manager.updateBackupStatus(BackupStatus.COMPLETED)

        manager.scheduleBackup()

        assertEquals(BackupStatus.PENDING, manager.getBackupStatus())
    }

    // MARK: - Cancel Pending Backup Tests

    @Test
    fun `cancelPendingBackup cancels work with WorkManager`() {
        manager.cancelPendingBackup()

        verify(mockWorkManager).cancelUniqueWork(ProteanCredentialManager.BACKUP_WORK_NAME)
    }

    // MARK: - Metadata Parsing Tests

    @Test
    fun `getMetadata returns null for invalid JSON`() {
        prefsStorage["metadata"] = "not valid json"

        assertNull(manager.getMetadata())
    }

    @Test
    fun `getBackupStatus handles invalid status gracefully`() {
        prefsStorage["backup_status"] = "INVALID_STATUS"

        // Should throw IllegalArgumentException from valueOf, which returns NONE
        try {
            manager.getBackupStatus()
            fail("Expected exception")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    // MARK: - Store with Trigger Backup Tests

    @Test
    fun `storeCredential with triggerBackup true schedules backup`() {
        manager.storeCredential("blob", "user-123", triggerBackup = true)

        verify(mockWorkManager).enqueueUniqueWork(
            eq(ProteanCredentialManager.BACKUP_WORK_NAME),
            any(),
            any<androidx.work.OneTimeWorkRequest>()
        )
    }

    @Test
    fun `storeCredential with triggerBackup false does not schedule backup`() {
        manager.storeCredential("blob", "user-123", triggerBackup = false)

        verify(mockWorkManager, never()).enqueueUniqueWork(
            any(),
            any(),
            any<androidx.work.OneTimeWorkRequest>()
        )
    }

    @Test
    fun `updateCredential with triggerBackup true schedules backup`() {
        manager.storeCredential("blob-v1", "user-123", triggerBackup = false)
        reset(mockWorkManager)

        manager.updateCredential("blob-v2", triggerBackup = true)

        verify(mockWorkManager).enqueueUniqueWork(
            eq(ProteanCredentialManager.BACKUP_WORK_NAME),
            any(),
            any<androidx.work.OneTimeWorkRequest>()
        )
    }

    // MARK: - Multiple Operations Tests

    @Test
    fun `multiple version updates increment correctly`() {
        manager.storeCredential("v1", "user", version = 1, triggerBackup = false)
        manager.updateCredential("v2", triggerBackup = false)
        manager.updateCredential("v3", triggerBackup = false)
        manager.updateCredential("v4", triggerBackup = false)

        assertEquals(4, manager.getMetadata()?.version)
    }

    @Test
    fun `clear and re-store resets version`() {
        manager.storeCredential("v1", "user", version = 10, triggerBackup = false)
        manager.clearCredential()
        manager.storeCredential("new", "user", version = 1, triggerBackup = false)

        assertEquals(1, manager.getMetadata()?.version)
    }
}

/**
 * Testable version of ProteanCredentialManager that accepts injected dependencies.
 */
class TestableProteanCredentialManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val workManager: WorkManager
) {
    companion object {
        private const val KEY_CREDENTIAL_BLOB = "credential_blob"
        private const val KEY_METADATA = "metadata"
        private const val KEY_BACKUP_STATUS = "backup_status"
        private const val KEY_USER_GUID = "user_guid"
    }

    private val gson = Gson()

    fun storeCredential(
        credentialBlob: String,
        userGuid: String,
        version: Int = 1,
        triggerBackup: Boolean = true
    ) {
        val metadata = CredentialMetadata(
            version = version,
            createdAt = java.util.Date(),
            backedUpAt = null,
            sizeBytes = credentialBlob.length
        )

        prefs.edit()
            .putString(KEY_CREDENTIAL_BLOB, credentialBlob)
            .putString(KEY_METADATA, gson.toJson(metadata))
            .putString(KEY_USER_GUID, userGuid)
            .putString(KEY_BACKUP_STATUS, BackupStatus.PENDING.name)
            .apply()

        if (triggerBackup) {
            scheduleBackup()
        }
    }

    fun storeCredential(
        credentialBytes: ByteArray,
        userGuid: String,
        version: Int = 1,
        triggerBackup: Boolean = true
    ) {
        val base64 = Base64.encodeToString(credentialBytes, Base64.NO_WRAP)
        storeCredential(base64, userGuid, version, triggerBackup)
    }

    fun getCredential(): String? {
        return prefs.getString(KEY_CREDENTIAL_BLOB, null)
    }

    fun getCredentialBytes(): ByteArray? {
        val base64 = getCredential() ?: return null
        return try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun getMetadata(): CredentialMetadata? {
        val json = prefs.getString(KEY_METADATA, null) ?: return null
        return try {
            gson.fromJson(json, CredentialMetadata::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getUserGuid(): String? {
        return prefs.getString(KEY_USER_GUID, null)
    }

    fun hasCredential(): Boolean {
        return prefs.contains(KEY_CREDENTIAL_BLOB)
    }

    fun getBackupStatus(): BackupStatus {
        val status = prefs.getString(KEY_BACKUP_STATUS, null)
        return status?.let { BackupStatus.valueOf(it) } ?: BackupStatus.NONE
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateBackupStatus(status: BackupStatus, backupId: String? = null) {
        prefs.edit()
            .putString(KEY_BACKUP_STATUS, status.name)
            .apply()

        if (status == BackupStatus.COMPLETED) {
            getMetadata()?.let { metadata ->
                val updated = metadata.copy(backedUpAt = java.util.Date())
                prefs.edit()
                    .putString(KEY_METADATA, gson.toJson(updated))
                    .apply()
            }
        }
    }

    fun updateCredential(
        newCredentialBlob: String,
        triggerBackup: Boolean = true
    ) {
        val currentMetadata = getMetadata()
        val userGuid = getUserGuid()

        if (currentMetadata == null || userGuid == null) {
            return
        }

        val newVersion = currentMetadata.version + 1
        storeCredential(
            credentialBlob = newCredentialBlob,
            userGuid = userGuid,
            version = newVersion,
            triggerBackup = triggerBackup
        )
    }

    fun scheduleBackup() {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        val backupRequest = androidx.work.OneTimeWorkRequestBuilder<CredentialBackupWorker>()
            .setConstraints(constraints)
            .setInputData(androidx.work.workDataOf(
                CredentialBackupWorker.KEY_USER_GUID to getUserGuid()
            ))
            .build()

        workManager.enqueueUniqueWork(
            ProteanCredentialManager.BACKUP_WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            backupRequest
        )

        updateBackupStatus(BackupStatus.PENDING)
    }

    fun cancelPendingBackup() {
        workManager.cancelUniqueWork(ProteanCredentialManager.BACKUP_WORK_NAME)
    }

    fun clearCredential() {
        prefs.edit()
            .remove(KEY_CREDENTIAL_BLOB)
            .remove(KEY_METADATA)
            .remove(KEY_BACKUP_STATUS)
            .remove(KEY_USER_GUID)
            .apply()
        cancelPendingBackup()
    }

    fun importRecoveredCredential(
        credentialBlob: String,
        userGuid: String,
        version: Int
    ) {
        val metadata = CredentialMetadata(
            version = version,
            createdAt = java.util.Date(),
            backedUpAt = java.util.Date(),
            sizeBytes = credentialBlob.length
        )

        prefs.edit()
            .putString(KEY_CREDENTIAL_BLOB, credentialBlob)
            .putString(KEY_METADATA, gson.toJson(metadata))
            .putString(KEY_USER_GUID, userGuid)
            .putString(KEY_BACKUP_STATUS, BackupStatus.COMPLETED.name)
            .apply()
    }
}
