package com.vettid.core.data.lock

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.hardware.biometrics.BiometricManager.Authenticators
import android.os.SystemClock
import com.vettid.core.data.prefs.AppLockMethod
import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.keystore.AndroidKeys
import com.vettid.core.keystore.AppDataKey
import com.vettid.core.keystore.KeystoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher

/** The app lock's state (ANDROID-PLAN D6). */
enum class AppLockState {
    /** Preferences not read yet. */
    PENDING,

    /** The app lock is off. */
    DISABLED,

    /** On, and the member has not authenticated since the app started or timed out. */
    LOCKED,

    /** On, and the member authenticated since the app started or timed out. */
    UNLOCKED,
}

/** The app lock's cover is up in [this] state: locked, or not yet known (fails closed). */
fun AppLockState.coversApp(): Boolean = this == AppLockState.LOCKED || this == AppLockState.PENDING

/**
 * What BiometricPrompt allows for [this] method: a class 3 biometric or the phone's screen lock, or the screen lock
 * only (BiometricPrompt with a CryptoObject and DEVICE_CREDENTIAL alone needs API 30; minSdk is 31).
 */
fun AppLockMethod.authenticators(): Int = when (this) {
    AppLockMethod.BIOMETRICS -> Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
    AppLockMethod.SCREEN_LOCK -> Authenticators.DEVICE_CREDENTIAL
}

/**
 * An alarm for the cover's deadline (owner decision 2026-11-02, the frozen-process case): while the app is in the
 * background with the lock on, something wakes the app at [set]'s time so that notifications showing names are replaced
 * even when the process's timer does not run (deep sleep, a frozen cached process). Inexact is fine: the app looks at its
 * clock again when it is woken. [AppLock] drives it; the Android one is in the app module.
 */
interface CoverAlarm {
    /**
     * Wake the app at about [atElapsedMs] on the monotonic clock (`SystemClock.elapsedRealtime()`, as `AlarmManager.ELAPSED_REALTIME`,
     * not waking the phone); replaces an earlier one. A date set back moves no monotonic deadline (the lead's decision of 2026-11-03).
     */
    fun set(atElapsedMs: Long)

    fun cancel()

    companion object {
        val NONE: CoverAlarm = object : CoverAlarm {
            override fun set(atElapsedMs: Long) = Unit

            override fun cancel() = Unit
        }
    }
}

/**
 * The user-authenticated Keystore key (`:core:keystore` [AppDataKey]); an interface so tests can use a software key.
 * A new key is made as a pending one ([createPending]) beside the current key and replaces it only at [promote], after
 * its proof worked: a cancelled method change keeps the old key.
 */
interface AppLockKeys {
    /** A fresh pending key for [method] (biometric or screen lock, or the screen lock only); the current key is untouched. */
    fun createPending(method: AppLockMethod)

    /** A cipher of the pending key, to authenticate with BiometricPrompt before [promote]. */
    fun pendingCipher(): Cipher

    /** The slot (0 or 1) of the pending key: what the preferences record as the current slot once the change succeeded. */
    fun pendingSlot(): Int

    /** Call after the preferences named the pending slot: the pending key becomes the current one and the old key is deleted. */
    fun promote()

    /** The pending key is deleted (the change was cancelled or failed); the current key stays. */
    fun discardPending()

    /**
     * At start, with the slot the preferences name ([markedSlot], 0 when never written): that slot's key is the current one
     * and the other slot is deleted (a pending key a dead process left, or the old key after a crash following the write).
     */
    fun recover(markedSlot: Int)

    /** The current key exists (a pending one does not count). */
    fun exists(): Boolean

    /** A cipher whose use proves a fresh authentication (BiometricPrompt CryptoObject; the key needs one per use). */
    fun encryptCipher(): Cipher

    /** Deletes the current and the pending key. */
    fun delete()
}

