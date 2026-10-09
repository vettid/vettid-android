package com.vettid.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The app's theme choice. */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** How long the app may stay in the background before the app lock asks again (D6). */
@Suppress("MagicNumber") // seconds
enum class AppLockTimeout(val seconds: Int) {
    IMMEDIATELY(0),
    ONE_MINUTE(60),
    FIVE_MINUTES(300),
    FIFTEEN_MINUTES(900),
    ONE_HOUR(3600),
    ;

    companion object {
        val DEFAULT = FIVE_MINUTES

        fun of(seconds: Int): AppLockTimeout = entries.firstOrNull { it.seconds == seconds } ?: DEFAULT
    }
}

/**
 * How the app lock asks (owner request 2026-10-09, ANDROID-PLAN 0.1.19): [BIOMETRICS] a class 3 fingerprint or face,
 * falling back to the phone's screen lock; [SCREEN_LOCK] the phone's PIN, pattern or password only. No VettID passcode.
 */
enum class AppLockMethod { BIOMETRICS, SCREEN_LOCK }

/** Non-secret preferences of this device (DataStore). Nothing here is vault data. */
data class AppPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val appLockEnabled: Boolean = false,
    val appLockTimeout: AppLockTimeout = AppLockTimeout.DEFAULT,
    /** Null: never chosen (a lock turned on before the choice existed is [AppLockMethod.BIOMETRICS], see AppLock.start). */
    val appLockMethod: AppLockMethod? = null,
    /** The release notice the member dismissed (ANDROID-PLAN 0.1.19): the target release, its kind, when (epoch ms). */
    val releaseNoticeDismissed: ReleaseNoticeDismissal? = null,
    /** The newest release a local notification was posted for (one per release). */
    val releaseNotified: Long = 0,
    /** The notification permission (API 33+) was asked for once; never again. */
    val notificationsAsked: Boolean = false,
)

/** A dismissed release notice: [release] and [kind] (`UpdateNoticeKind` name) at [atMs] (epoch milliseconds). */
data class ReleaseNoticeDismissal(val release: Long, val kind: String, val atMs: Long)

interface PreferencesRepository {
    val preferences: Flow<AppPreferences>

    suspend fun setTheme(theme: ThemePreference)

    suspend fun setAppLockEnabled(enabled: Boolean)

    suspend fun setAppLockTimeout(timeout: AppLockTimeout)

    suspend fun setAppLockMethod(method: AppLockMethod)

    suspend fun setReleaseNoticeDismissed(d: ReleaseNoticeDismissal)

    suspend fun setReleaseNotified(release: Long)

    suspend fun setNotificationsAsked()

    /** Back to the defaults of a fresh install (the wipe of a replaced phone). */
    suspend fun clear()
}

private val Context.vettIdPrefs: DataStore<Preferences> by preferencesDataStore(name = "vettid_preferences")

/** [PreferencesRepository] on Jetpack DataStore. */
class DataStorePreferencesRepository(context: Context) : PreferencesRepository {
    private val store = context.applicationContext.vettIdPrefs

    override val preferences: Flow<AppPreferences> = store.data.map { p ->
        AppPreferences(
            theme = p[THEME]?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() } ?: ThemePreference.SYSTEM,
            appLockEnabled = p[APP_LOCK] ?: false,
            appLockTimeout = p[APP_LOCK_TIMEOUT]?.let { AppLockTimeout.of(it) } ?: AppLockTimeout.DEFAULT,
            appLockMethod = p[APP_LOCK_METHOD]?.let { runCatching { AppLockMethod.valueOf(it) }.getOrNull() },
            releaseNoticeDismissed = p[RELEASE_DISMISSED]?.let { r ->
                ReleaseNoticeDismissal(r, p[RELEASE_DISMISSED_KIND] ?: "", p[RELEASE_DISMISSED_AT] ?: 0)
            },
            releaseNotified = p[RELEASE_NOTIFIED] ?: 0,
            notificationsAsked = p[NOTIFICATIONS_ASKED] ?: false,
        )
    }

    override suspend fun setAppLockMethod(method: AppLockMethod) {
        store.edit { it[APP_LOCK_METHOD] = method.name }
    }

    override suspend fun setReleaseNoticeDismissed(d: ReleaseNoticeDismissal) {
        store.edit {
            it[RELEASE_DISMISSED] = d.release
            it[RELEASE_DISMISSED_KIND] = d.kind
            it[RELEASE_DISMISSED_AT] = d.atMs
        }
    }

    override suspend fun setReleaseNotified(release: Long) {
        store.edit { it[RELEASE_NOTIFIED] = release }
    }

    override suspend fun setNotificationsAsked() {
        store.edit { it[NOTIFICATIONS_ASKED] = true }
    }

    override suspend fun setTheme(theme: ThemePreference) {
        store.edit { it[THEME] = theme.name }
    }

    override suspend fun setAppLockEnabled(enabled: Boolean) {
        store.edit { it[APP_LOCK] = enabled }
    }

    override suspend fun setAppLockTimeout(timeout: AppLockTimeout) {
        store.edit { it[APP_LOCK_TIMEOUT] = timeout.seconds }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_TIMEOUT = intPreferencesKey("app_lock_timeout_s")
        val APP_LOCK_METHOD = stringPreferencesKey("app_lock_method")
        val RELEASE_DISMISSED = longPreferencesKey("release_notice_dismissed")
        val RELEASE_DISMISSED_KIND = stringPreferencesKey("release_notice_dismissed_kind")
        val RELEASE_DISMISSED_AT = longPreferencesKey("release_notice_dismissed_at_ms")
        val RELEASE_NOTIFIED = longPreferencesKey("release_notified")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications_asked")
    }
}

/** In memory (tests and previews). */
class InMemoryPreferencesRepository(initial: AppPreferences = AppPreferences()) : PreferencesRepository {
    private val state = MutableStateFlow(initial)
    val current: StateFlow<AppPreferences> = state.asStateFlow()
    override val preferences: Flow<AppPreferences> = state

    override suspend fun setTheme(theme: ThemePreference) = state.update { it.copy(theme = theme) }

    override suspend fun setAppLockEnabled(enabled: Boolean) = state.update { it.copy(appLockEnabled = enabled) }

    override suspend fun setAppLockTimeout(timeout: AppLockTimeout) = state.update { it.copy(appLockTimeout = timeout) }

    override suspend fun setAppLockMethod(method: AppLockMethod) = state.update { it.copy(appLockMethod = method) }

    override suspend fun setReleaseNoticeDismissed(d: ReleaseNoticeDismissal) = state.update { it.copy(releaseNoticeDismissed = d) }

    override suspend fun setReleaseNotified(release: Long) = state.update { it.copy(releaseNotified = release) }

    override suspend fun setNotificationsAsked() = state.update { it.copy(notificationsAsked = true) }

    override suspend fun clear() {
        state.value = AppPreferences()
    }
}
