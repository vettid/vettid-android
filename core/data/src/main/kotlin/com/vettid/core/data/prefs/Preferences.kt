package com.vettid.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

/** Non-secret preferences of this device (DataStore). Nothing here is vault data. */
data class AppPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val appLockEnabled: Boolean = false,
    val appLockTimeout: AppLockTimeout = AppLockTimeout.DEFAULT,
)

interface PreferencesRepository {
    val preferences: Flow<AppPreferences>

    suspend fun setTheme(theme: ThemePreference)

    suspend fun setAppLockEnabled(enabled: Boolean)

    suspend fun setAppLockTimeout(timeout: AppLockTimeout)

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
        )
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

    override suspend fun clear() {
        state.value = AppPreferences()
    }
}
