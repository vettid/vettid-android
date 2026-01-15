package com.vettid.app.features.enrollment

import com.vettid.app.core.nats.UtkInfo
import com.vettid.app.core.network.EnrollmentQRData
import com.vettid.app.core.network.TransactionKeyPublic

/**
 * Enrollment flow state machine
 *
 * Legacy Flow: Initial → ScanningQR/ManualEntry → Attesting → SettingPassword → Finalizing → Complete
 *
 * Nitro Enclave Flow (new):
 * Initial → ScanningQR → ProcessingInvite → ConnectingToNats → RequestingAttestation →
 * SettingPin → WaitingForVault → SettingPassword → CreatingCredential → VerifyingEnrollment → Complete
 */
sealed class EnrollmentState {
    /** Initial state before enrollment starts */
    object Initial : EnrollmentState()

    /** Scanning QR code for invitation */
    data class ScanningQR(
        val error: String? = null
    ) : EnrollmentState()

    /** Manual entry of invitation code */
    data class ManualEntry(
        val inviteCode: String = "",
        val error: String? = null
    ) : EnrollmentState()

    /** Processing QR code, calling enroll/start */
    data class ProcessingInvite(
        val qrData: EnrollmentQRData
    ) : EnrollmentState()

    /** Connecting to NATS with bootstrap credentials (Nitro flow) */
    data class ConnectingToNats(
        val message: String = "Connecting to secure vault..."
    ) : EnrollmentState()

    /** Requesting attestation from enclave supervisor via NATS (Nitro flow) */
    data class RequestingAttestation(
        val message: String = "Verifying enclave security...",
        val progress: Float = 0f
    ) : EnrollmentState()

    /** Performing hardware attestation (legacy flow) */
    data class Attesting(
        val sessionId: String,
        val challenge: ByteArray,
        val transactionKeys: List<TransactionKeyPublic>,
        val passwordKeyId: String,
        val progress: Float = 0f
    ) : EnrollmentState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Attesting) return false
            return sessionId == other.sessionId &&
                    challenge.contentEquals(other.challenge) &&
                    transactionKeys == other.transactionKeys &&
                    passwordKeyId == other.passwordKeyId &&
                    progress == other.progress
        }

        override fun hashCode(): Int {
            var result = sessionId.hashCode()
            result = 31 * result + challenge.contentHashCode()
            result = 31 * result + transactionKeys.hashCode()
            result = 31 * result + passwordKeyId.hashCode()
            result = 31 * result + progress.hashCode()
            return result
        }
    }

    /** User setting up 6-digit PIN (Nitro flow) */
    data class SettingPin(
        val pin: String = "",
        val confirmPin: String = "",
        val isSubmitting: Boolean = false,
        val error: String? = null,
        val attestationInfo: AttestationInfo? = null
    ) : EnrollmentState()

    /** Waiting for vault to be ready with UTKs (Nitro flow) */
    data class WaitingForVault(
        val message: String = "Initializing your secure vault...",
        val progress: Float = 0f
    ) : EnrollmentState()

    /** Attestation complete, waiting for user to set password */
    data class SettingPassword(
        val sessionId: String,
        val transactionKeys: List<TransactionKeyPublic>,
        val passwordKeyId: String, // Key ID to use for password encryption
        val password: String = "",
        val confirmPassword: String = "",
        val strength: PasswordStrength = PasswordStrength.WEAK,
        val isSubmitting: Boolean = false,
        val error: String? = null,
        // For Nitro flow - UTKs from vault ready
        val utks: List<UtkInfo> = emptyList(),
        val isNitroFlow: Boolean = false
    ) : EnrollmentState()

    /** Creating credential via NATS (Nitro flow) */
    data class CreatingCredential(
        val message: String = "Creating your secure credential...",
        val progress: Float = 0f
    ) : EnrollmentState()

    /** Verifying enrollment with test operation (Nitro flow) */
    data class VerifyingEnrollment(
        val message: String = "Verifying enrollment...",
        val progress: Float = 0f
    ) : EnrollmentState()

    /** Finalizing enrollment (legacy flow) */
    data class Finalizing(
        val sessionId: String,
        val progress: Float = 0f
    ) : EnrollmentState()

    /** Enrollment successfully completed */
    data class Complete(
        val userGuid: String,
        val vaultStatus: String = "enrolled"
    ) : EnrollmentState()

    /** Error occurred (may be retryable) */
    data class Error(
        val message: String,
        val retryable: Boolean = true,
        val previousState: EnrollmentState? = null
    ) : EnrollmentState()
}

