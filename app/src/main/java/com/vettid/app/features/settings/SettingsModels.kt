package com.vettid.app.features.settings

/**
 * App theme options.
 */
enum class AppTheme(val displayName: String) {
    AUTO("Auto (follow system)"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * App lock method options.
 */
enum class LockMethod(val displayName: String) {
    PIN("PIN (4-6 digits)"),
    BIOMETRIC("Biometrics"),
    BOTH("Both (biometrics + PIN)")
}

/**
 * Auto-lock timeout options.
 */
enum class AutoLockTimeout(val minutes: Int, val displayName: String) {
    IMMEDIATE(0, "Immediately"),
    ONE_MINUTE(1, "After 1 minute"),
    FIVE_MINUTES(5, "After 5 minutes"),
    FIFTEEN_MINUTES(15, "After 15 minutes"),
    THIRTY_MINUTES(30, "After 30 minutes"),
    NEVER(-1, "Never")
}

/**
 * App settings state.
 */
data class AppSettingsState(
    val theme: AppTheme = AppTheme.AUTO,
    val appLockEnabled: Boolean = false,
    val lockMethod: LockMethod = LockMethod.BIOMETRIC,
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.FIVE_MINUTES,
    val screenshotsEnabled: Boolean = false,
    val screenLockEnabled: Boolean = true
)

/**
 * Effects from settings changes.
 */
sealed class SettingsEffect {
    data class ShowSuccess(val message: String) : SettingsEffect()
    data class ShowError(val message: String) : SettingsEffect()
    object NavigateToSetupPin : SettingsEffect()
    object NavigateToChangePassword : SettingsEffect()
    object NavigateToRecoveryPhrase : SettingsEffect()
}
