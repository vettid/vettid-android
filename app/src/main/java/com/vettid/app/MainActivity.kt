package com.vettid.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.vettid.app.core.security.RuntimeProtection
import com.vettid.app.core.security.SecureClipboard
import com.vettid.app.ui.theme.VettIDTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var runtimeProtection: RuntimeProtection

    @Inject
    lateinit var secureClipboard: SecureClipboard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply FLAG_SECURE to prevent screenshots/screen recording
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Perform runtime security check
        performSecurityCheck()

        setContent {
            VettIDTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VettIDApp()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Clear clipboard when app goes to background
        secureClipboard.onAppBackground()
    }

    private fun performSecurityCheck() {
        val result = runtimeProtection.performSecurityCheck()

        // Log threats for debugging (in production, handle appropriately)
        if (!result.isSecure) {
            // In production, you might want to:
            // 1. Show a warning dialog for non-critical threats
            // 2. Restrict functionality for critical threats
            // 3. Block app entirely for very critical threats (debugger, Frida)
            android.util.Log.w("VettID-Security", "Security threats detected: ${result.threats}")

            // Check for critical threats that should block the app
            if (runtimeProtection.hasCriticalThreats()) {
                android.util.Log.e("VettID-Security", "Critical security threat detected!")
                // In production, consider finishing the activity or showing error screen
            }
        }
    }
}