/** One of the two Keystore slots of the app-lock key: a seam over [AppDataKey] so that the slot logic runs in JVM tests. */
interface AppLockKeySlot {
    fun exists(): Boolean

    /** A fresh key for [method], replacing any in this slot. */
    fun create(method: AppLockMethod)

    fun cipher(): Cipher

    fun delete()
}

/** [AppLockKeySlot] on the Android Keystore alias of [key]: a key per use, the biometric one invalidated by a new biometric. */
class AppDataKeySlot(private val key: AppDataKey) : AppLockKeySlot {
    override fun exists() = key.exists()

    override fun create(method: AppLockMethod) {
        key.create(authTimeoutSeconds = 0, allowDeviceCredential = true, allowBiometric = method == AppLockMethod.BIOMETRICS)
    }

    override fun cipher(): Cipher = key.encryptCipher()

    override fun delete() = key.delete()
}

/**
 * [AppLockKeys] on the Android Keystore, per use: [AppLockMethod.BIOMETRICS] class 3 biometric or device credential,
 * invalidated by a new biometric; [AppLockMethod.SCREEN_LOCK] the device credential only. Two slots (slot 0 is the alias
 * the app always used); the current one is named by the persisted marker `appLockKeySlot`, which [recover] is given at start.
 */
class KeystoreAppLockKeys(
    private val slots: List<AppLockKeySlot>,
) : AppLockKeys {
    constructor() : this(
        listOf(AppDataKeySlot(AppDataKey()), AppDataKeySlot(AppDataKey(AndroidKeys.ALIAS_APP_DATA_NEXT))),
    )

    init {
        require(slots.size == 2)
    }

    /** The slot of the current key (the marker, set by [recover] and [promote]). */
    @Volatile
    private var currentSlot = 0

    /** The slot of the pending key, in memory only. */
    @Volatile
    private var pending: Int? = null

    private fun current(): AppLockKeySlot = slots[currentSlot]

    override fun createPending(method: AppLockMethod) {
        discardPending()
        val slot = 1 - currentSlot
        pending = slot
        try {
            slots[slot].create(method)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            discardPending()
            throw e
        }
    }

    override fun pendingCipher(): Cipher = slots[pending ?: error("no pending app-lock key")].cipher()

    override fun pendingSlot(): Int = pending ?: error("no pending app-lock key")

    override fun promote() {
        val p = pending ?: return
        slots[currentSlot].delete()
        currentSlot = p
        pending = null
    }

    override fun discardPending() {
        pending?.let { slots[it].delete() }
        pending = null
    }

    override fun recover(markedSlot: Int) {
        pending = null
        val marked = markedSlot.coerceIn(0, 1)
        currentSlot = marked
        val other = 1 - marked
        if (slots[marked].exists()) {
            slots[other].delete() // a pending key of a dead process, or the old key left by a crash after the marker was written
        } else if (slots[other].exists()) {
            currentSlot = other // the marker names no key but the other slot has one: keep the lock usable
        }
    }

    override fun exists(): Boolean = current().exists()

    override fun encryptCipher(): Cipher = current().cipher()

    override fun delete() {
        slots.forEach { it.delete() }
        pending = null
    }
}

/**
 * Where builds before 2026-10-27 kept a random "app-data key" wrapped under the lock's Keystore key (a file in no-backup
 * storage). Nothing used that key (A6 audit, 2026-10-27), so it is gone; [AppLock.start] only deletes the stored file.
 */
interface WrappedKeyFile {
    fun delete()
}

class FileWrappedKeyFile(private val file: File) : WrappedKeyFile {
    override fun delete() {
        file.delete()
    }
}

