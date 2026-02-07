package com.vettid.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.vettid.app.core.security.RuntimeProtection
import com.vettid.app.core.security.SecureClipboard
import com.vettid.app.core.storage.AppPreferencesStore
import com.vettid.app.features.settings.AppTheme
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
    TRANSFER_APPROVE,
    NONE
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var runtimeProtection: RuntimeProtection

    @Inject
    lateinit var secureClipboard: SecureClipboard

    @Inject
    lateinit var appPreferencesStore: AppPreferencesStore

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
            val themePreference by appPreferencesStore.themeFlow.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themePreference) {
                AppTheme.AUTO -> systemDark
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }

            VettIDTheme(darkTheme = darkTheme) {
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
     * - vettid://enroll?code=xxx (short code)
     * - vettid://enroll?data=xxx (base64-encoded JSON - clickable link from QR)
     * - https://vettid.dev/enroll/xxx (short code in path)
     * - https://vettid.dev/enroll?data=xxx (base64-encoded JSON - clickable link)
     * - vettid://connect?code=xxx
     * - vettid://connect?data=xxx (base64-encoded JSON)
     * - https://vettid.dev/connect/xxx
     * - https://vettid.dev/connect?data=xxx
     */
    private fun extractDeepLinkData(intent: Intent?): DeepLinkData {
        val uri = intent?.data ?: return DeepLinkData(DeepLinkType.NONE)

        // Debug logging for deep link troubleshooting
        android.util.Log.d("VettID-DeepLink", "Received URI: $uri")
        android.util.Log.d("VettID-DeepLink", "  scheme: ${uri.scheme}")
        android.util.Log.d("VettID-DeepLink", "  host: ${uri.host}")
        android.util.Log.d("VettID-DeepLink", "  path: ${uri.path}")
        android.util.Log.d("VettID-DeepLink", "  query: ${uri.query}")
        android.util.Log.d("VettID-DeepLink", "  data param: ${uri.getQueryParameter("data")}")
        android.util.Log.d("VettID-DeepLink", "  code param: ${uri.getQueryParameter("code")}")

        return when {
            // Custom scheme: vettid://enroll?code=xxx or vettid://enroll?data=xxx
            uri.scheme == "vettid" && uri.host == "enroll" -> {
                // Support both code (short code) and data (base64-encoded JSON)
                val data = uri.getQueryParameter("data")
                val code = uri.getQueryParameter("code")
                DeepLinkData(
                    type = DeepLinkType.ENROLL,
                    code = data?.let { decodeBase64IfNeeded(it) } ?: code
                )
            }
            // Custom scheme: vettid://connect?code=xxx or vettid://connect?data=xxx (base64 JSON)
            uri.scheme == "vettid" && uri.host == "connect" -> {
                // Support both code (short code) and data (base64-encoded JSON)
                val data = uri.getQueryParameter("data")
                val code = uri.getQueryParameter("code")
                DeepLinkData(
                    type = DeepLinkType.CONNECT,
                    code = data?.let { decodeBase64IfNeeded(it) } ?: code
                )
            }
            // Custom scheme: vettid://transfer/approve?id=xxx (Issue #31: Device-to-device transfer)
            uri.scheme == "vettid" && uri.host == "transfer" && uri.pathSegments.firstOrNull() == "approve" -> {
                val transferId = uri.getQueryParameter("id")
                DeepLinkData(
                    type = DeepLinkType.TRANSFER_APPROVE,
                    code = transferId
                )
            }
            // HTTPS: https://vettid.dev/enroll/xxx or https://vettid.dev/enroll?data=xxx
            uri.host == "vettid.dev" && uri.pathSegments.firstOrNull() == "enroll" -> {
                val data = uri.getQueryParameter("data")
                DeepLinkData(
                    type = DeepLinkType.ENROLL,
                    code = data?.let { decodeBase64IfNeeded(it) } ?: uri.pathSegments.getOrNull(1)
                )
            }
            // HTTPS: https://vettid.dev/connect/xxx or https://vettid.dev/connect?data=xxx
            uri.host == "vettid.dev" && uri.pathSegments.firstOrNull() == "connect" -> {
                val data = uri.getQueryParameter("data")
                DeepLinkData(
                    type = DeepLinkType.CONNECT,
                    code = data?.let { decodeBase64IfNeeded(it) } ?: uri.pathSegments.getOrNull(1)
                )
            }
            else -> DeepLinkData(DeepLinkType.NONE)
        }
    }

    /**
     * Decode base64 string if it appears to be base64-encoded.
     * Returns the original string if decoding fails or it's not base64.
     */
    private fun decodeBase64IfNeeded(input: String): String {
        return try {
            // Check if it looks like base64 (only contains valid base64 chars)
            if (input.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
                // Try URL-safe base64 first, then standard
                val decoded = try {
                    android.util.Base64.decode(input, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                } catch (e: Exception) {
                    android.util.Base64.decode(input, android.util.Base64.DEFAULT)
                }
                String(decoded, Charsets.UTF_8)
            } else {
                input
            }
        } catch (e: Exception) {
            // If decoding fails, return original string
            input
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
