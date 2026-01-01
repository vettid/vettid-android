package com.vettid.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.vettid.app.core.security.RuntimeProtection
import com.vettid.app.core.security.SecureClipboard
import com.vettid.app.ui.theme.VettIDTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Deep link data extracted from intent.
 */
data class DeepLinkData(
    val type: DeepLinkType,
    val code: String? = null
)

enum class DeepLinkType {
    ENROLL,
    CONNECT,
    NONE
}

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

        // Extract deep link data
        val deepLinkData = extractDeepLinkData(intent)

        setContent {
            var currentDeepLink by remember { mutableStateOf(deepLinkData) }

            VettIDTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VettIDApp(
                        deepLinkData = currentDeepLink,
                        onDeepLinkConsumed = { currentDeepLink = DeepLinkData(DeepLinkType.NONE) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Deep link will be re-processed on next composition
    }

    /**
     * Extract deep link data from the intent.
     * Supports:
     * - vettid://enroll?code=xxx
     * - https://vettid.dev/enroll/xxx
     * - vettid://connect?code=xxx
     * - https://vettid.dev/connect/xxx
     */
    private fun extractDeepLinkData(intent: Intent?): DeepLinkData {
        val uri = intent?.data ?: return DeepLinkData(DeepLinkType.NONE)

        return when {
            // Custom scheme: vettid://enroll?code=xxx
            uri.scheme == "vettid" && uri.host == "enroll" -> {
                DeepLinkData(
                    type = DeepLinkType.ENROLL,
                    code = uri.getQueryParameter("code")
                )
            }
            // Custom scheme: vettid://connect?code=xxx or vettid://connect?data=xxx (base64 JSON)
            uri.scheme == "vettid" && uri.host == "connect" -> {
                // Support both code (short code) and data (base64-encoded JSON)
                val data = uri.getQueryParameter("data")
                val code = uri.getQueryParameter("code")
                DeepLinkData(
                    type = DeepLinkType.CONNECT,
                    code = data ?: code  // Prefer data if present
                )
            }
            // HTTPS: https://vettid.dev/enroll/xxx
            uri.host == "vettid.dev" && uri.pathSegments.firstOrNull() == "enroll" -> {
                DeepLinkData(
                    type = DeepLinkType.ENROLL,
                    code = uri.pathSegments.getOrNull(1)
                )
            }
            // HTTPS: https://vettid.dev/connect/xxx
            uri.host == "vettid.dev" && uri.pathSegments.firstOrNull() == "connect" -> {
                DeepLinkData(
                    type = DeepLinkType.CONNECT,
                    code = uri.pathSegments.getOrNull(1)
                )
            }
            else -> DeepLinkData(DeepLinkType.NONE)
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
