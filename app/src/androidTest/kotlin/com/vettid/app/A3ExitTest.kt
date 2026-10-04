package com.vettid.app

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.core.keystore.DeviceAttestationKey
import com.vettid.core.keystore.KeyLevel
import com.vettid.core.testing.DevStack
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The A3 exit test (ANDROID-PLAN §6), on the phone, against the local dev
 * stack (devstack/README.md), with the `devStack` build:
 *
 * `./gradlew -PvettidTestBuildType=devStack :app:connectedDevStackAndroidTest`
 *
 * A fresh install goes through onboarding with a new test member (the dev
 * stack's sign-in stand-in), enrolls a vault with the phone's REAL Keystore
 * attestation (StrongBox; the stack runs with devstack/device-policy.json),
 * creates the Protean Credential, then locks the vault and unlocks it with
 * the PIN, all through the UI. Screenshots of each step go to
 * `/data/local/tmp/a3-exit/` (`adb pull` them). Skipped when the stack is not reachable.
 * The biometric app lock cannot be automated (it needs a finger); see the
 * manual check in devstack/README.md.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class A3ExitTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var shot = 0

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).close()

    /** A screenshot through the shell (`screencap`), kept in [SHOTS] after the test APK is uninstalled. */
    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        shell("screencap -p $SHOTS/%02d-%s.png".format(++shot, name))
        Thread.sleep(SETTLE_MS)
    }

    private fun waitTag(tag: String, timeout: Long = WAIT_MS) = rule.waitUntilAtLeastOneExists(hasTestTag(tag), timeout)

    private fun waitText(text: String, timeout: Long = WAIT_MS) = rule.waitUntilAtLeastOneExists(hasText(text), timeout)

    private fun tag(t: String): SemanticsNodeInteraction = rule.onAllNodes(hasTestTag(t)).onFirst()

    private fun text(t: String): SemanticsNodeInteraction = rule.onAllNodes(hasText(t)).onFirst()

    private fun primary() = tag("primary_button").performClick()

    @Test
    fun freshInstallToEnrolledVaultThenLockAndUnlock() {
        assumeTrue(
            "dev stack not reachable",
            DevStack(BuildConfig.DEV_STACK_API, BuildConfig.DEV_STACK_RELAY, BuildConfig.DEV_STACK_CTL).reachable(),
        )
        shell("rm -rf $SHOTS")
        shell("mkdir -p $SHOTS")
        val email = "a3-exit-${System.currentTimeMillis()}@example.org"
        val intent = Intent(context, MainActivity::class.java).putExtra("vettid.screenshot", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            // Welcome → sign in (the dev stack's stand-in: the token is pasted).
            waitText("Get started")
            screenshot("welcome")
            text("Get started").performClick()
            waitTag("email")
            tag("email").performTextInput(email)
            screenshot("email")
            primary()
            waitTag("link")
            tag("link").performTextInput("devstack-sign-in-token")
            screenshot("check-email")
            primary()
            waitText("Sign in on this phone?")
            screenshot("confirm-sign-in")
            primary()

            // Membership ok → vault PIN, confirmation, credential password, backup.
            waitTag("pin", LONG_WAIT_MS)
            tag("pin").performTextInput(PIN)
            screenshot("pin")
            primary()
            waitTag("pin_confirm")
            tag("pin_confirm").performTextInput(PIN)
            primary()
            waitTag("password")
            tag("password").performTextInput(PASSWORD)
            tag("password_confirm").performTextInput(PASSWORD)
            screenshot("password")
            primary()
            waitTag("backup_on")
            screenshot("backup")
            primary()

            // Enrollment (device attestation, vault.enrolled, handshake, credential, confirm).
            waitTag("progress")
            screenshot("progress")
            waitText("Your vault is ready", ENROLL_WAIT_MS)
            screenshot("done")
            primary()

            // The app: the shell, then Settings → lock the vault.
            waitText("Messages", LONG_WAIT_MS)
            screenshot("shell")
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Open navigation menu")).onFirst().performClick()
            waitText("Settings")
            text("Settings").performClick()
            waitTag("lock_vault")
            screenshot("settings")
            tag("lock_vault").performClick()
            waitTag("confirm_button")
            tag("confirm_button").performClick()

            // Unlock with the PIN (preflight, sealed unlock, attestation assertion).
            waitTag("unlock_pin", LONG_WAIT_MS)
            screenshot("unlock")
            tag("unlock_pin").performTextInput(PIN)
            primary()
            waitText("Messages", LONG_WAIT_MS)
            screenshot("unlocked")

            // The credential exists after the unlock.
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Open navigation menu")).onFirst().performClick()
            waitText("Credential")
            text("Credential").performClick()
            waitText("Version", LONG_WAIT_MS)
            screenshot("credential")

            // Settings → Attestation: this phone's key and the release last verified.
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Open navigation menu")).onFirst().performClick()
            waitText("Settings")
            text("Settings").performClick()
            waitText("Attestation")
            text("Attestation").performClick()
            waitText("Device attestation key", LONG_WAIT_MS)
            screenshot("attestation")
        }
        // The enrollment and the unlock used the phone's real attestation key.
        val level = DeviceAttestationKey().level()
        assertTrue("attestation key level $level", level == KeyLevel.STRONG_BOX || level == KeyLevel.TEE)
    }

    private companion object {
        const val PIN = "40281795"
        const val PASSWORD = "correct horse battery staple (a3)"
        const val WAIT_MS = 15_000L
        const val LONG_WAIT_MS = 60_000L
        const val ENROLL_WAIT_MS = 240_000L
        const val SETTLE_MS = 400L
        const val SHOTS = "/data/local/tmp/a3-exit"
    }
}
