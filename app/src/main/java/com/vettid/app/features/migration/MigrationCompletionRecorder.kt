package com.vettid.app.features.migration

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the post-migration housekeeping that has to happen after a
 * successful re-seal: write the completed-version marker, clear the
 * deferral state, and sweep stale "deferred update" entries out of the
 * local feed cache.
 *
 * Single source of truth so the M1 PIN-coupled flow
 * ([com.vettid.app.features.unlock.PinUnlockViewModel.dispatchPinUnlock])
 * and the legacy post-unlock card flow ([VaultUpdateViewModel]) keep
 * the same on-disk shape. Prefs file + key names match those that
 * existed before the redesign so we don't strand prior records.
 */
@Singleton
class MigrationCompletionRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedRepository: FeedRepository
) {

    companion object {
        const val PREFS_NAME = "vault_migration_prefs"
        const val KEY_REMINDED_AT = "migration_reminded_at"
        const val KEY_DISMISSED = "migration_dismissed"
        const val KEY_DISMISSED_VERSION = "migration_dismissed_version"
        const val KEY_COMPLETED_VERSION = "migration_completed_version"
        private const val TAG = "MigrationRecorder"
    }

    /**
     * Mark a migration as completed so subsequent app launches don't
     * re-prompt for it, clear any "remind me later" state from earlier
     * deferrals, and remove deferred-update feed entries that this
     * completion supersedes.
     *
     * Both arguments may be the same string; the published version is
     * preferred when available because the per-user marker keys off it.
     */
    fun recordCompletion(version: String, fallbackVersion: String? = null) {
        val resolved = version.ifEmpty { fallbackVersion ?: "" }
        if (resolved.isEmpty()) {
            Log.w(TAG, "recordCompletion called with empty version; skipping persistence")
            return
        }

        getPrefs().edit()
            .putString(KEY_COMPLETED_VERSION, resolved)
            .putBoolean(KEY_DISMISSED, false)
            .remove(KEY_DISMISSED_VERSION)
            .remove(KEY_REMINDED_AT)
            .apply()

        // Sweep stale "deferred update" feed rows. There may be entries
        // from prior deploys; this completed update supersedes all of
        // them. Identical key names match VaultUpdateViewModel's pattern
        // so legacy entries written before the redesign also get
        // cleared.
        val sweepIds = listOf(
            "local-migration-$resolved",
            if (fallbackVersion != null) "local-migration-$fallbackVersion" else null,
        ).filterNotNull().distinct()
        sweepIds.forEach { feedRepository.removeEventLocally(it) }
        feedRepository.removeEventsLocallyWhere { it.eventId.startsWith("local-migration-") }

        Log.i(TAG, "Migration $resolved marked completed; deferred-update feed entries swept")
    }

    /**
     * Read the most recently completed version. Used by
     * [VaultUpdateViewModel] to short-circuit the post-unlock card.
     */
    fun completedVersion(): String? = getPrefs().getString(KEY_COMPLETED_VERSION, null)

    fun getPrefs(): android.content.SharedPreferences {
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
