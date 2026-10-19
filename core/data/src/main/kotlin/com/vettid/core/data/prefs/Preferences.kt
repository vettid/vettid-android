package com.vettid.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

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

/**
 * How notifications reach this phone (ANDROID-PLAN 0.1.23 D7): [SERVICE] the on-phone service (recommended, the
 * default), [PUSH] Google push (FCM, not available yet), [OFF] none (not recommended). A device preference: it
 * describes this phone, not the vault.
 */
enum class NotificationMode { SERVICE, PUSH, OFF }

/** What notifications show (ANDROID-PLAN 0.1.23, Notification modes 8): names (the default), names and message text, nothing. */
enum class NotificationPreviews { NAMES, NAMES_AND_TEXT, NOTHING }

/**
 * What this phone remembers of its Google push registration between process starts (ANDROID-PLAN 0.1.30, Notification
 * modes 4). Never the token (Firebase keeps it; the vault keeps none): [registered] the vault last answered
 * `push.register` with an active registration, so a process a wake starts need not fall back to the on-phone
 * service while it asks again; [limitedUntilMs] `limit` was answered: no `push.register` before then (epoch ms, 24
 * hours); [unregisterPending] the mode was left while the vault could not be told: `push.unregister` at its next open;
 * [confirmPending] `push.register` answered `pending` (VAULT-MESSAGING 0.30.0) and no confirmation or other final
 * answer followed yet, so a new process falls back to the on-phone service until the registration is active.
 */
data class PushMemory(
    val registered: Boolean = false,
    val limitedUntilMs: Long = 0,
    val unregisterPending: Boolean = false,
    val confirmPending: Boolean = false,
)

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
    /** Null: never chosen, which is [NotificationMode.SERVICE] for new and updated installs (§9 question 13). */
    val notificationMode: NotificationMode? = null,
    val notificationPreviews: NotificationPreviews = NotificationPreviews.NAMES,
    /** The one-time note "VettID now notifies you in the background" was shown. */
    val notificationsNoteShown: Boolean = false,
    val push: PushMemory = PushMemory(),
    /**
     * The preferences file was corrupt and was replaced by an empty one (A6 audit P4): every preference is back at
     * its default (the app lock is off) until the member has been told ([PreferencesRepository.acknowledgeReset]).
     */
    val wasReset: Boolean = false,
) {
    /** The mode in effect. */
    val effectiveNotificationMode: NotificationMode get() = notificationMode ?: NotificationMode.SERVICE
}

/**
 * A dismissed release notice: [release] and [kind] (`UpdateNoticeKind` name) at [atMs] (epoch milliseconds), for
 * [forMs] (STAGING-TIME-SCALE, S16: 24 h divided by the vault's time scale at the dismissal; 0 for 24 h, as stored
 * before the scale).
 */
data class ReleaseNoticeDismissal(val release: Long, val kind: String, val atMs: Long, val forMs: Long = 0)

@Suppress("TooManyFunctions") // one setter per preference
interface PreferencesRepository {
    val preferences: Flow<AppPreferences>

    suspend fun setTheme(theme: ThemePreference)

    suspend fun setAppLockEnabled(enabled: Boolean)

    suspend fun setAppLockTimeout(timeout: AppLockTimeout)

    suspend fun setAppLockMethod(method: AppLockMethod)

    suspend fun setReleaseNoticeDismissed(d: ReleaseNoticeDismissal)

    suspend fun setReleaseNotified(release: Long)

    suspend fun setNotificationsAsked()

    suspend fun setNotificationMode(mode: NotificationMode)

    suspend fun setNotificationPreviews(previews: NotificationPreviews)

    suspend fun setNotificationsNoteShown()

    suspend fun setPushMemory(m: PushMemory)

    /** The member saw the notice that the preferences were reset ([AppPreferences.wasReset]). */
    suspend fun acknowledgeReset() {}

    /** Back to the defaults of a fresh install (the wipe of a replaced phone). */
    suspend fun clear()
}

/**
 * The preferences file. Nothing in it is a key or identifies the device or the vault (theme, app lock settings,
 * notification choices), so a file that cannot be parsed is replaced by an empty one that only remembers that it
 * happened ([PreferencesFiles.RESET], shown as a notice): without this, every read of the preferences threw, the app lock never
 * left "pending" and the app stayed on its start screen for good (A6 audit P4). The stores that do hold keys or the
 * device's identity (`KeystoreFileStore` files) are never replaced this way.
 */
private val Context.vettIdPrefs: DataStore<Preferences> by preferencesDataStore(
    name = "vettid_preferences",
    corruptionHandler = PreferencesFiles.corruptionHandler,
)

/** How the preferences file is opened: the same corruption handling for the app's store and for tests of it. */
internal object PreferencesFiles {
    /** Set in the file that replaced a corrupt one, until the member has seen the notice. */
    val RESET = booleanPreferencesKey("preferences_were_reset")
    val corruptionHandler = ReplaceFileCorruptionHandler<Preferences> { preferencesOf(RESET to true) }

