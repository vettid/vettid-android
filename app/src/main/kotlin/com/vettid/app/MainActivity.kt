package com.vettid.app

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
import com.vettid.core.data.account.SignInLinkInbox
import com.vettid.core.data.social.InviteLinkInbox
import com.vettid.core.data.vault.CanaryManifestInbox
import com.vettid.core.data.social.InviteLinks
import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.prefs.ThemePreference
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.ui.theme.ThemeMode
import com.vettid.core.ui.theme.VettIdTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    lateinit var inbox: SignInLinkInbox

    @Inject
    lateinit var invites: InviteLinkInbox

    @Inject
    lateinit var canaryManifests: CanaryManifestInbox

    private var prompting = false

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
            CompositionLocalProvider(LocalThemeController provides controller) {
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
     * before anything is sent; a sign-in link (account.vettid.org `/auth/`) to
     * onboarding, where only confirmed sign-ins send it. A shared file is a canary manifest
     * ([receiveCanaryManifest]).
     */
    private fun receiveLink(intent: Intent?) {
        when (intent?.action) {
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

    override fun onStop() {
        appLock.onBackground()
        super.onStop()
    }

    // --- biometric app lock (D6): BiometricPrompt, class 3 or the device credential, with a CryptoObject ---

    private fun promptUnlock() {
        if (prompting) return
        lifecycleScope.launch {
            val cipher = appLock.cipherToUnlock() ?: return@launch
            authenticate(cipher) { c -> appLock.completeUnlock(c) }
        }
    }

    @Suppress("ReturnCount")
    private fun promptEnable() {
        if (prompting) return
        val cipher = try {
            appLock.cipherToEnable()
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
        authenticate(cipher) { c -> lifecycleScope.launch { appLock.completeEnable(c) } }
    }

    private fun enableFailed() {
        Toast.makeText(this, R.string.app_lock_enable_failed, Toast.LENGTH_LONG).show()
    }

    private fun authenticate(cipher: Cipher, onSuccess: (Cipher) -> Unit) {
        prompting = true
        appLock.authenticating = true
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(OnboardingR.string.app_lock_prompt_title))
            .setSubtitle(getString(OnboardingR.string.app_lock_prompt_subtitle))
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    done()
                    result.cryptoObject?.cipher?.let(onSuccess)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = done()

                private fun done() {
                    prompting = false
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
