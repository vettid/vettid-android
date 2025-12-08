package com.vettid.app.core.security

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * Base activity with security features applied
 *
 * Security features:
 * - FLAG_SECURE: Prevents screenshots and screen recording
 * - Prevents app from appearing in recent apps with content visible
 */
abstract class SecureActivity : ComponentActivity() {

    /**
     * Override to disable FLAG_SECURE for specific activities
     * Default is true (secure)
     */
    protected open val isSecureScreen: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isSecureScreen) {
            applySecureFlag()
        }
    }

    /**
     * Apply FLAG_SECURE to prevent screenshots/screen recording
     */
    protected fun applySecureFlag() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    /**
     * Remove FLAG_SECURE (use sparingly)
     */
    protected fun removeSecureFlag() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * Temporarily disable secure flag (e.g., to allow user to take screenshot of QR code)
     * Automatically re-enables after action completes
     */
    protected fun withSecureFlagDisabled(action: () -> Unit) {
        if (!isSecureScreen) {
            action()
            return
        }

        removeSecureFlag()
        try {
            action()
        } finally {
            applySecureFlag()
        }
    }
}

/**
 * Extension function to apply FLAG_SECURE to any ComponentActivity
 */
fun ComponentActivity.applySecureWindowFlags() {
    window.setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE
    )
}

/**
 * Extension function to clear FLAG_SECURE from any ComponentActivity
 */
fun ComponentActivity.clearSecureWindowFlags() {
    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