    /** The store on [file] (`*.preferences_pb`), as the app opens its own. */
    fun open(file: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(corruptionHandler = corruptionHandler, scope = scope) { file }
}

/** [PreferencesRepository] on Jetpack DataStore. */
@Suppress("TooManyFunctions")
class DataStorePreferencesRepository internal constructor(private val store: DataStore<Preferences>) : PreferencesRepository {
    constructor(context: Context) : this(context.applicationContext.vettIdPrefs)

    // A file that cannot be read at all (an I/O error, not a corrupt file) reads as the defaults; it is not replaced.
    private val data: Flow<Preferences> = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    override val preferences: Flow<AppPreferences> = data.map { p ->
        AppPreferences(
            wasReset = p[PreferencesFiles.RESET] ?: false,
            theme = p[THEME]?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() } ?: ThemePreference.SYSTEM,
            appLockEnabled = p[APP_LOCK] ?: false,
            appLockTimeout = p[APP_LOCK_TIMEOUT]?.let { AppLockTimeout.of(it) } ?: AppLockTimeout.DEFAULT,
            appLockMethod = p[APP_LOCK_METHOD]?.let { runCatching { AppLockMethod.valueOf(it) }.getOrNull() },
            releaseNoticeDismissed = p[RELEASE_DISMISSED]?.let { r ->
                ReleaseNoticeDismissal(r, p[RELEASE_DISMISSED_KIND] ?: "", p[RELEASE_DISMISSED_AT] ?: 0, p[RELEASE_DISMISSED_FOR] ?: 0)
            },
            releaseNotified = p[RELEASE_NOTIFIED] ?: 0,
            notificationsAsked = p[NOTIFICATIONS_ASKED] ?: false,
            notificationMode = p[NOTIFICATION_MODE]?.let { runCatching { NotificationMode.valueOf(it) }.getOrNull() },
            notificationPreviews = p[NOTIFICATION_PREVIEWS]?.let { runCatching { NotificationPreviews.valueOf(it) }.getOrNull() }
                ?: NotificationPreviews.NAMES,
            notificationsNoteShown = p[NOTIFICATIONS_NOTE] ?: false,
            push = PushMemory(
                p[PUSH_REGISTERED] ?: false,
                p[PUSH_LIMITED_UNTIL] ?: 0,
                p[PUSH_UNREGISTER_PENDING] ?: false,
                p[PUSH_CONFIRM_PENDING] ?: false,
            ),
        )
    }

    override suspend fun setNotificationMode(mode: NotificationMode) {
        store.edit { it[NOTIFICATION_MODE] = mode.name }
    }

    override suspend fun setNotificationPreviews(previews: NotificationPreviews) {
        store.edit { it[NOTIFICATION_PREVIEWS] = previews.name }
    }

    override suspend fun setNotificationsNoteShown() {
        store.edit { it[NOTIFICATIONS_NOTE] = true }
    }

    override suspend fun setPushMemory(m: PushMemory) {
        store.edit {
            it[PUSH_REGISTERED] = m.registered
            it[PUSH_LIMITED_UNTIL] = m.limitedUntilMs
            it[PUSH_UNREGISTER_PENDING] = m.unregisterPending
            it[PUSH_CONFIRM_PENDING] = m.confirmPending
        }
    }

    override suspend fun setAppLockMethod(method: AppLockMethod) {
        store.edit { it[APP_LOCK_METHOD] = method.name }
    }

    override suspend fun acknowledgeReset() {
        store.edit { it.remove(PreferencesFiles.RESET) }
    }

    override suspend fun setReleaseNoticeDismissed(d: ReleaseNoticeDismissal) {
        store.edit {
            it[RELEASE_DISMISSED] = d.release
            it[RELEASE_DISMISSED_KIND] = d.kind
            it[RELEASE_DISMISSED_AT] = d.atMs
            it[RELEASE_DISMISSED_FOR] = d.forMs
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
        val RELEASE_DISMISSED_FOR = longPreferencesKey("release_notice_dismissed_for_ms")
        val RELEASE_NOTIFIED = longPreferencesKey("release_notified")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications_asked")
        val NOTIFICATION_MODE = stringPreferencesKey("notification_mode")
        val NOTIFICATION_PREVIEWS = stringPreferencesKey("notification_previews")
        val NOTIFICATIONS_NOTE = booleanPreferencesKey("notifications_note_shown")
        val PUSH_REGISTERED = booleanPreferencesKey("push_registered")
        val PUSH_LIMITED_UNTIL = longPreferencesKey("push_limited_until_ms")
        val PUSH_UNREGISTER_PENDING = booleanPreferencesKey("push_unregister_pending")
        val PUSH_CONFIRM_PENDING = booleanPreferencesKey("push_confirm_pending")
    }
}

/** In memory (tests and previews). */
@Suppress("TooManyFunctions")
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

    override suspend fun setNotificationMode(mode: NotificationMode) = state.update { it.copy(notificationMode = mode) }

    override suspend fun setNotificationPreviews(previews: NotificationPreviews) = state.update { it.copy(notificationPreviews = previews) }

    override suspend fun setNotificationsNoteShown() = state.update { it.copy(notificationsNoteShown = true) }

    override suspend fun setPushMemory(m: PushMemory) = state.update { it.copy(push = m) }

    override suspend fun acknowledgeReset() = state.update { it.copy(wasReset = false) }

    override suspend fun clear() {
        state.value = AppPreferences()
    }
}
