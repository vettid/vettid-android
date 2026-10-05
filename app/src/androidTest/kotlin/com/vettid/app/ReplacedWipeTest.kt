package com.vettid.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.keystore.AndroidKeys
import com.vettid.core.testing.DevStack
import com.vettid.core.testing.TestAndroidAttester
import com.vettid.core.vault.DeviceConfig
import com.vettid.core.vault.DeviceSecrets
import com.vettid.core.vault.InMemoryDeviceStateStore
import com.vettid.core.vault.VaultDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration

/**
 * A replaced phone erases itself (owner decision, 2026-10-05), on the phone
 * against the local dev stack with the `devStack` build:
 *
 * `./gradlew -PvettidTestBuildType=devStack :app:connectedDevStackAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vettid.app.ReplacedWipeTest`
 *
 * The app onboards a new member (the phone's real attestation), then: a wrong
 * PIN at unlock (a sealed `bad_pin`) changes nothing; Settings → Move to a new
 * phone shows the transfer code; a "new phone" in this test process (software
 * keys, the TEST attester the dev policy accepts) scans it, and both show the
 * same SAS; the app approves with the PIN and the password (VAULT-MESSAGING
 * §6.7.1). The vault completes the transfer, the new device gets
 * `device.paired`, the app gets `device.unlinked{transferred}` and erases
 * itself: the welcome screen, no Keystore key, no account, device, social,
 * session or app-lock file, no SharedPreferences, no DataStore file.
 * Screenshots go to `/data/local/tmp/replaced-wipe/`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReplacedWipeTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val stack = DevStack(BuildConfig.DEV_STACK_API, BuildConfig.DEV_STACK_RELAY, BuildConfig.DEV_STACK_CTL)
    private var shot = 0

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).close()

    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        shell("screencap -p $SHOTS/%02d-%s.png".format(++shot, name))
        Thread.sleep(SETTLE_MS)
    }

    private fun waitTag(tag: String, timeout: Long = WAIT_MS) = shotOnTimeout(tag) { rule.waitUntilAtLeastOneExists(hasTestTag(tag), timeout) }

    private fun waitText(text: String, timeout: Long = WAIT_MS) =
        shotOnTimeout(text) { rule.waitUntilAtLeastOneExists(hasText(text, substring = true), timeout) }

    private fun shotOnTimeout(what: String, wait: () -> Unit) {
        try {
            wait()
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            shell("screencap -p $SHOTS/timeout-${what.filter { it.isLetterOrDigit() }.take(20)}.png")
            throw e
        }
    }

    private fun tag(t: String): SemanticsNodeInteraction = rule.onAllNodes(hasTestTag(t)).onFirst()

    private fun text(t: String): SemanticsNodeInteraction = rule.onAllNodes(hasText(t)).onFirst()

    private fun primary() = tag("primary_button").performClick()

    private fun textOf(tag: String): String =
        tag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    private fun shownSas(): String = rule.onAllNodes(hasContentDescription("Safety code", substring = true)).onFirst()
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("")?.filter { it.isDigit() } ?: ""

    private fun drawer(item: String) {
        rule.onAllNodes(hasContentDescription("Open navigation menu")).onFirst().performClick()
        waitText(item)
        text(item).performClick()
    }

    @Test
    @Suppress("LongMethod")
    fun aTransferredPhoneErasesItself() {
        assumeTrue("dev stack not reachable", stack.reachable())
        shell("rm -rf $SHOTS")
        shell("mkdir -p $SHOTS")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val intent = Intent(context, MainActivity::class.java).putExtra("vettid.screenshot", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<MainActivity>(intent).use {
                onboard("replaced-wipe-${System.currentTimeMillis()}@example.org")
                waitText("Messages", LONG_WAIT_MS)
                assertTrue("enrolled: the device file exists", File(context.noBackupFilesDir, "vault-device.bin").exists())
                assertTrue("enrolled: Keystore keys exist", AndroidKeys.aliases().isNotEmpty())

                // Not proof: a wrong PIN (a sealed bad_pin) at unlock leaves everything as it was.
                drawer("Settings")
                waitTag("lock_vault")
                tag("lock_vault").performClick()
                waitTag("confirm_button")
                tag("confirm_button").performClick()
                waitTag("unlock_pin", LONG_WAIT_MS)
                tag("unlock_pin").performTextInput("13572468")
                primary()
                waitText("That PIN is not correct.", LONG_WAIT_MS)
                screenshot("wrong-pin")
                assertTrue("a wrong PIN wipes nothing", File(context.noBackupFilesDir, "vault-device.bin").exists())
                assertTrue(File(context.noBackupFilesDir, "account.bin").exists())
                // The enclave's backoff after a wrong PIN (§11.8): the field is enabled again when it ends.
                rule.waitUntilAtLeastOneExists(hasTestTag("unlock_pin") and isEnabled(), BACKOFF_WAIT_MS)
                tag("unlock_pin").performTextInput(PIN)
                primary()
                waitText("Messages", LONG_WAIT_MS)

                // Settings → Move to a new phone → the code.
                drawer("Settings")
                waitTag("transfer")
                tag("transfer").performScrollTo()
                tag("transfer").performClick()
                waitTag("primary_button")
                screenshot("transfer-intro")
                primary()
                waitTag("transfer_link", LONG_WAIT_MS)
                screenshot("transfer-code")
                val link = textOf("transfer_link")
                assertTrue("transfer link: $link", link.length > LINK_MIN)

                // The new phone (this process, software keys, the TEST attester) scans it.
                val newPhone = runBlocking {
                    val cfg = DeviceConfig(
                        name = "New test phone", relayUrl = stack.relayUrl, http = stack.http, store = InMemoryDeviceStateStore(),
                        trust = stack.trust(), pollWait = Duration.ofSeconds(POLL_S),
                    )
                    val secrets = DeviceSecrets(Ed25519PrivateKey.generate(), KemPrivateKey.generate(), Ed25519PrivateKey.generate())
                    VaultDevice.create(cfg, secrets).also { d ->
                        d.start(scope)
                        d.startTransfer(link, TestAndroidAttester())
                    }
                }
                val newSas = runBlocking { withTimeout(LONG_WAIT_MS) { newPhone.pairingSas.filterNotNull().first() } }

                // The old phone: compare, approve with the PIN and the password.
                waitText("Compare the code", LONG_WAIT_MS)
                screenshot("transfer-compare")
                assertEquals(newSas, shownSas())
                primary()
                waitTag("transfer_pin")
                tag("transfer_pin").performTextInput(PIN)
                tag("transfer_password").performTextInput(PASSWORD)
                screenshot("transfer-approve")
                primary()
                waitTag("confirm_button")
                tag("confirm_button").performClick()

                // The vault completes the transfer: the new phone is paired …
                runBlocking { newPhone.awaitTransfer(Duration.ofMillis(LONG_WAIT_MS)) }
                assertTrue("the new phone holds the vault", newPhone.deviceId != null)

                // … and the old one, told by device.unlinked{transferred}, is as freshly installed.
                waitText("Get started", LONG_WAIT_MS)
                screenshot("welcome-after-wipe")
            }
            assertFresh()
            // A restart finds nothing either (no wipe marker left, nothing to resume).
            ActivityScenario.launch<MainActivity>(intent).use {
                waitText("Get started", LONG_WAIT_MS)
                screenshot("restart")
            }
            assertFresh()
        } finally {
            scope.cancel()
        }
    }

    /** Everything the app kept is gone (owner decision, 2026-10-05). */
    private fun assertFresh() {
        val noBackup = context.noBackupFilesDir.listFiles()?.map { it.name }.orEmpty()
        for (f in listOf("account.bin", "vault-device.bin", "social.bin", "member-session.bin", "app-lock.bin", "wipe-pending")) {
            assertFalse("$f left in no_backup: $noBackup", f in noBackup)
        }
        assertEquals("Keystore aliases left", emptyList<String>(), AndroidKeys.aliases())
        val prefs = File(context.dataDir, "shared_prefs").listFiles()?.map { it.name }.orEmpty()
        for (p in listOf("vettid_device_keys.xml", "vettid_release_state.xml", "vettid_devstack_account.xml")) {
            assertFalse("$p left: $prefs", p in prefs)
        }
        assertFalse("DataStore file left", File(context.filesDir, "datastore/vettid_preferences.preferences_pb").exists())
    }

    private fun onboard(email: String) {
        waitText("Get started", LONG_WAIT_MS)
        screenshot("welcome")
        text("Get started").performClick()
        waitTag("email")
        tag("email").performTextInput(email)
        rule.waitForIdle()
        primary()
        waitTag("link", LONG_WAIT_MS)
        tag("link").performTextInput("devstack-sign-in-token")
        primary()
        waitText("Sign in on this phone?")
        primary()
        waitTag("pin", LONG_WAIT_MS)
        tag("pin").performTextInput(PIN)
        primary()
        waitTag("pin_confirm")
        tag("pin_confirm").performTextInput(PIN)
        primary()
        waitTag("password")
        tag("password").performTextInput(PASSWORD)
        tag("password_confirm").performTextInput(PASSWORD)
        primary()
        waitTag("backup_on")
        primary()
        waitText("Your vault is ready", ENROLL_WAIT_MS)
        primary()
    }

    private companion object {
        const val PIN = "40281795"
        const val PASSWORD = "correct horse battery staple (wipe)"
        const val LINK_MIN = 100
        const val POLL_S = 10L
        const val WAIT_MS = 15_000L
        const val LONG_WAIT_MS = 90_000L
        const val ENROLL_WAIT_MS = 240_000L
        const val BACKOFF_WAIT_MS = 180_000L
        const val SETTLE_MS = 400L
        const val SHOTS = "/data/local/tmp/replaced-wipe"
    }
}
