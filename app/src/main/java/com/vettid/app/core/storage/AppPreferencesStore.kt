package com.vettid.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vettid.app.features.location.DisplacementThreshold
import com.vettid.app.features.location.LocationPrecision
import com.vettid.app.features.location.LocationRetention
import com.vettid.app.features.location.LocationUpdateFrequency
import com.vettid.app.features.settings.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-sensitive app preferences (theme, UI settings, location tracking config).
 * Uses regular SharedPreferences since these are not sensitive data.
 *
 * Last-known GPS coordinates are sensitive (PII), so they live in a
 * separate EncryptedSharedPreferences file. SECURITY (android-storage-H2).
 */
class AppPreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val secureMasterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS_NAME,
        secureMasterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _themeFlow = MutableStateFlow(getTheme())
    val themeFlow: StateFlow<AppTheme> = _themeFlow.asStateFlow()

    private val _locationTrackingFlow = MutableStateFlow(
        prefs.getBoolean(KEY_LOCATION_ENABLED, false)
    )
    val locationTrackingFlow: StateFlow<Boolean> = _locationTrackingFlow.asStateFlow()

    fun getTheme(): AppTheme {
        val name = prefs.getString(KEY_THEME, AppTheme.AUTO.name) ?: AppTheme.AUTO.name
        return try {
            AppTheme.valueOf(name)
        } catch (_: IllegalArgumentException) {
            AppTheme.AUTO
        }
    }

    fun setTheme(theme: AppTheme) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _themeFlow.value = theme
    }

    // --- Location Tracking Preferences ---

    fun isLocationTrackingEnabled(): Boolean =
        prefs.getBoolean(KEY_LOCATION_ENABLED, false)

    fun setLocationTrackingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCATION_ENABLED, enabled).apply()
        _locationTrackingFlow.value = enabled
        // SECURITY (#46): when the user disables tracking, clear the
        // last-known-location cache that LocationCollectionWorker keeps
        // for displacement filtering. The vault is the source of truth
        // for retained location history; this cache only exists to
        // avoid sending duplicate readings, and it has no reason to
        // outlive the opt-in window.
        if (!enabled) {
            securePrefs.edit()
                .remove(KEY_LAST_KNOWN_LAT)
                .remove(KEY_LAST_KNOWN_LON)
                .apply()
            prefs.edit().remove(KEY_LAST_CAPTURE_TIME).apply()
        }
    }

    fun getLocationPrecision(): LocationPrecision {
        val name = prefs.getString(KEY_LOCATION_PRECISION, LocationPrecision.EXACT.name)
            ?: LocationPrecision.EXACT.name
        return try {
            LocationPrecision.valueOf(name)
        } catch (_: IllegalArgumentException) {
            LocationPrecision.EXACT
        }
    }

    fun setLocationPrecision(precision: LocationPrecision) {
        prefs.edit().putString(KEY_LOCATION_PRECISION, precision.name).apply()
    }

    fun getLocationFrequency(): LocationUpdateFrequency {
        val name = prefs.getString(KEY_LOCATION_FREQUENCY, LocationUpdateFrequency.THIRTY_MINUTES.name)
            ?: LocationUpdateFrequency.THIRTY_MINUTES.name
        return try {
            LocationUpdateFrequency.valueOf(name)
        } catch (_: IllegalArgumentException) {
            LocationUpdateFrequency.THIRTY_MINUTES
        }
    }

    fun setLocationFrequency(frequency: LocationUpdateFrequency) {
        prefs.edit().putString(KEY_LOCATION_FREQUENCY, frequency.name).apply()
    }

    fun getDisplacementThreshold(): DisplacementThreshold {
        val name = prefs.getString(KEY_LOCATION_DISPLACEMENT, DisplacementThreshold.ONE_HUNDRED.name)
            ?: DisplacementThreshold.ONE_HUNDRED.name
        return try {
            DisplacementThreshold.valueOf(name)
        } catch (_: IllegalArgumentException) {
            DisplacementThreshold.ONE_HUNDRED
        }
    }

    fun setDisplacementThreshold(threshold: DisplacementThreshold) {
        prefs.edit().putString(KEY_LOCATION_DISPLACEMENT, threshold.name).apply()
    }

    fun getLocationRetention(): LocationRetention {
        val name = prefs.getString(KEY_LOCATION_RETENTION, LocationRetention.THIRTY_DAYS.name)
            ?: LocationRetention.THIRTY_DAYS.name
        return try {
            LocationRetention.valueOf(name)
        } catch (_: IllegalArgumentException) {
            LocationRetention.THIRTY_DAYS
        }
    }

    fun setLocationRetention(retention: LocationRetention) {
        prefs.edit().putString(KEY_LOCATION_RETENTION, retention.name).apply()
    }

    /**
     * Last-known GPS coordinates. Persisted in EncryptedSharedPreferences
     * (hardware-backed MasterKey, AES256-GCM) because lat/lon is PII.
     *
     * SECURITY (android-storage-H2 + #46): the vault is the canonical
     * store for retained location history (`location.add` NATS op).
     * This on-device copy exists purely so LocationCollectionWorker can
     * compute the displacement-since-last-reading and skip duplicates
     * without round-tripping to the vault on every GPS tick. Cache is
     * cleared on tracking-disable (see setLocationTrackingEnabled).
     */
    fun getLastKnownLatitude(): Double {
        // Migrate any existing plaintext value to encrypted on first read.
        if (prefs.contains(KEY_LAST_KNOWN_LAT)) {
            val legacy = Double.fromBits(prefs.getLong(KEY_LAST_KNOWN_LAT, 0L))
            val legacyLon = Double.fromBits(prefs.getLong(KEY_LAST_KNOWN_LON, 0L))
            securePrefs.edit()
                .putLong(KEY_LAST_KNOWN_LAT, legacy.toBits())
                .putLong(KEY_LAST_KNOWN_LON, legacyLon.toBits())
                .apply()
            prefs.edit()
                .remove(KEY_LAST_KNOWN_LAT)
                .remove(KEY_LAST_KNOWN_LON)
                .apply()
        }
        return Double.fromBits(securePrefs.getLong(KEY_LAST_KNOWN_LAT, 0L))
    }

    fun getLastKnownLongitude(): Double =
        Double.fromBits(securePrefs.getLong(KEY_LAST_KNOWN_LON, 0L))

    fun setLastKnownLocation(latitude: Double, longitude: Double) {
        securePrefs.edit()
            .putLong(KEY_LAST_KNOWN_LAT, latitude.toBits())
            .putLong(KEY_LAST_KNOWN_LON, longitude.toBits())
            .apply()
    }

    fun getLastCaptureTime(): Long =
        prefs.getLong(KEY_LAST_CAPTURE_TIME, 0L)

    fun setLastCaptureTime(epochSeconds: Long) {
        prefs.edit().putLong(KEY_LAST_CAPTURE_TIME, epochSeconds).apply()
    }

    // --- Credential Settings ---

    fun getSessionTtlSeconds(): Int =
        prefs.getInt(KEY_SESSION_TTL, 300)

    fun setSessionTtlSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_SESSION_TTL, seconds).apply()
    }

    fun getArchiveAfterDays(): Int =
        prefs.getInt(KEY_ARCHIVE_AFTER_DAYS, 7)

    fun setArchiveAfterDays(days: Int) {
        prefs.edit().putInt(KEY_ARCHIVE_AFTER_DAYS, days).apply()
    }

    fun getDeleteAfterDays(): Int =
        prefs.getInt(KEY_DELETE_AFTER_DAYS, 30)

    fun setDeleteAfterDays(days: Int) {
        prefs.edit().putInt(KEY_DELETE_AFTER_DAYS, days).apply()
    }

    /**
     * Wipe every preference (used by sign-out + decommission).
     * Drops the cached lat/lon, location-tracking config, theme, all
     * credential-policy knobs. Callers should re-seed defaults if the
     * user immediately re-enrolls.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        securePrefs.edit().clear().apply()
        _themeFlow.value = AppTheme.AUTO
        _locationTrackingFlow.value = false
    }

    companion object {
        private const val PREFS_NAME = "app_preferences"
        private const val SECURE_PREFS_NAME = "app_preferences_secure"
        private const val KEY_THEME = "app_theme"

        // Credential settings keys
        private const val KEY_SESSION_TTL = "session_ttl_seconds"
        private const val KEY_ARCHIVE_AFTER_DAYS = "archive_after_days"
        private const val KEY_DELETE_AFTER_DAYS = "delete_after_days"

        // Location tracking keys
        private const val KEY_LOCATION_ENABLED = "location_tracking_enabled"
        private const val KEY_LOCATION_PRECISION = "location_precision"
        private const val KEY_LOCATION_FREQUENCY = "location_frequency"
        private const val KEY_LOCATION_DISPLACEMENT = "location_displacement_threshold"
        private const val KEY_LOCATION_RETENTION = "location_retention"
        private const val KEY_LAST_KNOWN_LAT = "location_last_lat"
        private const val KEY_LAST_KNOWN_LON = "location_last_lon"
        private const val KEY_LAST_CAPTURE_TIME = "location_last_capture_time"

        // Backup keys
        private const val KEY_BACKUP_ENABLED = "backup_enabled"

        // Notification keys
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

        // One-time UI nudge keys
        private const val KEY_CATALOG_NUDGE_DISMISSED = "secrets_catalog_nudge_dismissed"
    }

    fun isBackupEnabled(): Boolean =
        prefs.getBoolean(KEY_BACKUP_ENABLED, true)

    fun setBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKUP_ENABLED, enabled).apply()
    }

    // --- Notification Preferences ---

    fun hasRequestedNotificationPermission(): Boolean =
        prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)

    fun setNotificationPermissionRequested(requested: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, requested).apply()
    }

    // --- One-time UI nudges ---

    /**
     * Whether the user has dismissed the "secrets visible to connections"
     * nudge on the Secrets screen. Persisted so the banner shows at most
     * once ever — previously a remember-scoped flag, so it reappeared on
     * every navigation back to the screen.
     */
    fun hasDismissedCatalogVisibilityNudge(): Boolean =
        prefs.getBoolean(KEY_CATALOG_NUDGE_DISMISSED, false)

    fun setCatalogVisibilityNudgeDismissed() {
        prefs.edit().putBoolean(KEY_CATALOG_NUDGE_DISMISSED, true).apply()
    }
}
