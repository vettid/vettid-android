package com.vettid.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.core.testing.DevStack
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The A4 exit test (ANDROID-PLAN §6: invite/QR connect, SAS, messages both
 * ways, approvals), on the phone, against the local dev stack, with the
 * `devStack` build; the other member is the stack's vaultctl peer:
 *
 * `./gradlew -PvettidTestBuildType=devStack :app:connectedDevStackAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vettid.app.A4ExitTest`
 *
 * A fresh install onboards a new test member (as in A3), then, all through the UI:
 * 1. invites the peer: the QR code and link are shown, the peer accepts the link,
 *    the request arrives with its 6-digit safety code and is approved;
 * 2. sends a message and receives one;
 * 3. removes the connection, then accepts the peer's invitation by pasting its link (the
 *    invitee side); the peer approves;
 * 4. decides approvals: a member-authentication request (with the credential
 *    password; the peer gets a verified signature) and a grant request (denied);
 * 5. marks the connection a favourite.
 * Screenshots go to `/data/local/tmp/a4-exit/`. Skipped when the stack is not reachable.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class A4ExitTest {
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

    private fun waitTag(tag: String, timeout: Long = WAIT_MS) = rule.waitUntilAtLeastOneExists(hasTestTag(tag), timeout)

    private fun waitText(text: String, timeout: Long = WAIT_MS) = rule.waitUntilAtLeastOneExists(hasText(text, substring = true), timeout)

    private fun tag(t: String): SemanticsNodeInteraction = rule.onAllNodes(hasTestTag(t)).onFirst()

    private fun text(t: String): SemanticsNodeInteraction = rule.onAllNodes(hasText(t)).onFirst()

    private fun primary() = tag("primary_button").performClick()

    private fun drawer(item: String) {
        // From a detail screen, go back to a top-level screen (they hold the menu button).
        repeat(BACK_MAX) {
            rule.waitForIdle()
            if (rule.onAllNodes(hasContentDescription("Open navigation menu")).fetchSemanticsNodes().isNotEmpty()) return@repeat
            Espresso.pressBackUnconditionally()
        }
        rule.onAllNodes(hasContentDescription("Open navigation menu")).onFirst().performClick()
        waitText(item)
        text(item).performClick()
    }

    private fun textOf(tag: String): String =
        tag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    private fun peer(type: String, body: kotlinx.serialization.json.JsonObject = buildJsonObject {}) = runBlocking { stack.peerRequest(type, body) }

    private fun peerEvent(type: String, match: Map<String, String> = emptyMap()) = runBlocking { stack.peerEvent(type, match, PEER_WAIT_S) }

    private fun str(o: kotlinx.serialization.json.JsonObject, k: String) = (o[k] as JsonPrimitive).content

    @Test
    @Suppress("LongMethod")
    fun inviteSasMessagesBothWaysAcceptAndApprovals() {
        assumeTrue("dev stack not reachable", stack.reachable())
        shell("rm -rf $SHOTS")
        shell("mkdir -p $SHOTS")
        // The peer presents a name to connections (its hs.init profile, §6.2).
        val profile = peer("profile.get")
        peer("profile.set", buildJsonObject {
            put("version", (profile["version"] as JsonPrimitive).content.toLong())
            put("name", PEER_NAME)
        })

        val intent = Intent(context, MainActivity::class.java).putExtra("vettid.screenshot", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            onboard("a4-exit-${System.currentTimeMillis()}@example.org")
            waitText("Messages", LONG_WAIT_MS)
            screenshot("messages-empty")

            // --- 1. Invite (in person, 10 minutes): QR code and link; the peer accepts; SAS; approve. ---
            drawer("Invite a connection")
            waitTag("ttl_600")
            screenshot("invite-choose")
            primary()
            waitTag("invite_link", LONG_WAIT_MS)
            waitTag("invite_qr")
            screenshot("invite-qr")
            val link = textOf("invite_link")
            assertTrue("link $link", link.length > LINK_MIN)
            val accepted = peer("connection.invite.accept", buildJsonObject { put("link", link) })
            val peerConn1 = str(accepted, "connection_id")
            waitTag("connection_request", LONG_WAIT_MS)
            waitText(PEER_NAME)
            val sas = rule.onAllNodes(hasContentDescription("Safety code", substring = true)).onFirst()
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("") ?: ""
            assertEquals("safety code of 6 digits: $sas", SAS_DIGITS, sas.count { it.isDigit() })
            screenshot("invite-request-sas")
            primary()
            waitTag("connected", LONG_WAIT_MS)
            peerEvent("connection.event", mapOf("event" to "added"))
            screenshot("invite-connected")

            // --- 2. Messages both ways. ---
            primary() // Send a message
            waitTag("composer", LONG_WAIT_MS)
            val out = "Hello from the A4 exit test ${System.currentTimeMillis()}"
            tag("composer").performTextInput(out)
            tag("send").performClick()
            val got = peerEvent("message.new", mapOf("text" to out))
            assertEquals("in", str(got, "direction"))
            val back = "Hello back from vaultctl ${System.currentTimeMillis()}"
            peer("message.send", buildJsonObject {
                put("connection_id", peerConn1)
                put("text", back)
            })
            rule.waitUntilAtLeastOneExists(hasContentDescription(back, substring = true), LONG_WAIT_MS)
            // Our message was delivered (the peer vault's receipt), and the app read the peer's message while
            // the conversation was open: the peer gets a read receipt (§10.5).
            rule.waitUntilAtLeastOneExists(hasContentDescription(", Delivered", substring = true), LONG_WAIT_MS)
            peerEvent("sync.event", mapOf("kind" to "message.receipt", "receipt" to "read"))
            screenshot("conversation")

            // --- 3. Remove the connection (a vault refuses a second connection with a peer it has), ---
            // then the invitee side: paste the peer's invitation link; the peer approves.
            rule.onAllNodes(hasContentDescription("About $PEER_NAME")).onFirst().performClick()
            waitTag("detail_name", LONG_WAIT_MS)
            rule.onAllNodes(hasContentDescription("Remove")).onFirst().performClick()
            waitTag("confirm_button")
            screenshot("connection-remove-confirm")
            tag("confirm_button").performClick()
            peerEvent("connection.event", mapOf("event" to "removed"))

            val peerInvite = peer("connection.invite.create", buildJsonObject { put("ttl_seconds", INVITE_TTL) })
            drawer("Connections")
            waitTag("add_connection")
            tag("add_connection").performClick()
            waitTag("add_paste")
            screenshot("connections-add")
            tag("add_paste").performClick()
            waitTag("invite_input")
            tag("invite_input").performTextInput(str(peerInvite, "link"))
            screenshot("accept-paste")
            primary()
            waitTag("accept_waiting", LONG_WAIT_MS)
            screenshot("accept-waiting")
            val pending = peerEvent("connection.request.pending")
            peer("connection.approve", buildJsonObject { put("pending_id", str(pending, "pending_id")) })
            waitTag("connected", LONG_WAIT_MS)
            screenshot("accept-connected")
            val added = peerEvent("connection.event", mapOf("event" to "added"))
            val peerConn2 = str(added, "connection_id")
            text("Done").performClick()

            // --- 4. Approvals: member authentication (password) and a grant request (denied). ---
            peer("connection.authenticate.request", buildJsonObject {
                put("connection_id", peerConn2)
                put("context", "A4 exit test")
            })
            peer("grant.request", buildJsonObject {
                put("connection_id", peerConn2)
                putJsonArray("items") {
                    add(buildJsonObject {
                        put("kind", "category")
                        put("ref", "insurance")
                        put("label", "Your insurance card")
                    })
                }
                put("reason", "A4 exit test")
            })
            drawer("Approvals")
            waitText("Authenticate to a connection", LONG_WAIT_MS)
            waitText("Request to see your items", LONG_WAIT_MS)
            screenshot("approvals")
            text("Authenticate to a connection").performClick()
            waitTag("approval_password")
            tag("approval_password").performTextInput(PASSWORD)
            screenshot("approval-authenticate")
            primary()
            val verdict = peerEvent("connection.authenticate.result")
            assertEquals("true", (verdict["authenticated"] as JsonPrimitive).content)
            waitText("Request to see your items", LONG_WAIT_MS)
            text("Request to see your items").performClick()
            waitText("Your insurance card")
            screenshot("approval-grant")
            text("Deny").performClick()
            peerEvent("grant.event", mapOf("event" to "denied"))
            waitText("Nothing to approve", LONG_WAIT_MS)
            screenshot("approvals-done")

            // --- 5. Favourite, and the connection's details. ---
            drawer("Connections")
            waitText(PEER_NAME, LONG_WAIT_MS)
            rule.onAllNodes(hasContentDescription("Add $PEER_NAME to favourites")).onFirst().performClick()
            rule.waitUntilAtLeastOneExists(hasContentDescription("Remove $PEER_NAME from favourites"), LONG_WAIT_MS)
            screenshot("connections-favourite")
            text(PEER_NAME).performClick()
            waitTag("detail_name", LONG_WAIT_MS)
            screenshot("connection-detail")
            tag("auth_card").performScrollTo()
            screenshot("connection-detail-auth")
            assertTrue(rule.onAllNodes(hasTestTag("auth_status")).fetchSemanticsNodes().isNotEmpty())
            val conns = peer("connection.list")["connections"] as JsonArray
            assertTrue("peer sees our connection", conns.isNotEmpty())
        }
    }

    private fun onboard(email: String) {
        waitText("Get started", LONG_WAIT_MS)
        text("Get started").performClick()
        waitTag("email")
        tag("email").performTextInput(email)
        primary()
        waitTag("link")
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
        const val PASSWORD = "correct horse battery staple (a4)"
        const val PEER_NAME = "Vaultctl Peer"
        const val INVITE_TTL = 600
        const val LINK_MIN = 100
        const val SAS_DIGITS = 6
        const val PEER_WAIT_S = 120
        const val WAIT_MS = 15_000L
        const val LONG_WAIT_MS = 90_000L
        const val ENROLL_WAIT_MS = 240_000L
        const val SETTLE_MS = 400L
        const val SHOTS = "/data/local/tmp/a4-exit"
        const val BACK_MAX = 4
    }
}
