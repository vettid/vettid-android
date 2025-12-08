package com.vettid.app.features.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Handles biometric authentication (Fingerprint / Face) with security hardening
 *
 * Security features:
 * - Detects biometric enrollment changes (invalidates keys)
 * - Enforces authentication timeout
 * - Uses hardware-backed crypto operations
 * - Tracks failed authentication attempts
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val biometricManager = BiometricManager.from(context)
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "biometric_security_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val BIOMETRIC_KEY_ALIAS = "vettid_biometric_key"
        private const val PREF_FAILED_ATTEMPTS = "failed_biometric_attempts"
        private const val PREF_LAST_AUTH_TIME = "last_auth_time"
        private const val PREF_LOCKOUT_UNTIL = "lockout_until"

        // Security thresholds
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30 * 60 * 1000L // 30 minutes
        private const val AUTH_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes for sensitive operations
    }

    // MARK: - Biometric Enrollment Change Detection

    /**
     * Generate or retrieve biometric-protected key
     * This key is invalidated when biometric enrollment changes
     */
    fun setupBiometricKey(): Boolean {
        return try {
            if (!keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
                createBiometricKey()
            }
            // Test if key is still valid
            isBiometricKeyValid()
        } catch (e: Exception) {
            false
        }
    }

    private fun createBiometricKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val builder = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true) // Key invalidated on enrollment change

        // Require authentication for each use on API 30+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0, // No timeout - require auth each time
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        }

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    /**
     * Check if biometric key is still valid (not invalidated by enrollment change)
     */
    fun isBiometricKeyValid(): Boolean {
        return try {
            val key = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey
                ?: return false

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            true
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Biometric enrollment changed - key is invalid
            keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
            false
        } catch (e: UserNotAuthenticatedException) {
            // Key exists but needs authentication - this is expected
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Handle biometric enrollment change
     * Returns true if enrollment changed since last setup
     */
    fun hasBiometricEnrollmentChanged(): Boolean {
        if (!keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
            return false // No key to check
        }
        return !isBiometricKeyValid()
    }

    /**
     * Reset biometric key after enrollment change
     */
    fun resetBiometricKey() {
        if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
            keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
        }
        createBiometricKey()
        // Reset failed attempts
        securePrefs.edit()
            .putInt(PREF_FAILED_ATTEMPTS, 0)
            .putLong(PREF_LOCKOUT_UNTIL, 0)
            .apply()
    }

    // MARK: - Failed Attempt Tracking

    /**
     * Check if user is locked out due to too many failed attempts
     */
    fun isLockedOut(): Boolean {
        val lockoutUntil = securePrefs.getLong(PREF_LOCKOUT_UNTIL, 0)
        if (lockoutUntil > System.currentTimeMillis()) {
            return true
        }
        // Reset lockout if expired
        if (lockoutUntil > 0) {
            securePrefs.edit()
                .putLong(PREF_LOCKOUT_UNTIL, 0)
                .putInt(PREF_FAILED_ATTEMPTS, 0)
                .apply()
        }
        return false
    }

    /**
     * Get remaining lockout time in milliseconds
     */
    fun getRemainingLockoutTime(): Long {
        val lockoutUntil = securePrefs.getLong(PREF_LOCKOUT_UNTIL, 0)
        val remaining = lockoutUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }

    private fun recordFailedAttempt() {
        val attempts = securePrefs.getInt(PREF_FAILED_ATTEMPTS, 0) + 1
        securePrefs.edit().putInt(PREF_FAILED_ATTEMPTS, attempts).apply()

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            securePrefs.edit()
                .putLong(PREF_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_DURATION_MS)
                .apply()
        }
    }

    private fun recordSuccessfulAuth() {
        securePrefs.edit()
            .putInt(PREF_FAILED_ATTEMPTS, 0)
            .putLong(PREF_LOCKOUT_UNTIL, 0)
            .putLong(PREF_LAST_AUTH_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Check if recent authentication is still valid (within timeout)
     */
    fun isRecentAuthValid(): Boolean {
        val lastAuth = securePrefs.getLong(PREF_LAST_AUTH_TIME, 0)
        return System.currentTimeMillis() - lastAuth < AUTH_TIMEOUT_MS
    }

    // MARK: - Biometric Availability

    /**
     * Check what biometric capabilities are available
     */
    fun getBiometricCapability(): BiometricCapability {
        // First check lockout
        if (isLockedOut()) {
            return BiometricCapability.LOCKED_OUT
        }

        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricCapability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricCapability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricCapability.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricCapability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricCapability.SECURITY_UPDATE_REQUIRED
            else -> BiometricCapability.UNKNOWN
        }
    }

    /**
     * Check if biometric authentication is available
     */
    fun isBiometricAvailable(): Boolean {
        return getBiometricCapability() == BiometricCapability.AVAILABLE
    }

    // MARK: - Authentication

    /**
     * Authenticate the user with biometrics
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock VettID",
        subtitle: String = "Use your biometric credential",
        negativeButtonText: String = "Cancel"
    ): BiometricAuthResult = suspendCancellableCoroutine { continuation ->

        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) {
                    recordSuccessfulAuth()
                    continuation.resume(BiometricAuthResult.Success)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) {
                    val error = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BiometricAuthError.CANCELLED
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricAuthError.LOCKOUT
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> BiometricAuthError.NOT_ENROLLED
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE -> BiometricAuthError.HARDWARE_UNAVAILABLE
                        else -> BiometricAuthError.UNKNOWN
                    }
                    continuation.resume(BiometricAuthResult.Error(error, errString.toString()))
                }
            }

            override fun onAuthenticationFailed() {
                // Called on each failed attempt, but authentication continues
                // Track failed attempts
                recordFailedAttempt()
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo)

        continuation.invokeOnCancellation {
            biometricPrompt.cancelAuthentication()
        }
    }

    /**
     * Authenticate with fallback to device credential (PIN/Pattern/Password)
     */
    suspend fun authenticateWithFallback(
        activity: FragmentActivity,
        title: String = "Unlock VettID",
        subtitle: String = "Use biometric or device credential"
    ): BiometricAuthResult = suspendCancellableCoroutine { continuation ->

        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) {
                    continuation.resume(BiometricAuthResult.Success)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) {
                    val error = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BiometricAuthError.CANCELLED
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricAuthError.LOCKOUT
                        else -> BiometricAuthError.UNKNOWN
                    }
                    continuation.resume(BiometricAuthResult.Error(error, errString.toString()))
                }
            }

            override fun onAuthenticationFailed() {
                // Continue waiting
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo)

        continuation.invokeOnCancellation {
            biometricPrompt.cancelAuthentication()
        }
    }
}

// MARK: - Types

enum class BiometricCapability {
    AVAILABLE,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NOT_ENROLLED,
    SECURITY_UPDATE_REQUIRED,
    LOCKED_OUT,
    UNKNOWN
}

sealed class BiometricAuthResult {
    object Success : BiometricAuthResult()
    data class Error(val error: BiometricAuthError, val message: String) : BiometricAuthResult()
}

enum class BiometricAuthError {
    CANCELLED,
    LOCKOUT,
    NOT_ENROLLED,
    HARDWARE_UNAVAILABLE,
    UNKNOWN
}
