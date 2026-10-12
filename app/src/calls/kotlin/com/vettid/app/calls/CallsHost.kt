package com.vettid.app.calls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.vettid.app.BuildConfig
import com.vettid.core.ui.theme.Spacing
import com.vettid.feature.calls.CallOverlay
import com.vettid.feature.calls.CallTest
import com.vettid.feature.calls.CallTestScreen

/**
 * The open app with calls (CALLS-PLAN 0.1.1 C1): a call covers the app's screens while it is live or has just
 * ended. It is composed only where the open vault's shell is (inside the app lock and the unlocked destination), so
 * a call screen is never in front of a locked app or of the unlock screen.
 *
 * In a build made for the two-phone tests (`BuildConfig.CALLS_TEST`) a banner says so on every screen and opens the
 * test screen. `CALLS_TEST` is a compile-time constant: in any other build this branch, the test screen and its
 * marker text are not in the APK (`:app:checkStagingApk` looks for the marker).
 */
@Composable
fun CallsHost(content: @Composable () -> Unit) {
    if (!BuildConfig.CALLS_TEST) {
        CallOverlay(content = content)
        return
    }
    var tests by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().clickable { tests = true }.testTag("calltest_banner")) {
            Text(
                "${CallTest.MARKER}. Not for members. Tap for the call tests.",
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.statusBarsPadding().padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        }
        Box(Modifier.weight(1f)) {
            CallOverlay(content = content)
            if (tests) CallTestScreen(onBack = { tests = false })
        }
    }
}