/**
 * The app lock (ANDROID-PLAN D6; 0.1.19: "App lock" with a [method]). When on, opening the app, and
 * returning to it after [AppLockTimeout], asks through BiometricPrompt for a class 3 biometric or the
 * device credential ([AppLockMethod.BIOMETRICS]) or the device credential only ([AppLockMethod.SCREEN_LOCK]),
 * whose CryptoObject must then encrypt a short fixed proof: the Keystore key is usable only right after
 * an authentication, so a cipher that works is the proof. A UI gate only: it protects no data (there is no
 * app-data key; the one earlier builds wrapped was never used), never replaces the vault PIN or the credential
 * password, and nothing of the vault depends on it.
 *
 * The activity hands the ciphers from [cipherToEnable] / [cipherToUnlock] to
 * BiometricPrompt and the authenticated cipher back to [completeEnable] /
 * [completeUnlock]. A biometric enrolled since a [AppLockMethod.BIOMETRICS] key was made invalidates
 * it: the lock then turns itself off ([invalidated]) and the member can turn
 * it on again. A screen-lock key that no longer opens (the screen lock was removed) turns the lock off too,
 * without that notice.
 */
@Suppress("TooManyFunctions")
class AppLock(
    private val prefs: PreferencesRepository,
    private val keys: AppLockKeys,
    private val obsoleteWrappedKey: WrappedKeyFile,
    private val coverAlarm: CoverAlarm = CoverAlarm.NONE,
    /** The monotonic clock (`SystemClock.elapsedRealtime()`): it measures the time in the background. Pass both clocks by name. */
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
    /** The wall clock: only compared with the monotonic one since [onBackground], to see that it was set back. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val stateFlow = MutableStateFlow(AppLockState.PENDING)
    val state: StateFlow<AppLockState> = stateFlow.asStateFlow()

    private val invalidatedFlow = MutableStateFlow(false)

    /** True once the key was invalidated by a biometric change and the lock turned itself off (Biometrics only). */
    val invalidated: StateFlow<Boolean> = invalidatedFlow.asStateFlow()

    private val methodFlow = MutableStateFlow(AppLockMethod.BIOMETRICS)

    /** How the lock asks; the chosen one also while the lock is off. */
    val method: StateFlow<AppLockMethod> = methodFlow.asStateFlow()

    /** The method of the key [cipherToEnable] made, saved by [completeEnable]. */
    private var enabling: AppLockMethod? = null

    /** When the app went to the background: on the monotonic clock (the duration) and on the wall clock (a date set back). */
    private class Background(val elapsedMs: Long, val wallMs: Long) {
        /** Sticky: the wall clock went back at some look since the background; only a new background or the return clears it. */
        @Volatile
        var wentBack: Boolean = false
    }

    private val backgroundAtFlow = MutableStateFlow<Background?>(null)
    private var backgroundAt: Background?
        get() = backgroundAtFlow.value
        set(v) {
            backgroundAtFlow.value = v
        }

    /** Bumped when the system says the wall clock changed, so [coverFlow] looks again at once. */
    private val clockChanges = MutableStateFlow(0)
    private val timeoutFlow = MutableStateFlow(AppLockTimeout.DEFAULT)

    /** One prompt at a time, and no automatic prompt after one the member dismissed ([UnlockPromptGate]). */
    val prompts = UnlockPromptGate()

    /**
     * True while a prompt is showing ([promptStarted] to [promptEnded] / [abandonPrompt]); the device-credential screen sends
     * the app to the background. The background mark is still recorded meanwhile ([onBackground]) but never cleared
     * ([onForeground] leaves it), and its end decides ([promptEnded]). [wouldShowLock] ignores it: a prompt left up does not keep
     * names showing in notifications past the timeout.
     */
    @Volatile
    var authenticating: Boolean = false
        private set

    /** When a prompt succeeded with the app not yet in the foreground (monotonic): the next return within the grace is that unlock. */
    private var provenAtElapsed: Long? = null

    /** The prompt in progress is an enable or a method change ([promptStarted]); only that prompt's end skips the lock decision. */
    private var enablePrompt = false

    /** Reads the preference at start: an enabled lock starts locked. Always leaves [AppLockState.PENDING]. */
    suspend fun start() {
        val p = prefs.preferences.first()
        timeoutFlow.value = p.appLockTimeout
        methodFlow.value = p.appLockMethod ?: AppLockMethod.BIOMETRICS
        prompts.newLock()
        runCatching { keys.recover(p.appLockKeySlot) }
        // The wrapped app-data key of earlier builds was never used: deleted at the next start (2026-10-27).
        runCatching { obsoleteWrappedKey.delete() }
        // A key that is gone is a lock that cannot open: off, never "pending" for good (the start screen would
        // stay; A6 audit P4). The lock is a convenience and locks nobody out.
        val usable = p.appLockEnabled && keys.exists()
        stateFlow.value = if (usable) AppLockState.LOCKED else AppLockState.DISABLED
        // Bookkeeping last: a preference that cannot be written must not keep the lock pending.
        try {
            // A lock turned on before the method could be chosen was the biometric one: it stays "Biometrics".
            if (p.appLockMethod == null && p.appLockEnabled) prefs.setAppLockMethod(AppLockMethod.BIOMETRICS)
            if (p.appLockEnabled && !usable) prefs.setAppLockEnabled(false)
        } catch (_: IOException) {
            // Read again at the next start.
        }
    }

    fun setTimeout(t: AppLockTimeout) {
        timeoutFlow.value = t
        syncCoverAlarm()
    }

    /**
     * The alarm is set while the lock is on and unlocked, the app is in the background and the timeout is above zero (with
     * "Immediately" the cover applies at once), for the monotonic [backgroundAt] + the timeout; otherwise it is cancelled. Called after
     * every change of those inputs. The alarm is a help: a failure to set it breaks nothing.
     */
    private fun syncCoverAlarm() {
        val at = backgroundAt
        val seconds = timeoutFlow.value.seconds
        runCatching {
            if (stateFlow.value == AppLockState.UNLOCKED && at != null && seconds > 0) {
                coverAlarm.set(at.elapsedMs + seconds * MS)
            } else {
                coverAlarm.cancel()
            }
        }
    }

    /**
     * A fresh key for [method] and its encrypt cipher, to authenticate with BiometricPrompt (with
     * [AppLockMethod.authenticators]) before [completeEnable]. Also how the method of a lock that is on changes.
     */
    fun cipherToEnable(method: AppLockMethod = methodFlow.value): Cipher {
        enabling = null // set only once the cipher is there: a failure leaves no half-made change behind
        try {
            keys.createPending(method)
            val cipher = keys.pendingCipher()
            enabling = method
            return cipher
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            runCatching { keys.discardPending() }
            throw e
        }
    }

    /**
     * The prompt of [cipherToEnable] was cancelled or failed: the pending key is deleted and the lock stays as it was
     * (a first enable stays off with no key left over; a change of method keeps the old method and the old key).
     */
    fun enableCancelled() {
        if (enabling == null) return
        enabling = null
        runCatching { keys.discardPending() }
    }

    /**
     * Whether opening the app now would show the lock (owner decision 2026-11-02, for notifications): the lock is
     * [AppLockState.LOCKED] or not yet known, or it is on, the app is in the background and has been for the timeout or
     * longer, or the wall clock went backwards since then ([backgroundDue], the lead's decision of 2026-11-03). The same
     * decision [onForeground] uses (the lock itself engages only there); [AppLockState.DISABLED] is never covered.
     */
    fun wouldShowLock(): Boolean = when (stateFlow.value) {
        AppLockState.LOCKED, AppLockState.PENDING -> true
        AppLockState.DISABLED -> false
        AppLockState.UNLOCKED -> backgroundAt?.let { backgroundDue(it, timeoutFlow.value.seconds * MS) } == true
    }

    /**
     * The one decision of [onForeground] and [wouldShowLock]: the lock is due when the monotonic time since [at] has reached
     * the timeout, or the wall clock went back (strict reading, fail secure): the wall time since [at] is shorter than the
     * monotonic time by more than [WALL_BACK_TOLERANCE_MS] (so that a clock correction of a few seconds by the network does
     * not lock a phone that was away a moment). That is remembered in [Background.wentBack]: a date set back and then
     * restored still locks. A date set forward does not lock by itself: the duration is monotonic. The monotonic clock restarts at
     * a reboot, but a reboot ends the process and the next one starts [AppLockState.LOCKED].
     */
    private fun backgroundDue(at: Background, timeoutMs: Long): Boolean =
        elapsed().minus(at.elapsedMs).let { mono ->
            if (now() - at.wallMs < mono - WALL_BACK_TOLERANCE_MS) at.wentBack = true
            at.wentBack || mono >= timeoutMs
        }

    /** The wall clock was changed (`ACTION_TIME_CHANGED`): [coverFlow] decides again now, not at its timer. */
    fun onClockChanged() {
        backgroundAt?.let { backgroundDue(it, timeoutFlow.value.seconds * MS) } // remembers a step back before it is undone
        recheck()
    }

    /** [coverFlow] decides again now. */
    private fun recheck() {
        clockChanges.value++
    }

    /**
     * [wouldShowLock] as a flow, true until the state is read. In the background it turns true by itself when the timeout
     * elapses (a timer in [scope], cancelled when the app comes back or the lock is turned off); it does not survive the
     * process, whose next start is [AppLockState.LOCKED] anyway.
     *
     * A wall clock set back during the wait is seen at once through [onClockChanged] (the `TIME_CHANGED` receiver);
     * if that broadcast is late or never comes, it is seen when the timer looks again at the monotonic deadline.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun coverFlow(scope: CoroutineScope): StateFlow<Boolean> =
        combine(stateFlow, backgroundAtFlow, timeoutFlow, clockChanges) { s, at, t, _ -> Triple(s, at, t) }
            .transformLatest { (s, at, t) ->
                when {
                    s.coversApp() -> emit(true)
                    s != AppLockState.UNLOCKED || at == null -> emit(false)
                    else -> {
                        val left = at.elapsedMs + t.seconds * MS - elapsed()
                        if (backgroundDue(at, t.seconds * MS)) {
                            emit(true)
                        } else {
                            emit(false)
                            delay(left + COVER_MARGIN_MS)
                            // A prompt in progress no longer holds the cover back. A timer that fires early all the same (a clock
                            // set back, a test clock) looks again rather than loses the cover for good, backing off 1 s to 60 s.
                            var wait = COVER_RETRY_MS
                            while (!wouldShowLock()) {
                                val more = at.elapsedMs + t.seconds * MS - elapsed()
                                delay(if (more > 0) minOf(more + COVER_MARGIN_MS, wait) else wait)
                                wait = minOf(wait * 2, COVER_RETRY_MAX_MS)
                            }
                            emit(true)
                        }
                    }
                }
            }.stateIn(scope, SharingStarted.Eagerly, true)

    /** Chooses the method while the lock is off (it applies when the lock is turned on). */
    suspend fun chooseMethod(method: AppLockMethod) {
        if (stateFlow.value == AppLockState.UNLOCKED || stateFlow.value == AppLockState.LOCKED) return
        prefs.setAppLockMethod(method)
        methodFlow.value = method
    }

    /** What BiometricPrompt allows for the current method. */
    fun authenticators(): Int = methodFlow.value.authenticators()

    /** Checks the authenticated [cipher] works (the proof of the authentication) and turns the lock on. */
    suspend fun completeEnable(cipher: Cipher) {
        try {
            cipher.doFinal(PROOF)
        } catch (e: GeneralSecurityException) {
            enableCancelled()
            throw KeystoreException("app lock: not authenticated", e)
        }
        // A late success for an enable that was already dropped (cancelled, abandoned, destroyed): nothing pending to promote.
        val m = enabling ?: return
        try {
            // One edit: enabled, method and the slot of the new key. A crash after it leaves both keys; recover keeps the marked one.
            prefs.setAppLock(true, m, keys.pendingSlot())
        } catch (e: IOException) {
            enableCancelled()
            throw e
        }
        enabling = null
        keys.promote() // only now is the old key deleted
        methodFlow.value = m
        invalidatedFlow.value = false
        stateFlow.value = AppLockState.UNLOCKED
        syncCoverAlarm()
    }

    /**
     * The cipher to authenticate, or null when the lock cannot be used
     * any more (key invalidated by a new biometric, or gone): the
     * lock is then turned off.
     */
    suspend fun cipherToUnlock(): Cipher? {
        // The "new fingerprint or face" notice is the biometric method's only.
        val notice = methodFlow.value == AppLockMethod.BIOMETRICS
        if (!keys.exists()) {
            disable(invalidated = notice)
            return null
        }
        return try {
            keys.encryptCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable(invalidated = notice)
            null
        } catch (_: KeystoreException) {
            disable(invalidated = notice)
            null
        }
    }

    /** Checks the authenticated [cipher] works. Returns false if it does not (the authentication did not happen). */
    fun completeUnlock(cipher: Cipher): Boolean {
        return try {
            cipher.doFinal(PROOF)
            stateFlow.value = AppLockState.UNLOCKED
            syncCoverAlarm()
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalStateException) {
            false // a cipher that was never initialised
        }
    }

    /** Turns the lock off: deletes the key. */
    suspend fun disable(invalidated: Boolean = false) {
        keys.delete()
        prefs.setAppLockEnabled(false)
        invalidatedFlow.value = invalidated
        stateFlow.value = AppLockState.DISABLED
        syncCoverAlarm()
    }

    /** As on a fresh install (the wipe of a replaced phone): off, no key, the default timeout. */
    suspend fun reset() {
        disable()
        methodFlow.value = AppLockMethod.BIOMETRICS
        enabling = null
        timeoutFlow.value = AppLockTimeout.DEFAULT
        backgroundAt = null
        authenticating = false
        provenAtElapsed = null
        enablePrompt = false
        syncCoverAlarm()
        recheck()
    }

    fun acknowledgeInvalidated() {
        invalidatedFlow.value = false
    }

    /** Locks now. */
    fun lockNow() = lockNow(automaticPrompt = true)

    private fun lockNow(automaticPrompt: Boolean) {
        if (stateFlow.value != AppLockState.UNLOCKED) return
        if (automaticPrompt) prompts.newLock() else prompts.holdAutomatic()
        stateFlow.value = AppLockState.LOCKED
        syncCoverAlarm()
    }

    /**
     * The activity stopped. Always recorded (owner rule 2026-11-03: a Home press during a prompt must not lose the
     * background), except when the activity is only being recreated for a configuration change ([changingConfigurations]:
     * rotation, fold, theme), which with "Immediately" would lock the app in the member's hands. While a prompt is up an
     * earlier mark is kept (the first stop counts) and the mark is never cleared; the prompt's end decides ([promptEnded]).
     */
    fun onBackground(changingConfigurations: Boolean = false) {
        if (changingConfigurations) return
        if (!authenticating) provenAtElapsed = null
        if (!(authenticating && backgroundAt != null)) backgroundAt = Background(elapsed(), now())
        syncCoverAlarm()
    }

    /** The activity is in the foreground again. While a prompt is up the mark is left alone: [promptEnded] decides. */
    fun onForeground() {
        if (authenticating) return
        returned()
    }

    /**
     * The shared decision of a return: lock if the mark is due, then clear it. A return within [PROMPT_RETURN_GRACE_MS] of
     * a prompt that succeeded while the app was not in the foreground is that unlock, not a leaving: no lock.
     */
    private fun returned() {
        val at = backgroundAt
        val proven = provenAtElapsed
        provenAtElapsed = null
        if (at == null) return
        val grace = proven != null && elapsed() - proven <= PROMPT_RETURN_GRACE_MS
        // Locked before the background time is cleared: the cover (wouldShowLock) has no gap between the two.
        if (!grace && stateFlow.value == AppLockState.UNLOCKED && backgroundDue(at, timeoutFlow.value.seconds * MS)) {
            // Locking just after a prompt that succeeded while the app was stopped (its onStart later than the grace): no
            // automatic prompt, so a prompt that stops the app can never loop (lock, prompt, lock); the member taps Unlock once.
            lockNow(automaticPrompt = proven == null)
        }
        backgroundAt = null
        syncCoverAlarm()
    }

    /** A prompt is about to show (BiometricPrompt, the app lock's or a presence check). */
    fun promptStarted(enable: Boolean = false) {
        enablePrompt = enable
        authenticating = true
    }

    /**
     * The prompt ended (owner rule 2026-11-03: the mark is always recorded, kept while a prompt is up, and the end decides).
     * [inForeground]: the activity is at least STARTED.
     * - [PromptOutcome.SUCCEEDED] in the foreground: the member was just proven present, the mark is cleared.
     * - [PromptOutcome.SUCCEEDED] not in the foreground (the device-credential screen's callback can come before the
     *   activity's onStart): the mark is set to now, and the next [onForeground] within [PROMPT_RETURN_GRACE_MS] clears it
     *   without locking, so "Immediately" does not lock the app straight after an unlock. Later, the normal decision applies.
     * - [PromptOutcome.SYSTEM_CANCELED] or [PromptOutcome.DISMISSED] in the foreground: the decision of [onForeground]: lock
     *   if the mark is due, then clear it (fail secure; with "Immediately", cancelling a presence check whose screen stopped
     *   the activity therefore locks the app).
     * - The prompt of an enable or a method change ([promptStarted] with `enable`) that did not succeed also drops its pending
     *   key ([enableCancelled]); the lock stays as it was, and the decision above applies to it like to any prompt.
     * - Either of those not in the foreground: the mark is kept; if there is none, one is recorded now.
     */
    fun promptEnded(outcome: PromptOutcome, inForeground: Boolean) {
        authenticating = false
        val enabled = enablePrompt
        enablePrompt = false
        if (enabled && outcome != PromptOutcome.SUCCEEDED) enableCancelled() // a cancel, a failure, or the activity gone
        when {
            outcome == PromptOutcome.SUCCEEDED && inForeground -> {
                provenAtElapsed = null
                backgroundAt = null
            }
            outcome == PromptOutcome.SUCCEEDED -> {
                backgroundAt = Background(elapsed(), now())
                provenAtElapsed = elapsed()
            }
            inForeground -> returned()
            else -> if (backgroundAt == null) backgroundAt = Background(elapsed(), now())
        }
        syncCoverAlarm()
        recheck()
    }

    /** The prompt's activity was destroyed for a configuration change: no outcome, the mark stays as it is. */
    fun abandonPrompt() {
        authenticating = false
        if (enablePrompt) enableCancelled() // its cancel callback will not come
        enablePrompt = false
        recheck()
    }

    private companion object {
        /** What the authenticated cipher encrypts: not a secret, only a use of the key that needs the authentication. */
        val PROOF = "vettid/app-lock/1".toByteArray()
        const val MS = 1000L

        /** The timer fires a little late rather than a clock tick early: [wouldShowLock] must be true when it does. */
        const val COVER_MARGIN_MS = 100L
        const val COVER_RETRY_MS = 1000L
        const val COVER_RETRY_MAX_MS = 60_000L

        /** A return this soon after a prompt succeeded in the background is that unlock (the late onStart), not a leaving. */
        const val PROMPT_RETURN_GRACE_MS = 3_000L

        /** A wall clock this much (or less) behind the one at the background is a correction, not a date set back. */
        const val WALL_BACK_TOLERANCE_MS = 5_000L
    }
}
