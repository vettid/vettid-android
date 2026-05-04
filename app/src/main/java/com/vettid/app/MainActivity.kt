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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.vettid.app.core.security.RuntimeProtection
import com.vettid.app.core.security.SecureClipboard
import com.vettid.app.core.storage.AppPreferencesStore
import com.vettid.app.features.feed.FeedNotificationService
import com.vettid.app.features.settings.AppTheme
import com.vettid.app.ui.theme.VettIDTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Deep link data extracted from intent.
 */
data class DeepLinkData(
    val type: DeepLinkType,
    val code: String? = null,
    val eventType: String? = null,
    val sourceId: String? = null
)

enum class DeepLinkType {
    ENROLL,
    CONNECT,
    TRANSFER_APPROVE,
    VOTE,
    NOTIFICATION,
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

    @Inject
    lateinit var callManager: com.vettid.app.features.calling.CallManager

    // Mutable state accessible from onNewIntent to trigger recomposition
    private var deepLinkState = mutableStateOf(DeepLinkData(DeepLinkType.NONE))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO: RE-ENABLE FLAG_SECURE before release — disabled temporarily for demo recording
        // window.setFlags(
        //     WindowManager.LayoutParams.FLAG_SECURE,
        //     WindowManager.LayoutParams.FLAG_SECURE
        // )

        // Perform runtime security check
        performSecurityCheck()

        // Only extract the deep-link data on the very first launch.
        // Activity gets recreated on config changes (rotation, theme,
        // dark-mode) and that fired the original enrollment URI a
        // second time → a 401 from /enroll/authenticate (token already
        // consumed) → the user lands on the QR scanner with a
        // "something went wrong" toast. The non-null savedInstanceState
        // is Android's signal that this is a recreation; in that case
        // we keep whatever deepLinkState was already (initial NONE
        // or whatever onNewIntent has since set).
        if (savedInstanceState == null) {
            deepLinkState.value = extractDeepLinkData(intent)
            // Belt-and-braces: drop the data off the intent so any
            // future getIntent() reads after a process death also
            // see it as already-consumed.
            intent?.data = null
        }

        setContent {
            val currentDeepLink by deepLinkState
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
                        callManager = callManager,
                        deepLinkData = currentDeepLink,
                        onDeepLinkConsumed = { deepLinkState.value = DeepLinkData(DeepLinkType.NONE) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-extract deep link data — triggers recomposition via deepLinkState
        deepLinkState.value = extractDeepLinkData(intent)
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
     * - vettid://votes?id=xxx
     * - https://vettid.dev/votes/xxx
     * - https://vettid.dev/votes?id=xxx
     */
    private fun extractDeepLinkData(intent: Intent?): DeepLinkData {
        // Check for notification tap extras (from FeedNotificationService)
        if (intent?.getBooleanExtra(FeedNotificationService.EXTRA_OPEN_VOTES, false) == true) {
            // Open-votes notification → land on the proposals list
            intent.removeExtra(FeedNotificationService.EXTRA_OPEN_VOTES)
            return DeepLinkData(type = DeepLinkType.VOTE)
        }
        if (intent?.getBooleanExtra(FeedNotificationService.EXTRA_OPEN_FEED, false) == true) {
            val eventType = intent.getStringExtra(FeedNotificationService.EXTRA_EVENT_TYPE)
            val sourceId = intent.getStringExtra(FeedNotificationService.EXTRA_SOURCE_ID)
            val eventId = intent.getStringExtra(FeedNotificationService.EXTRA_EVENT_ID)
            // Clear the extras so we don't re-process on config change
            intent.removeExtra(FeedNotificationService.EXTRA_OPEN_FEED)
            if (BuildConfig.DEBUG) {
                android.util.Log.d("VettID-DeepLink", "Notification tap: eventType=$eventType, sourceId=$sourceId")
            }
            return DeepLinkData(
                type = DeepLinkType.NOTIFICATION,
                code = eventId,
                eventType = eventType,
                sourceId = sourceId
            )
        }

        val uri = intent?.data ?: return DeepLinkData(DeepLinkType.NONE)

        // SECURITY: Only log deep link type, not parameters (may contain tokens/codes)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("VettID-DeepLink", "Received URI: scheme=${uri.scheme}, host=${uri.host}, path=${uri.path}")
        }

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
            // Custom scheme: vettid://connect?c=xxx (broker code — preferred),
            // vettid://connect?code=xxx, or vettid://connect?data=xxx (base64 JSON).
            uri.scheme == "vettid" && uri.host == "connect" -> {
                val data = uri.getQueryParameter("data")
                val brokerCode = uri.getQueryParameter("c") ?: uri.getQueryParameter("code")
                DeepLinkData(
                    type = DeepLinkType.CONNECT,
                    code = data?.let { decodeBase64IfNeeded(it) }
                        ?: brokerCode?.let { wrapBrokerCodeAsJson(it) }
                )
            }
            // Custom scheme: vettid://votes?id=xxx (Issue #50: Vault-based voting)
            uri.scheme == "vettid" && uri.host == "votes" -> {
                val proposalId = uri.getQueryParameter("id")
                DeepLinkData(
                    type = DeepLinkType.VOTE,
                    code = proposalId
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
            // HTTPS:
            //   https://vettid.dev/connect?c=<broker-code>  (preferred — share link from Android)
            //   https://vettid.dev/connect/<broker-code>    (path-segment variant)
            //   https://vettid.dev/connect?data=<base64>    (legacy inline format)
            uri.host == "vettid.dev" && uri.pathSegments.firstOrNull() == "connect" -> {
                val data = uri.getQueryParameter("data")
                val brokerCode = uri.getQueryParameter("c")
                    ?: uri.pathSegments.getOrNull(1)
                DeepLinkData(
                    type = DeepLinkType.CONNECT,
                    code = data?.let { decodeBase64IfNeeded(it) }
                        ?: brokerCode?.let { wrapBrokerCodeAsJson(it) }
                )
            }
            // HTTPS: https://vettid.dev/votes/xxx or https://vettid.dev/votes?id=xxx
            uri.host == "vettid.dev" && uri.pathSegments.firstOrNull() == "votes" -> {
                val proposalId = uri.getQueryParameter("id") ?: uri.pathSegments.getOrNull(1)
                DeepLinkData(
                    type = DeepLinkType.VOTE,
                    code = proposalId
                )
            }
            else -> DeepLinkData(DeepLinkType.NONE)
        }
    }

    /**
     * The ScanInvitationViewModel's scan pipeline expects a JSON blob
     * like {"c":"<code>","e":"<endpoint>"} — the same shape QR codes
     * carry. Share links use the bare broker code as a query param
     * (?c=<code>) to keep the URL short and linkifiable; wrap it back
     * into that JSON form here so downstream code is unchanged.
     */
    private fun wrapBrokerCodeAsJson(code: String): String =
        """{"c":"$code"}"""

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

        if (!result.isSecure) {
            android.util.Log.w("VettID-Security", "Security threats detected: ${result.threats.size} threat(s)")

            // SECURITY: Block app for critical threats (debugger, Frida, tampering)
            if (runtimeProtection.hasCriticalThreats()) {
                android.util.Log.e("VettID-Security", "Critical security threat detected - blocking app")
                android.widget.Toast.makeText(
                    this,
                    "Security threat detected. VettID cannot run in this environment.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                finish()
                return
            }
        }
    }
}
