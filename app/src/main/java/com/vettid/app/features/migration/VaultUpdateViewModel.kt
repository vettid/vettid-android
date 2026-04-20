package com.vettid.app.features.migration

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vettid.app.core.attestation.PcrConfigManager
import com.vettid.app.core.nats.FeedEvent
import com.vettid.app.core.nats.MigrationClient
import com.vettid.app.core.nats.MigrationConfig
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * ViewModel for the vault security update consent flow.
 *
 * Checks for pending updates after vault unlock and manages the
 * user's update/defer decision. After 72 hours, deferral is no
 * longer available and the update becomes mandatory.
 */
@HiltViewModel
class VaultUpdateViewModel @Inject constructor(
    private val migrationClient: MigrationClient,
    private val pcrConfigManager: PcrConfigManager,
    private val feedRepository: FeedRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "VaultUpdateVM"
        private const val PREFS_NAME = "vault_migration_prefs"
        private const val KEY_REMINDED_AT = "migration_reminded_at"
        private const val KEY_DISMISSED = "migration_dismissed"
        private const val KEY_COMPLETED_VERSION = "migration_completed_version"
    }

    private val _state = MutableStateFlow<VaultUpdateState>(VaultUpdateState.Checking)
    val state: StateFlow<VaultUpdateState> = _state.asStateFlow()

    private var currentConfig: MigrationConfig? = null

    /**
     * Check for pending migration after vault unlock.
     * Called from the main screen on first composition.
     */
    fun checkForUpdate() {
        viewModelScope.launch {
            _state.value = VaultUpdateState.Checking

            try {
                val config = migrationClient.getMigrationConfig()
                if (config == null) {
                    _state.value = VaultUpdateState.NoUpdate
                    return@launch
                }

                // Check if user already completed this version
                val prefs = getPrefs()
                val completedVersion = prefs.getString(KEY_COMPLETED_VERSION, null)
                if (completedVersion == config.version) {
                    _state.value = VaultUpdateState.NoUpdate
                    return@launch
                }

                // Check if user dismissed and whether it's now mandatory
                val isMandatory = isMandatoryNow(config)
                val isDismissed = prefs.getBoolean(KEY_DISMISSED, false)

                currentConfig = config

                if (isDismissed && !isMandatory) {
                    // User dismissed but not yet mandatory — check again on next app open
                    _state.value = VaultUpdateState.NoUpdate
                    return@launch
                }

                // Only auto-apply silently when the user has ALREADY
                // explicitly consented to the migration's new PCR0 (via the
                // pre-PIN trust prompt during this unlock). Surfacing the
                // card in that case would be pure noise — the user's already
                // decided. In every other case the user gets the card so the
                // update is visible consent, not a silent swap.
                //
                // Historical regression: this branch also used to
                // auto-apply when `currentPcr0Trusted` was true. During
                // migration both old and new enclaves run; when the app
                // happened to hit the OLD enclave, currentPcr0 matched the
                // previously-trusted PCR0 and the card was suppressed — so
                // the new PCR0 got silently added to the trusted set with
                // no user visibility. Don't do that.
                val newPcr0 = config.newPcr0
                val newPcr0Trusted = newPcr0?.let { pcrConfigManager.isPcr0Trusted(it) } ?: false
                val currentPcr0 = pcrConfigManager.getCurrentPcrs().pcr0
                Log.d(TAG, "checkForUpdate: newPcr0=${newPcr0?.take(16)}..., newPcr0Trusted=$newPcr0Trusted, currentPcr0=${currentPcr0.take(16)}...")
                if (!newPcr0.isNullOrEmpty() && newPcr0Trusted) {
                    Log.i(TAG, "New PCR0 already trusted via pre-PIN prompt — auto-applying silently")
                    currentConfig = config
                    _state.value = VaultUpdateState.NoUpdate
                    runMigrationSilent(config)
                    return@launch
                }

                _state.value = VaultUpdateState.UpdateAvailable(
                    config = config,
                    isMandatory = isMandatory
                )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for update", e)
                _state.value = VaultUpdateState.NoUpdate
            }
        }
    }

    /**
     * Background variant of startUpdate for the pre-consented case. Unlike
     * startUpdate(), this does not flip state to Updating (keeps the UI at
     * NoUpdate), so no card is rendered at all while the migration runs.
     * Only if every retry fails do we surface the Error card so the user
     * has a way to retry.
     */
    private suspend fun runMigrationSilent(config: MigrationConfig) {
        val maxRetries = 5
        val retryDelayMs = 1500L
        for (attempt in 1..maxRetries) {
            val result = migrationClient.startMigration()
            if (result.isSuccess) {
                val version = result.getOrDefault("")
                config.newPcr0?.let { newPcr0 ->
                    pcrConfigManager.addTrustedPcr0(newPcr0)
                }
                getPrefs().edit()
                    .putString(KEY_COMPLETED_VERSION, version.ifEmpty { config.version })
                    .putBoolean(KEY_DISMISSED, false)
                    .remove(KEY_REMINDED_AT)
                    .apply()
                Log.i(TAG, "Silent migration completed (version=${version.ifEmpty { config.version }})")
                // Leave state at NoUpdate — user never sees anything.
                return
            }
            val error = result.exceptionOrNull()
            if (attempt < maxRetries) {
                Log.w(TAG, "Silent migration attempt $attempt/$maxRetries failed, retrying: ${error?.message}")
                delay(retryDelayMs)
            } else {
                Log.e(TAG, "Silent migration failed after $maxRetries attempts; surfacing error card", error)
                _state.value = VaultUpdateState.Error(
                    config = config,
                    message = error?.message ?: "Update failed. Please try again."
                )
            }
        }
    }

    /**
     * User tapped "Update Now" — start the re-sealing process.
     * Automatically retries on failure (NATS may route to either old or new instance).
     */
    fun startUpdate() {
        val config = currentConfig ?: return
        val maxRetries = 5
        val retryDelayMs = 1500L

        viewModelScope.launch {
            _state.value = VaultUpdateState.Updating(config)

            for (attempt in 1..maxRetries) {
                val result = migrationClient.startMigration()

                if (result.isSuccess) {
                    val version = result.getOrDefault("")

                    // SECURITY: Add new enclave PCR0 to user's trusted set.
                    // This is the user's explicit consent to the new enclave version.
                    // Without this, PIN unlock will be blocked on the new enclave.
                    config.newPcr0?.let { newPcr0 ->
                        pcrConfigManager.addTrustedPcr0(newPcr0)
                        Log.i(TAG, "Added new enclave PCR0 to trusted set after migration consent")
                    }

                    getPrefs().edit()
                        .putString(KEY_COMPLETED_VERSION, version.ifEmpty { config.version })
                        .putBoolean(KEY_DISMISSED, false)
                        .remove(KEY_REMINDED_AT)
                        .apply()
                    _state.value = VaultUpdateState.Updated
                    return@launch
                }

                val error = result.exceptionOrNull()
                if (attempt < maxRetries) {
                    Log.w(TAG, "Migration attempt $attempt/$maxRetries failed, retrying: ${error?.message}")
                    delay(retryDelayMs)
                } else {
                    Log.e(TAG, "Migration failed after $maxRetries attempts", error)
                    _state.value = VaultUpdateState.Error(
                        config = config,
                        message = error?.message ?: "Update failed. Please try again."
                    )
                }
            }
        }
    }

    /**
     * User tapped "Remind Me Later" — defer until next app open and also
     * drop a synthetic entry into the feed so the deferred update stays
     * discoverable from the feed (user asked for this explicitly — the
     * post-unlock card is easy to miss if you're in a hurry). The event
     * gets wiped on the next full sync; subsequent deferrals re-add it.
     */
    fun remindLater() {
        val config = currentConfig
        getPrefs().edit()
            .putLong(KEY_REMINDED_AT, System.currentTimeMillis())
            .putBoolean(KEY_DISMISSED, true)
            .apply()

        if (config != null) {
            pushDeferredUpdateToFeed(config)
        }

        _state.value = VaultUpdateState.NoUpdate
    }

    private fun pushDeferredUpdateToFeed(config: MigrationConfig) {
        val now = System.currentTimeMillis() / 1000
        val event = FeedEvent(
            // Stable id per config version — re-adding the same id after a
            // sync wipe just reinstates the entry rather than spamming.
            eventId = "local-migration-${config.version}",
            eventType = "security.migration",
            sourceType = "local",
            sourceId = null,
            title = "Vault Security Update Pending",
            message = config.summary.ifEmpty { "A vault update is waiting for your approval. Tap to review." },
            metadata = mapOf("migration_version" to config.version),
            feedStatus = "active",
            actionType = null,
            priority = 1,
            createdAt = now,
            readAt = null,
            actionedAt = null,
            archivedAt = null,
            expiresAt = null,
            syncSequence = 0L,
            retentionClass = "standard"
        )
        feedRepository.addEventLocally(event)
    }

    /**
     * Dismiss the success confirmation.
     */
    fun dismissSuccess() {
        _state.value = VaultUpdateState.NoUpdate
    }

    /**
     * Retry after error.
     */
    fun retry() {
        val config = currentConfig
        if (config != null) {
            _state.value = VaultUpdateState.UpdateAvailable(
                config = config,
                isMandatory = isMandatoryNow(config)
            )
        } else {
            checkForUpdate()
        }
    }

    private fun isMandatoryNow(config: MigrationConfig): Boolean {
        val mandatoryAfter = config.mandatoryAfter ?: return false
        return try {
            val deadline = Instant.parse(mandatoryAfter)
            Instant.now().isAfter(deadline)
        } catch (e: Exception) {
            false
        }
    }

    private fun getPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

/**
 * UI state for the vault update flow.
 */
sealed class VaultUpdateState {
    /** Checking for updates (initial state) */
    object Checking : VaultUpdateState()

    /** No update available */
    object NoUpdate : VaultUpdateState()

    /** Update available — show prompt to user */
    data class UpdateAvailable(
        val config: MigrationConfig,
        val isMandatory: Boolean
    ) : VaultUpdateState()

    /** Migration in progress — show spinner */
    data class Updating(val config: MigrationConfig) : VaultUpdateState()

    /** Migration completed — show brief success */
    object Updated : VaultUpdateState()

    /** Migration failed — show retry option */
    data class Error(
        val config: MigrationConfig,
        val message: String
    ) : VaultUpdateState()
}
