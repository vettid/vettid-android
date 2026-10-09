package com.vettid.app

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vettid.app.debug.debugTools
import com.vettid.app.ui.LocalThemeController
import com.vettid.app.ui.ThemeController
import com.vettid.app.ui.VettIdApp
import com.vettid.core.data.account.SetupLinkInbox
import com.vettid.core.data.social.InviteLinkInbox
import com.vettid.core.data.vault.CanaryManifestInbox
import com.vettid.core.data.vault.ReleaseUpdateInbox
import com.vettid.app.notify.ReleaseUpdateNotifier
import com.vettid.core.data.social.InviteLinks
import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.lock.PromptOutcome
import com.vettid.core.data.lock.authenticators
import com.vettid.core.data.prefs.AppLockMethod
import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.prefs.ThemePreference
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.ui.components.LocalUserPresence
import com.vettid.core.ui.components.UserPresence
import com.vettid.core.ui.theme.ThemeMode
import com.vettid.core.ui.theme.VettIdTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.inject.Inject
import com.vettid.feature.onboarding.R as OnboardingR

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var appLock: AppLock

    @Inject
    lateinit var prefs: PreferencesRepository

    @Inject
    lateinit var inbox: SetupLinkInbox

    @Inject
    lateinit var invites: InviteLinkInbox

    @Inject
    lateinit var canaryManifests: CanaryManifestInbox

    @Inject
    lateinit var releaseUpdates: ReleaseUpdateInbox

    @Inject
    lateinit var notificationTaps: com.vettid.core.notify.NotificationOpenInbox

    /** The CancellationSignal of a prompt this activity shows ([AppLock.prompts] says whether one is in flight). */
    private var promptCancel: CancellationSignal? = null

    /** Secret items are revealed after the phone's biometric or screen lock (ANDROID-PLAN D6; [UserPresence]). */
    private val presence = UserPresence { title, subtitle -> confirmPresence(title, subtitle) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        debugTools.onLaunch(this, intent)
        if (savedInstanceState == null) receiveLink(intent)
        val launchTheme = debugTools.themeOverride(intent)
        val launchRoute = debugTools.startRoute(intent)
        val catalog = debugTools.catalogScreen(intent)
        setContent {
            val p by prefs.preferences.collectAsStateWithLifecycle(AppPreferences())
            val themeMode = launchTheme ?: p.theme.toMode()
            val dark = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            val controller = remember(themeMode) {
                ThemeController(themeMode) { m -> lifecycleScope.launch { prefs.setTheme(m.toPreference()) } }
            }
            CompositionLocalProvider(LocalThemeController provides controller, LocalUserPresence provides presence) {
                VettIdTheme(themeMode = themeMode) {
                    if (catalog != null) {
                        catalog()
                    } else {
                        VettIdApp(onUnlockApp = ::promptUnlock, onEnableAppLock = ::promptEnable, launchRoute = launchRoute)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveLink(intent)
    }

    /**
     * A link the app was opened with: an invitation (`<relay>/connect#…` App Link or
     * `vettid://connect#…`, §6.4) goes to the connect flow, which asks the member
     * before anything is sent; the account portal's setup link (`/vault/enroll/#s=…`, VAULT-MESSAGING §11.12.1) to
     * onboarding, which redeems it only while this phone has no vault. A shared file is a canary manifest
     * ([receiveCanaryManifest]). The "Vault updates" notification opens the update screen.
     */
    private fun receiveLink(intent: Intent?) {
        when (intent?.action) {
            // A tap on the "Vault updates" notification: the update screen, once the vault is open.
            ReleaseUpdateNotifier.ACTION_OPEN_UPDATE -> releaseUpdates.offer()
            // A feed item's notification (ANDROID-PLAN 0.1.23): the shell marks it read and opens its target.
            com.vettid.core.notify.NotifyIntents.ACTION_OPEN_ITEM ->
                intent.getStringExtra(com.vettid.core.notify.NotifyIntents.EXTRA_ITEM_ID)?.let {
                    notificationTaps.offer(com.vettid.core.notify.NotificationOpenInbox.Open.Item(it))
                }
            // The on-phone service's own notification: Settings → Notifications.
            com.vettid.core.notify.NotifyIntents.ACTION_OPEN_SETTINGS ->
                notificationTaps.offer(com.vettid.core.notify.NotificationOpenInbox.Open.Settings)
            Intent.ACTION_SEND -> receiveCanaryManifest(intent)
            Intent.ACTION_VIEW -> intent.dataString?.let { data ->
                if (InviteLinks.isConnectUri(data)) invites.offer(data) else inbox.offer(data)
            }
        }
    }

    /**
     * A file shared to the app as `application/json`: a canary manifest (VAULT-RELEASES §10.1 step 9) for the
     * root of the UI, which verifies it and asks the member before anything is installed. Read here, bounded
     * (a served manifest is at most 90,112 bytes); anything larger is refused unread.
     */
    private fun receiveCanaryManifest(intent: Intent) {
        @Suppress("DEPRECATION") // getParcelableExtra(String, Class) needs API 33; minSdk is 31
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { CanaryManifestInbox.readBounded(it) }
                }.getOrNull()
            }
            if (bytes != null) canaryManifests.offer(bytes)
        }
    }

    override fun onStart() {
        super.onStart()
        appLock.onForeground()
    }

    override fun onDestroy() {
        // A prompt this activity showed ends with it; its callback will not come (a recreated activity may ask again).
        promptCancel?.cancel()
        promptCancel = null
        if (appLock.prompts.ownerGone(this)) appLock.authenticating = false
        super.onDestroy()
    }

    override fun onStop() {
        appLock.onBackground()
        super.onStop()
    }

    // --- the app lock (D6; 0.1.19: Biometrics, or Phone screen lock only): BiometricPrompt with a CryptoObject ---

    /**
     * The app lock's prompt: [auto] when the lock shows or the app returns while locked, false from "Unlock". One at a
     * time ([com.vettid.core.data.lock.UnlockPromptGate]): a prompt already showing (or restored) is never doubled,
     * and none starts by itself after the member cancelled one. Its device-credential fallback (the phone's PIN)
     * unlocks like a biometric: the same authenticated cipher.
     */
    private fun promptUnlock(auto: Boolean) {
        if (appLock.state.value != com.vettid.core.data.lock.AppLockState.LOCKED) return
        if (!appLock.prompts.tryStart(auto, this)) return
        lifecycleScope.launch {
            val cipher = appLock.cipherToUnlock()
            if (cipher == null) {
                appLock.prompts.finished(PromptOutcome.DISMISSED)
                return@launch
            }
            authenticate(cipher, appLock.method.value) { c -> appLock.completeUnlock(c) }
        }
    }

    /** Turns the lock on with [method], or changes the method of a lock that is on (a new key, asked for at once). */
    @Suppress("ReturnCount")
    private fun promptEnable(method: AppLockMethod) {
        if (!appLock.prompts.tryStart(auto = false, owner = this)) return
        if (method == AppLockMethod.SCREEN_LOCK && getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            appLock.prompts.finished(PromptOutcome.DISMISSED)
            enableFailed() // no screen lock on this phone (Settings says so before offering the choice)
            return
        }
        val cipher = try {
            appLock.cipherToEnable(method)
        } catch (_: GeneralSecurityException) {
            enableFailed()
            return
        } catch (_: KeystoreException) {
            enableFailed()
            return
        } catch (_: IllegalStateException) {
            enableFailed() // no biometric and no screen lock on this phone
            return
        }
        authenticate(cipher, method, onCancel = { lifecycleScope.launch { appLock.enableCancelled() } }) { c ->
            lifecycleScope.launch { appLock.completeEnable(c) }
        }
    }

    /**
     * BiometricPrompt without a key (nothing is decrypted: it only confirms the holder), class 3 or the device
     * credential. A phone with neither confirms at once: the vault PIN opened the app, and nothing else could ask.
     */
    @Suppress("ReturnCount")
    private suspend fun confirmPresence(title: String, subtitle: String?): Boolean {
        val allowed = Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        val bm = getSystemService(android.hardware.biometrics.BiometricManager::class.java)
        if (bm == null || bm.canAuthenticate(allowed) != android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) return true
        if (!appLock.prompts.tryStart(auto = false, owner = this)) return false
        return suspendCancellableCoroutine { cont ->
            appLock.authenticating = true
            val cancel = CancellationSignal()
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(title)
                .apply { subtitle?.let { setSubtitle(it) } }
                .setAllowedAuthenticators(allowed)
                .build()
            fun done(ok: Boolean) {
                appLock.prompts.finished(PromptOutcome.SUCCEEDED) // a presence check never holds back the lock's prompt
                appLock.authenticating = false
                if (cont.isActive) cont.resume(ok)
            }
            cont.invokeOnCancellation { cancel.cancel() }
            prompt.authenticate(
                cancel,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = done(false)
                },
            )
        }
    }

    private fun enableFailed() {
        appLock.prompts.finished(PromptOutcome.DISMISSED)
        Toast.makeText(this, R.string.app_lock_enable_failed, Toast.LENGTH_LONG).show()
    }

    /** The app lock's prompt; the caller passed [AppLock.prompts]' tryStart. */
    private fun authenticate(cipher: Cipher, method: AppLockMethod, onCancel: () -> Unit = {}, onSuccess: (Cipher) -> Unit) {
        appLock.authenticating = true
        val cancel = CancellationSignal()
        promptCancel = cancel
        val subtitle = when (method) {
            AppLockMethod.BIOMETRICS -> OnboardingR.string.app_lock_prompt_subtitle
            AppLockMethod.SCREEN_LOCK -> OnboardingR.string.app_lock_prompt_subtitle_screen_lock
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(OnboardingR.string.app_lock_prompt_title))
            .setSubtitle(getString(subtitle))
            .setAllowedAuthenticators(method.authenticators())
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            cancel,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                // A biometric or the device-credential fallback (the phone's PIN): either unlocks, once.
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    done(PromptOutcome.SUCCEEDED)
                    result.cryptoObject?.cipher?.let(onSuccess)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    done(PromptOutcome.ofError(errorCode))
                    onCancel()
                }

                private fun done(outcome: PromptOutcome) {
                    if (promptCancel === cancel) promptCancel = null
                    appLock.prompts.finished(outcome)
                    appLock.authenticating = false
                }
            },
        )
    }
}

private fun ThemePreference.toMode(): ThemeMode = when (this) {
    ThemePreference.SYSTEM -> ThemeMode.System
    ThemePreference.LIGHT -> ThemeMode.Light
    ThemePreference.DARK -> ThemeMode.Dark
}

private fun ThemeMode.toPreference(): ThemePreference = when (this) {
    ThemeMode.System -> ThemePreference.SYSTEM
    ThemeMode.Light -> ThemePreference.LIGHT
    ThemeMode.Dark -> ThemePreference.DARK
}
