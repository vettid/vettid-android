package com.vettid.feature.onboarding

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseView
import com.vettid.core.ui.theme.VettIdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Hosts the screens under test over the keyguard (the test phone stays locked). */
class LockedHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }
}

private object NoUnlockActions : UnlockActions {
    override fun retryPreflight() = Unit
    override fun acknowledgeUpdate() = Unit
    override fun setApproveOffer(approve: Boolean) = Unit
    override fun setPin(v: String) = Unit
    override fun submit() = Unit
    override fun cancelRecoveryAndUnlock() = Unit
    override fun signOut() = Unit
    override fun askErase() = Unit
    override fun dismissErase() = Unit
    override fun confirmErase() = Unit
}

@RunWith(AndroidJUnit4::class)
class OnboardingScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<LockedHostActivity>()

    private fun release(n: Long) = ReleaseView(n, "%02d".format(n).repeat(48), "active", null, "https://vettid.org/r/$n")

    @Test
    fun backupOffNeedsTheAcknowledgementBeforeContinuing() {
        var backup by mutableStateOf(true)
        var ack by mutableStateOf(false)
        rule.setContent {
            VettIdTheme {
                FormScreenForBackup(backup, ack, { backup = it }, { ack = it })
            }
        }
        rule.onNodeWithTag("primary_button").assertIsEnabled()
        rule.onNodeWithTag("backup_off").performClick()
        rule.onNodeWithText("Losing this phone loses your credential").assertExists()
        rule.onNodeWithTag("primary_button").assertIsNotEnabled()
        rule.onNodeWithTag("backup_off_ack").performClick()
        rule.onNodeWithTag("primary_button").assertIsEnabled()
    }

    @Test
    fun rollbackHidesThePinField() {
        rule.setContent {
            VettIdTheme {
                UnlockContent(UnlockUiState(loading = false, preflight = PreflightInfo(release(2), 3, false, true, null)), NoUnlockActions)
            }
        }
        rule.onNodeWithText("Older vault software").assertExists()
        rule.onNodeWithTag("unlock_pin").assertDoesNotExist()
    }

    @Test
    fun softwareUpdateNoticeDisablesThePinUntilAcknowledged() {
        rule.setContent {
            VettIdTheme {
                UnlockContent(UnlockUiState(loading = false, preflight = PreflightInfo(release(4), 3, true, false, null)), NoUnlockActions)
            }
        }
        rule.onNodeWithTag("software_updated").assertExists()
        rule.onNodeWithTag("unlock_pin").assertIsNotEnabled()
    }

    @Test
    fun eraseIsOfferedOnlyWhenTheVaultDidNotRecogniseThisPhone() {
        val calls = mutableListOf<String>()
        val actions = object : UnlockActions by NoUnlockActions {
            override fun askErase() { calls += "ask" }
            override fun dismissErase() { calls += "dismiss" }
            override fun confirmErase() { calls += "confirm" }
        }
        val preflight = PreflightInfo(release(3), 3, false, false, null)
        var state by mutableStateOf(UnlockUiState(loading = false, preflight = preflight))
        rule.setContent { VettIdTheme { UnlockContent(state, actions) } }
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()

        state = state.copy(message = UnlockMessage.Failed(com.vettid.core.data.vault.FailureKind.OTHER, UnlockViewModel.CODE_UNREADABLE),
            notRecognised = true)
        rule.onNodeWithTag("unlock_not_recognised").assertExists()
        rule.onNodeWithTag("unlock_failed").assertDoesNotExist()
        rule.onNodeWithText("Erase VettID from this phone").performClick()
        assertEquals(listOf("ask"), calls)

        state = state.copy(eraseConfirm = true)
        rule.onNodeWithText("Erase VettID from this phone?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("confirm_button").assertTextEquals("Erase").performClick()
        assertEquals(listOf("ask", "dismiss", "confirm"), calls)

        state = state.copy(eraseConfirm = false, erasing = true)
        rule.onNodeWithTag("erase_phone").assertIsNotEnabled()
    }
}

@androidx.compose.runtime.Composable
private fun FormScreenForBackup(backup: Boolean, ack: Boolean, onBackup: (Boolean) -> Unit, onAck: (Boolean) -> Unit) {
    com.vettid.core.ui.components.FormScaffold(
        title = "Backup",
        primaryLabel = "Continue",
        onPrimary = {},
        primaryEnabled = backup || ack,
    ) {
        BackupChoice(backup, ack, onBackup, onAck)
    }
}