/**
 * Password strength levels
 */
enum class PasswordStrength {
    WEAK,
    FAIR,
    GOOD,
    STRONG;

    companion object {
        // Common weak passwords to reject (exact matches only)
        private val commonPasswords = setOf(
            "password", "qwerty", "qwerty12", "abc123", "letmein", "welcome",
            "monkey", "dragon", "master", "login", "admin"
        )

        fun calculate(password: String): PasswordStrength {
            if (password.length < 8) return WEAK

            // Check for exact match with common passwords (case-sensitive)
            // This allows "Password" to be rated based on its character diversity
            if (commonPasswords.contains(password)) return WEAK

            var score = 0
            var charTypeCount = 0

            // Length score
            when {
                password.length >= 16 -> score += 2
                password.length >= 12 -> score += 1
            }

            // Character class checks - count types for diversity requirement
            val hasLower = password.any { it.isLowerCase() }
            val hasUpper = password.any { it.isUpperCase() }
            val hasDigit = password.any { it.isDigit() }
            val hasSpecial = password.any { !it.isLetterOrDigit() }

            if (hasLower) { score += 1; charTypeCount++ }
            if (hasUpper) { score += 1; charTypeCount++ }
            if (hasDigit) { score += 1; charTypeCount++ }
            if (hasSpecial) { score += 2; charTypeCount++ }

            // Require at least 2 character types to be above WEAK
            if (charTypeCount < 2) return WEAK

            // Consecutive characters penalty
            val hasConsecutive = (0 until password.length - 2).any { i ->
                password[i] == password[i + 1] && password[i + 1] == password[i + 2]
            }
            if (hasConsecutive) score -= 1

            return when {
                score >= 6 -> STRONG
                score >= 4 -> GOOD
                score >= 2 -> FAIR
                else -> WEAK
            }
        }
    }
}

/**
 * Events that can be sent to the enrollment flow
 */
sealed class EnrollmentEvent {
    /** Start QR scanning */
    object StartScanning : EnrollmentEvent()

    /** Switch to manual code entry */
    object SwitchToManualEntry : EnrollmentEvent()

    /** Switch back to QR scanning */
    object SwitchToScanning : EnrollmentEvent()

    /** Manual invite code changed */
    data class InviteCodeChanged(val inviteCode: String) : EnrollmentEvent()

    /** Submit manually entered invite code (JSON data) */
    object SubmitInviteCode : EnrollmentEvent()

    /** QR code scanned - contains raw JSON data */
    data class QRCodeScanned(val qrData: String) : EnrollmentEvent()

    /** Attestation completed */
    data class AttestationComplete(val success: Boolean) : EnrollmentEvent()

    /** PIN field updated (Nitro flow) */
    data class PinChanged(val pin: String) : EnrollmentEvent()

    /** Confirm PIN field updated (Nitro flow) */
    data class ConfirmPinChanged(val confirmPin: String) : EnrollmentEvent()

    /** Submit PIN (Nitro flow) */
    object SubmitPin : EnrollmentEvent()

    /** Password field updated */
    data class PasswordChanged(val password: String) : EnrollmentEvent()

    /** Confirm password field updated */
    data class ConfirmPasswordChanged(val confirmPassword: String) : EnrollmentEvent()

    /** Submit password */
    object SubmitPassword : EnrollmentEvent()

    /** Retry after error */
    object Retry : EnrollmentEvent()

    /** Cancel enrollment */
    object Cancel : EnrollmentEvent()
}

/**
 * Side effects from enrollment flow
 */
sealed class EnrollmentEffect {
    /** Navigate to next screen */
    data class Navigate(val route: String) : EnrollmentEffect()

    /** Show toast message */
    data class ShowToast(val message: String) : EnrollmentEffect()

    /** Trigger biometric prompt for attestation */
    object TriggerAttestation : EnrollmentEffect()

    /** Enrollment complete - navigate to main app */
    object NavigateToMain : EnrollmentEffect()
}

/**
 * Information about verified enclave attestation
 */
data class AttestationInfo(
    /** Enclave module ID (instance + enclave ID) */
    val moduleId: String,
    /** Attestation timestamp */
    val timestamp: Long,
    /** PCR0 hash (enclave image) - truncated for display */
    val pcr0Short: String,
    /** Whether all PCR values matched */
    val pcrsVerified: Boolean,
    /** PCR version used for verification */
    val pcrVersion: String? = null,
    /** Full PCR0 hash for display */
    val pcr0Full: String? = null
)
