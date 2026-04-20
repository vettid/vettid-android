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
    private val consentTracker: MigrationConsentTracker,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "VaultUpdateVM"
        private const val PREFS_NAME = "vault_migration_prefs"
        private const val KEY_REMINDED_AT = "migration_reminded_at"
        private const val KEY_DISMISSED = "migration_dismissed"
        private const val KEY_DISMISSED_VERSION = "migration_dismissed_version"
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

                // Dismissal is version-scoped so a later migration isn't
                // silently suppressed by an older deferral. The legacy
                // boolean (KEY_DISMISSED) without a version key is treated
                // as stale — we can't tell which migration it was for, so
                // clear it unconditionally on first read.
                val isMandatory = isMandatoryNow(config)
                val dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, null)
                val legacyDismissed = prefs.getBoolean(KEY_DISMISSED, false)
                if (legacyDismissed && dismissedVersion == null) {
                    prefs.edit().remove(KEY_DISMISSED).apply()
                }
                val isDismissedForThisVersion = dismissedVersion == config.version

                currentConfig = config

                // Pre-PIN consent recorded in this session short-circuits
                // the post-unlock flow so the user isn't asked twice about
                // the same update.
                when (consentTracker.consume()) {
                    MigrationIntent.APPROVED -> {
                        Log.i(TAG, "Pre-PIN approval recorded — auto-applying migration with visible state")
                        // autoApplied=true tells the UI to render a slim
                        // progress indicator instead of the full card, so
                        // the user doesn't see the action card flash up
                        // for a second before the success card replaces it.
                        startUpdate(autoApplied = true)
                        return@launch
                    }
                    MigrationIntent.SKIPPED -> {
                        Log.i(TAG, "Pre-PIN skip recorded — suppressing card, writing feed entry")
                        pushDeferredUpdateToFeed(config)
                        _state.value = VaultUpdateState.NoUpdate
                        return@launch
                    }
                    MigrationIntent.UNDECIDED -> { /* fall through */ }
                }

                if (isDismissedForThisVersion && !isMandatory) {
                    // User dismissed this version — check again on next app open
                    _state.value = VaultUpdateState.NoUpdate
                    return@launch
                }

                // Always surface the card when an unapplied migration
                // exists. The pre-PIN trust prompt only covers "I accept
                // this new enclave version"; re-sealing the vault state
                // against it is a separate decision the user must make
                // visibly — silent migration would swap vault state with
                // zero user acknowledgment even though the user just
                // typed their PIN and has the app open.
                val newPcr0 = config.newPcr0
                val currentPcr0 = pcrConfigManager.getCurrentPcrs().pcr0
                Log.d(TAG, "checkForUpdate: showing update card (version=${config.version}, newPcr0=${newPcr0?.take(16)}..., currentPcr0=${currentPcr0.take(16)}...)")

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
     * User tapped "Update Now" — start the re-sealing process.
     * Automatically retries on failure (NATS may route to either old or new instance).
     */
    fun startUpdate(autoApplied: Boolean = false) {
        val config = currentConfig ?: return
        val maxRetries = 5
        val retryDelayMs = 1500L

        viewModelScope.launch {
            _state.value = VaultUpdateState.Updating(config, autoApplied = autoApplied)

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
                    // Clear any leftover deferred-update entries from the
                    // local feed cache. pushDeferredUpdateToFeed seeds
                    // them on Remind Me Later; without this, the row
                    // stays in the Connections screen as unread after
                    // the update is applied. Sweep every version we've
                    // written so stale entries from prior deploys go
                    // away too.
                    val sweepIds = listOf(
                        "local-migration-${config.version}",
                        "local-migration-${version.ifEmpty { config.version }}",
                    ).distinct()
                    sweepIds.forEach { feedRepository.removeEventLocally(it) }
                    // Also purge any local-migration-* entry from earlier
                    // versions — the completed update supersedes them.
                    feedRepository.removeEventsLocallyWhere { it.eventId.startsWith("local-migration-") }
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
    /**
     * Re-surface the update card. Triggered when the user taps the
     * "Vault Security Update Pending" feed entry they created by
     * tapping Remind Me Later earlier — clears the dismissed flag so
     * checkForUpdate actually shows the card again, then runs it.
     */
    fun resurface() {
        getPrefs().edit()
            .remove(KEY_DISMISSED)
            .remove(KEY_DISMISSED_VERSION)
            .remove(KEY_REMINDED_AT)
            .apply()
        checkForUpdate()
    }

    fun remindLater() {
        val config = currentConfig
        val edit = getPrefs().edit()
            .putLong(KEY_REMINDED_AT, System.currentTimeMillis())
            .putBoolean(KEY_DISMISSED, true)
        if (config != null) {
            edit.putString(KEY_DISMISSED_VERSION, config.version)
        }
        edit.apply()

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
            // Normal priority — HIGH had the feed pin the entry at the
            // top which read as "urgent / demanding attention." It's a
            // deferred reminder, not a pinned item.
            priority = 0,
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

    /** Migration in progress — show spinner. autoApplied=true when
     *  pre-PIN consent drove the update so the UI can skip the big card. */
    data class Updating(val config: MigrationConfig, val autoApplied: Boolean = false) : VaultUpdateState()

    /** Migration completed — show brief success */
    object Updated : VaultUpdateState()

    /** Migration failed — show retry option */
    data class Error(
        val config: MigrationConfig,
        val message: String
    ) : VaultUpdateState()
}
