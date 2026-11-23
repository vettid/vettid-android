// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import android.view.MotionEvent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import com.vettid.core.data.items.FieldLabel
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.HeldRequest
import com.vettid.core.data.social.RunFlag
import com.vettid.core.data.social.SecretHow
import com.vettid.core.ui.text.ShownString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** ANDROID-PLAN 0.1.137 AA8: a run's and a proxy's approval screens, the gate before Approve and the overlay rule. */
@RunWith(RobolectricTestRunner::class)
class AgentWayScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.now()
    private val bot = "⁨Build bot⁩"
    private val sha = "1a2b3c4d5e6f7a8b".repeat(4)
    private val sha2 = "9f8e7d6c5b4a3928".repeat(4)
    private val items = listOf(
        ItemSummary("i1", 1, "GitHub token", "other", Sensitivity.SECRET, fieldLabels = listOf(FieldLabel("f1", "Token", "password"))),
    )

    private fun run(
        argv: List<String> = listOf("/usr/bin/git", "push", "origin"),
        flags: Set<RunFlag> = emptySet(),
        program: String = "/usr/bin/git",
        how: SecretHow = SecretHow.ENV,
        env: List<Pair<String, String>> = emptyList(),
        previous: Pair<String, String>? = null,
        replaces: String? = null,
        cwd: String? = null,
    ) = HeldRequest.ItemRun("i1", "f1", program, sha, argv, cwd, how, "GH_TOKEN", env, flags, previous?.first, previous?.second, replaces)

    private fun proxy(seconds: Int = 900, prefix: String = "Bearer ") =
        HeldRequest.ItemProxy("i1", "f1", "api.example.com", "Authorization", prefix, seconds, null)

    private fun ask(h: HeldRequest, exp: Instant = now.plusSeconds(4 * 60)) =
        Approval.DeviceRequest("approval.pending", "h1", "Build bot", "agent", "agent.request", now, exp, deviceId = "A", held = h)

    private fun state(a: Approval.DeviceRequest) = ApprovalDetailUiState(a.key, a, items = items)

    private fun show(h: HeldRequest, actions: DecisionActions = DecisionActions(), exp: Instant = now.plusSeconds(4 * 60)) =
        rule.setContent { ApprovalDetailScreen(state(ask(h, exp)), actions) }

    private fun inline(s: String) = ShownString.inline(s, "(empty)")

    @Test
    fun aRunShowsEverythingTheVaultHolds() {
        show(run(env = listOf("GIT_DIR" to "/tmp/x"), cwd = "/home/me/src"))
        rule.onNodeWithTag("agent_way_title").assertExists()
        rule.onNodeWithText("$bot wants to run a program with ⁨GitHub token⁩").assertExists()
        rule.onNodeWithText("2 arguments, 1 variable, 35 bytes in all").assertExists()
        rule.onNodeWithText("Field: ⁨Token⁩").assertExists()
        rule.onNodeWithText("Fingerprint: 1a2b 3c4d 5e6f 7a8b").assertExists()
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_env_0"))
        rule.onNodeWithText("variable ${inline("GH_TOKEN")}").assertExists()
        rule.onNodeWithText(inline("GIT_DIR")).assertExists()
    }

    @Test
    fun theFingerprintShowsAll64DigitsOnATap() {
        show(run())
        rule.onNodeWithTag("agent_way_pin").performClick()
        rule.onNodeWithText("Fingerprint: " + sha.chunked(4).joinToString(" ")).assertExists()
    }

    @Test
    fun eachFlagsWarningShowsAtTheTopAndAgainAtTheEnd() {
        show(run(flags = setOf(RunFlag.SHELL, RunFlag.PROGRAM_UNRECORDED)))
        rule.onNodeWithTag("agent_way_warning_top_0").assertExists()
        rule.onNodeWithText("This program can run other programs or code given to it, so the command can do anything with your secret. Read every argument.").assertExists()
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_warning_end_1"))
        rule.onNodeWithTag("agent_way_warning_end_1").assertExists()
    }

    @Test
    fun aMovedProgramShowsBothPathsAndFingerprintsInFull() {
        show(run(flags = setOf(RunFlag.PROGRAM_CHANGED), previous = sha2 to "/opt/git"))
        rule.onNodeWithText(
            "This program moved to ${inline("/usr/bin/git")}. It was approved at ${inline("/opt/git")}. Before: 9f8e 7d6c 5b4a 3928. Now: 1a2b 3c4d 5e6f 7a8b. If you did not move or update it, deny. If you approve, this file's fingerprint replaces the recorded one, and later runs of it may run without asking, as this item's settings allow.",
        ).assertExists()
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_now_pin"))
        rule.onNodeWithTag("agent_way_before_pin").assertExists()
        rule.onNodeWithText(sha.chunked(4).joinToString(" ")).assertExists()
    }

    @Test
    fun theBatchFileAndThe636LinesShow() {
        show(run(program = "C:/tools/build.cmd", argv = listOf("C:/tools/build.cmd")))
        rule.onNodeWithTag("agent_way_batch_file").assertExists()
    }

    @Test
    fun aSecretFileArgumentIsNamedOnlyForAFileRun() {
        show(run(argv = listOf("/usr/bin/tool", "--key", "{secret_file}"), program = "/usr/bin/tool", how = SecretHow.FILE))
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_secret_file_1"))
        rule.onNodeWithText("(the secret file's path)").assertExists()
    }

    @Test
    fun approveWaitsForTheEndOfALongRun() {
        val args = listOf("/usr/bin/git") + (1..64).map { "arg-$it-" + "x".repeat(500) }
        show(run(argv = args))
        rule.onNodeWithTag("agent_way_approve").assertIsNotEnabled()
        rule.onNodeWithText("Scroll to the end to approve.").assertExists()
        rule.onNodeWithTag("agent_way_deny").assertIsEnabled()
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_end"))
        rule.waitForIdle()
        rule.onNodeWithTag("agent_way_approve").assertIsEnabled()
        // Back up: it stays reached.
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_title"))
        rule.onNodeWithTag("agent_way_approve").assertIsEnabled()
    }

    /** Content that fits on the screen counts as reached, after the full layout. */
    @Test
    @Config(qualifiers = "w400dp-h2400dp")
    fun aShortRunThatFitsIsReachedAtOnce() {
        show(run(argv = listOf("/usr/bin/git")))
        rule.waitForIdle()
        rule.onNodeWithTag("agent_way_approve").assertIsEnabled()
    }

    @Test
    fun aProxyShowsItsNote() {
        show(proxy(seconds = 90))
        rule.onNodeWithText("$bot wants to use ⁨GitHub token⁩ with ${inline("api.example.com")} for 1 minute 30 seconds.").assertExists()
        rule.onNodeWithText("The agent will not see it; the key is sent only to ${inline("api.example.com")}.").assertExists()
        rule.onNodeWithText("Sent as: ${inline("Authorization")}: ${ShownString.inline("Bearer ", "")}••••••••").assertExists()
    }

    @Test
    fun aProxyOfAnHourSaysSo() {
        show(proxy(seconds = 3_600))
        rule.onNodeWithText("$bot wants to use ⁨GitHub token⁩ with ${inline("api.example.com")} for 1 hour.").assertExists()
    }

    @Test
    fun aReferralSaysItWasAskedAgain() {
        show(run(replaces = "h0"))
        rule.onNodeWithText("Asked again. This item's settings or its recorded program changed while you were deciding. Check the details again.").assertExists()
    }

    @Test
    fun anExpiredRequestHasNoButtons() {
        show(run(), exp = now.minusSeconds(1))
        rule.onNodeWithText("This request expired, so nothing was done.").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("agent_way_approve").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("agent_way_deny").fetchSemanticsNodes().size)
    }

    @Test
    fun aTouchSeenThroughAnotherAppIsRefused() {
        fun touch(flags: Int) = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_DOWN, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }), arrayOf(MotionEvent.PointerCoords()),
            0, 0, 1f, 1f, 0, 0, 0, flags,
        )
        assertTrue(obscuredTouch(touch(MotionEvent.FLAG_WINDOW_IS_OBSCURED)))
        assertTrue(obscuredTouch(touch(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)))
        assertFalse(obscuredTouch(touch(0)))
    }

    @Test
    fun theOutcomesSayWhatHappened() {
        fun outcome(o: DeviceOutcome) = rule.setContent { ApprovalDetailScreen(state(ask(run())).copy(outcome = o), DecisionActions()) }
        outcome(DeviceOutcome.Referred)
        rule.onNodeWithText("Not done yet: something changed while you were deciding, so you are asked again.").assertExists()
    }

    @Test
    fun aRunsNotFoundSaysTheProgramWasNotRun() {
        rule.setContent { ApprovalDetailScreen(state(ask(run())).copy(outcome = DeviceOutcome.Approved("not_found", AgentWay.RUN)), DecisionActions()) }
        rule.onNodeWithText("Approved, but the item or its field is gone, so the program was not run.").assertExists()
    }

    @Test
    fun aRecordedPinIsSaid() {
        rule.setContent {
            ApprovalDetailScreen(state(ask(run())).copy(outcome = DeviceOutcome.Approved("ok", AgentWay.RUN, pinRecorded = true, item = "GitHub token")), DecisionActions())
        }
        rule.onNodeWithText("This program's fingerprint is now recorded for ⁨GitHub token⁩.").assertExists()
    }
}
