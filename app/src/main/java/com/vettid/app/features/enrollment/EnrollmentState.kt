package com.vettid.app.features.enrollment

import com.vettid.app.core.network.TransactionKeyPublic

/**
 * Enrollment flow state machine
 *
 * Flow: Initial → ScanningQR → Attesting → SettingPassword → Finalizing → Complete
 */
sealed class EnrollmentState {
    /** Initial state before enrollment starts */
    object Initial : EnrollmentState()

    /** Scanning QR code for invitation */
    data class ScanningQR(
        val error: String? = null
    ) : EnrollmentState()

    /** Processing QR code, calling enroll/start */
    data class ProcessingInvite(
        val inviteCode: String
    ) : EnrollmentState()

    /** Performing hardware attestation */
    data class Attesting(
        val sessionId: String,
        val challenge: ByteArray,
        val transactionKeys: List<TransactionKeyPublic>,
        val progress: Float = 0f
    ) : EnrollmentState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Attesting) return false
            return sessionId == other.sessionId &&
                    challenge.contentEquals(other.challenge) &&
                    transactionKeys == other.transactionKeys &&
                    progress == other.progress
        }

        override fun hashCode(): Int {
            var result = sessionId.hashCode()
            result = 31 * result + challenge.contentHashCode()
            result = 31 * result + transactionKeys.hashCode()
            result = 31 * result + progress.hashCode()
            return result
        }
    }

    /** Attestation complete, waiting for user to set password */
    data class SettingPassword(
        val sessionId: String,
        val transactionKeys: List<TransactionKeyPublic>,
        val password: String = "",
        val confirmPassword: String = "",
        val strength: PasswordStrength = PasswordStrength.WEAK,
        val isSubmitting: Boolean = false,
        val error: String? = null
    ) : EnrollmentState()

    /** Finalizing enrollment */
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
        fun calculate(password: String): PasswordStrength {
            if (password.length < 8) return WEAK

            var score = 0

            // Length score
            when {
                password.length >= 16 -> score += 2
                password.length >= 12 -> score += 1
            }

            // Character class checks
            if (password.any { it.isLowerCase() }) score += 1
            if (password.any { it.isUpperCase() }) score += 1
            if (password.any { it.isDigit() }) score += 1
            if (password.any { !it.isLetterOrDigit() }) score += 2

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

    /** QR code scanned with invite code */
    data class InviteCodeScanned(val inviteCode: String) : EnrollmentEvent()

    /** Attestation completed */
    data class AttestationComplete(val success: Boolean) : EnrollmentEvent()

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
