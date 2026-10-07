package com.vettid.core.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Confirms that the phone's owner is holding it before something sensitive is shown (a secret item's values,
 * VAULT-MESSAGING §10.7: "revealed on purpose"): the phone's biometric or screen lock, through BiometricPrompt
 * (ANDROID-PLAN D6). A convenience layer only, like the app lock: it never replaces the vault PIN or the credential
 * password. True when confirmed, or when the phone has neither a biometric nor a screen lock to confirm with.
 */
fun interface UserPresence {
    suspend fun confirm(title: String, subtitle: String?): Boolean
}

/** The activity provides the BiometricPrompt one; previews, tests and the screen catalog confirm at once. */
val LocalUserPresence = staticCompositionLocalOf { UserPresence { _, _ -> true } }
