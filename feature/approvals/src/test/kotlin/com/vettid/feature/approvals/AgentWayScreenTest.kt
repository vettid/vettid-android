// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import android.view.MotionEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    /** As the view model has it once the item's settings allowed a run's `cwd` (see the view model's tests for the read). */
    private fun state(a: Approval.DeviceRequest) = ApprovalDetailUiState(a.key, a, items = items, cwdAllowed = true)

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
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_pin"))
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
    fun theSecretFileNoteIsOnlyForAFileRun() {
        show(run(argv = listOf("/usr/bin/tool", "{secret_file}"), program = "/usr/bin/tool", how = SecretHow.ENV))
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_arg_0"))
        assertEquals(0, rule.onAllNodesWithTag("agent_way_secret_file_0").fetchSemanticsNodes().size)
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

    /** A real touch flagged obscured never reaches Approve (and says why); a plain one does; Deny always does. */
    @Test
    @Config(qualifiers = "w400dp-h2400dp")
    fun anObscuredTouchOnApproveIsRefused() {
        var approved = 0
        var denied = 0
        var view: android.view.View? = null
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            ApprovalDetailScreen(state(ask(run(argv = listOf("/usr/bin/git")))), DecisionActions(onApprove = { approved++ }, onDeny = { denied++ }))
        }
        rule.waitForIdle()
        fun tap(tag: String, flags: Int) {
            val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val x = b.center.x
            val y = b.center.y
            fun ev(action: Int) = MotionEvent.obtain(
                0L, 0L, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags,
            )
            rule.runOnUiThread {
                view!!.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN))
                view!!.dispatchTouchEvent(ev(MotionEvent.ACTION_UP))
            }
            rule.waitForIdle()
        }
        rule.onNodeWithTag("agent_way_approve").assertIsEnabled()
        tap("agent_way_approve", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals("an obscured tap approves nothing", 0, approved)
        rule.onNodeWithTag("agent_way_obscured").assertExists()
        tap("agent_way_approve", MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertEquals(0, approved)
        tap("agent_way_deny", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals("Deny works while obscured", 1, denied)
        tap("agent_way_approve", 0)
        assertEquals("a plain tap approves", 1, approved)
    }

    /** What is shown changes while the end was reached: back to the top, and the end must be scrolled to again. */
    @Test
    fun aContentChangeReArmsTheGate() {
        val args = listOf("/usr/bin/git") + (1..40).map { "arg-$it-" + "x".repeat(300) }
        var h by androidx.compose.runtime.mutableStateOf<HeldRequest>(run(argv = args))
        rule.setContent { ApprovalDetailScreen(state(ask(h)), DecisionActions()) }
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_end"))
        rule.waitForIdle()
        rule.onNodeWithTag("agent_way_no_env").assertIsDisplayed()
        rule.onNodeWithTag("agent_way_approve").assertIsEnabled()
        h = run(argv = args, flags = setOf(RunFlag.SHELL))
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithTag("agent_way_approve").assertIsNotEnabled()
        // Back at the top: the end is no longer on screen.
        rule.onNodeWithTag("agent_way_title").assertIsDisplayed()
    }

    /** A change that adds no row (another value in a row): still back to the top, and the end must be reached again. */
    @Test
    fun aChangeOfAValueAloneReArmsTheGate() {
        val args = listOf("/usr/bin/git") + (1..40).map { "arg-$it-" + "x".repeat(300) }
        var h by androidx.compose.runtime.mutableStateOf<HeldRequest>(run(argv = args, env = listOf("A" to "1")))
        rule.setContent { ApprovalDetailScreen(state(ask(h)), DecisionActions()) }
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_end"))
        rule.waitForIdle()
        rule.onNodeWithTag("agent_way_env_0").assertIsDisplayed()
        rule.onNodeWithTag("agent_way_approve").assertIsEnabled()
        h = run(argv = args, env = listOf("A" to "2"))
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithTag("agent_way_approve").assertIsNotEnabled()
        // Back at the top: the end is no longer on screen.
        rule.onNodeWithTag("agent_way_title").assertIsDisplayed()
    }

    @Test
    fun theProgramThatRunsOtherCommandsLineShowsAtTheTopAndTheEnd() {
        show(run()) // git
        rule.onNodeWithText("This program can run other commands given in its arguments. Read every argument.", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_warning_end_0"))
        rule.onNodeWithTag("agent_way_warning_end_0").assertExists()
    }

    @Test
    fun anEmptyPrefixShowsNoMarkers() {
        show(proxy(prefix = ""))
        rule.onNodeWithText("Sent as: ${inline("Authorization")}: ••••••••").assertExists()
    }

    /** Expired while open: the screen says so, with no buttons (the view model calls it gone). */
    @Test
    fun anExpiredRunThatTheViewModelCallsGoneSaysSo() {
        val a = ask(run(), exp = now.minusSeconds(1))
        rule.setContent { ApprovalDetailScreen(state(a).copy(gone = true), DecisionActions()) }
        rule.onNodeWithText("This request expired, so nothing was done.").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("agent_way_approve").fetchSemanticsNodes().size)
    }

    /** The box's text is isolated and forced left to right: LRI LRO … PDF PDI, as drawn. */
    @Test
    fun theProgramsBoxIsIsolatedAndForcedLeftToRight() {
        show(run())
        rule.onAllNodesWithTag("shown_string", useUnmergedTree = true)[0]
            .assertTextEquals("\u2066\u202D/usr/bin/git\u202C\u2069")
    }

    /** "Asked for as" shows argv[0] when it differs from the program, and not otherwise. */
    @Test
    fun askedForAsShowsOnlyWhenArgv0Differs() {
        show(run(argv = listOf("git", "status")))
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_asked_as"))
        rule.onNodeWithTag("agent_way_asked_as").assertExists()
    }

    @Test
    fun askedForAsIsAbsentWhenArgv0IsTheProgram() {
        show(run())
        assertEquals(0, rule.onAllNodesWithTag("agent_way_asked_as").fetchSemanticsNodes().size)
    }

    /** `{secret_file}` is matched exactly, not inside another argument. */
    @Test
    fun theSecretFileNoteNeedsTheExactArgument() {
        show(run(argv = listOf("/usr/bin/tool", "x{secret_file}"), program = "/usr/bin/tool", how = SecretHow.FILE))
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_arg_0"))
        assertEquals(0, rule.onAllNodesWithTag("agent_way_secret_file_0").fetchSemanticsNodes().size)
    }

    /** A program with a look-alike character gets the look-alike line under its box. */
    @Test
    fun aLookAlikeGetsItsLine() {
        show(run(program = "/usr/bin/g\u0456t", argv = listOf("/usr/bin/g\u0456t")))
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_program"))
        rule.onAllNodesWithTag("shown_string_lookalike", useUnmergedTree = true)[0].assertExists()
        rule.onNodeWithText("Some characters here may look like others.", useUnmergedTree = true).assertExists()
    }

    /** Each half of the end: the last item laid out, and its bottom edge inside the list. */
    @Test
    fun theEndNeedsTheLastItemWhollyInside() {
        assertTrue(endReached(lastIndex = 9, total = 10, offset = 400, size = 50, viewportEnd = 450))
        assertFalse("half shown", endReached(lastIndex = 9, total = 10, offset = 420, size = 50, viewportEnd = 450))
        assertFalse("not the last item", endReached(lastIndex = 8, total = 10, offset = 0, size = 50, viewportEnd = 450))
    }

    /** The gate's key follows the content's value: any change shown changes it, the same content keeps it. */
    @Test
    fun theReArmKeyFollowsWhatIsShown() {
        val a = HeldShow.Run(run(), "GitHub token", "Token")
        assertEquals(WayKey.of(a), WayKey.of(HeldShow.Run(run(), "GitHub token", "Token")))
        val changes = listOf(
            a.copy(run = run(argv = listOf("/usr/bin/git", "push", "origin2"))),
            a.copy(run = run(env = listOf("A" to "1"))),
            a.copy(run = run(flags = setOf(RunFlag.SHELL))),
            a.copy(run = run(cwd = "/x")),
            a.copy(item = "Another"),
            a.copy(run = run(argv = listOf("/usr/bin/git", "pushorigin"))),
        )
        changes.forEach { assertTrue(WayKey.of(a) != WayKey.of(it)) }
        assertTrue("argument boundaries count", WayKey.of(a.copy(run = run(argv = listOf("a", "bc")))) != WayKey.of(a.copy(run = run(argv = listOf("ab", "c")))))
    }

    /** A clean tap after an obscured one takes the overlay line away. */
    @Test
    @Config(qualifiers = "w400dp-h2400dp")
    fun aCleanTouchClearsTheOverlayLine() {
        var view: android.view.View? = null
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            ApprovalDetailScreen(state(ask(run(argv = listOf("/usr/bin/git")))), DecisionActions())
        }
        rule.waitForIdle()
        val b = rule.onNodeWithTag("agent_way_approve").fetchSemanticsNode().boundsInRoot
        fun tap(flags: Int) {
            fun ev(action: Int) = MotionEvent.obtain(
                0L, 0L, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply { x = b.center.x; y = b.center.y; pressure = 1f; size = 1f }),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags,
            )
            rule.runOnUiThread {
                view!!.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN))
                view!!.dispatchTouchEvent(ev(MotionEvent.ACTION_UP))
            }
            rule.waitForIdle()
        }
        tap(MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        rule.onNodeWithTag("agent_way_obscured").assertExists()
        tap(0)
        assertEquals(0, rule.onAllNodesWithTag("agent_way_obscured").fetchSemanticsNodes().size)
    }

    /** The re-arm key changes when only a proxy's prefix changes. */
    @Test
    fun theReArmKeyFollowsTheProxyPrefix() {
        val a = HeldShow.Proxy(proxy(prefix = "Bearer "), "GitHub token", "Token")
        assertTrue(WayKey.of(a) != WayKey.of(a.copy(proxy = proxy(prefix = "Token "))))
        assertTrue(WayKey.of(a) != WayKey.of(a.copy(proxy = proxy(prefix = ""))))
        assertEquals(WayKey.of(a), WayKey.of(HeldShow.Proxy(proxy(prefix = "Bearer "), "GitHub token", "Token")))
    }

    // AA9: a run's member folder and files, from the approval.

    private val workEntry = com.vettid.core.data.devices.RunFolder("/home/me/work", write = false)
    private val outEntry = com.vettid.core.data.devices.RunFolder("/home/me/out", write = true)

    /** A program with no advice of its own (git would add the #636 line first). */
    private val plain = "/usr/local/bin/report"

    private fun folderRun() =
        run(argv = listOf(plain), program = plain, cwd = "/home/me/work/src", flags = setOf(RunFlag.MEMBER_FOLDER)).copy(cwdEntry = workEntry)

    private fun filesRun(output: Boolean = true) = run(argv = listOf(plain), program = plain, flags = setOf(RunFlag.MEMBER_FILES)).copy(
        inputs = listOf(com.vettid.core.data.social.RunInput("/home/me/docs/report.pdf", 2048)),
        output = if (output) com.vettid.core.data.social.RunOutput("/home/me/out/run1", listOf("result.csv"), outEntry) else null,
    )

    @Test
    @Config(qualifiers = "w400dp-h2400dp")
    fun aRunInAMemberFolderShowsItsEntryAndWarning() {
        show(folderRun())
        val warn = "This runs in a folder of yours. The agent can change what is in it, and the program runs what it finds there."
        rule.onNodeWithTag("agent_way_folder").assertTextEquals("Folder: ${inline("/home/me/work")} · Read only")
        rule.onNodeWithTag("agent_way_warning_top_0").assertTextEquals(warn)
        rule.onNodeWithTag("agent_way_content").performScrollToNode(hasTestTag("agent_way_warning_end_0"))
        rule.onNodeWithTag("agent_way_warning_end_0").assertTextEquals(warn)
    }

    /** The text a string box with [tag] draws: LRI LRO … PDF PDI. */
    private fun box(tag: String) =
        rule.onNode(hasTestTag("shown_string") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    private fun boxed(s: String) = "\u2066\u202D$s\u202C\u2069"

    private val twoFiles = run(argv = listOf("/usr/local/bin/report"), program = "/usr/local/bin/report", flags = setOf(RunFlag.MEMBER_FILES)).copy(
        inputs = listOf(
            com.vettid.core.data.social.RunInput("/home/me/docs/report.pdf", 2048),
            com.vettid.core.data.social.RunInput("/home/me/data/notes.txt", 900),
        ),
        output = com.vettid.core.data.social.RunOutput("/home/me/out/run1", listOf("result.csv", "log.txt"), outEntry),
    )

    @Test
    @Config(qualifiers = "w400dp-h3000dp")
    fun aRunWithFilesShowsWhatIsCopiedInAndBack() {
        show(twoFiles)
        val warn = "Files saved to ${inline("/home/me/out/run1")} are new copies; nothing there is overwritten. A file that held the secret is changed before it is copied back."
        rule.onNodeWithTag("agent_way_warning_top_0").assertTextEquals(warn)
        rule.onNodeWithText("Copied in, read only:").assertExists()
        // Every input, each with its own name, size and folder.
        rule.onNodeWithTag("agent_way_input_0").assertTextEquals("${inline("report.pdf")} · 2.0 KB")
        box("agent_way_input_folder_0").assertTextEquals(boxed("/home/me/docs"))
        rule.onNodeWithTag("agent_way_input_1").assertTextEquals("${inline("notes.txt")} · 900 bytes")
        box("agent_way_input_folder_1").assertTextEquals(boxed("/home/me/data"))
        rule.onNodeWithText("Copied back to ${inline("/home/me/out/run1")} when it ends:").assertExists()
        rule.onNodeWithTag("agent_way_output_entry").assertTextEquals("Folder: ${inline("/home/me/out")} · Can change files")
        // Every name to be copied back.
        box("agent_way_output_0").assertTextEquals(boxed("result.csv"))
        box("agent_way_output_1").assertTextEquals(boxed("log.txt"))
        assertEquals(0, rule.onAllNodesWithTag("agent_way_input_2").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("agent_way_output_2").fetchSemanticsNodes().size)
        rule.onNodeWithTag("agent_way_warning_end_0").assertTextEquals(warn)
        assertEquals(0, rule.onAllNodesWithTag("agent_way_folder").fetchSemanticsNodes().size)
    }

    /** Inputs only: nothing is copied back, so neither that line nor its warning. */
    @Test
    @Config(qualifiers = "w400dp-h3000dp")
    fun inputsOnlyHaveNoCopyBack() {
        show(filesRun(output = false))
        rule.onNodeWithTag("agent_way_input_0").assertExists()
        assertEquals(0, rule.onAllNodesWithText("when it ends:", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("agent_way_warning_top_0").fetchSemanticsNodes().size)
    }

    /** The gate's key follows AA9's parts too. */
    @Test
    fun theReArmKeyFollowsTheFoldersAndFiles() {
        val a = HeldShow.Run(filesRun(), "GitHub token", "Token")
        val r = filesRun()
        val changes = listOf(
            r.copy(inputs = listOf(com.vettid.core.data.social.RunInput("/home/me/docs/report.pdf", 2049))),
            r.copy(inputs = listOf(com.vettid.core.data.social.RunInput("/home/me/docs/other.pdf", 2048))),
            r.copy(output = r.output!!.copy(names = listOf("result2.csv"))),
            r.copy(output = r.output!!.copy(folder = "/home/me/out/run2")),
            r.copy(output = r.output!!.copy(entry = outEntry.copy(path = "/home/me"))),
            r.copy(cwdEntry = workEntry),
            r.copy(cwdEntry = workEntry.copy(write = true)),
        )
        // Only the folder entry's path.
        val withEntry = a.copy(run = r.copy(cwdEntry = workEntry))
        assertTrue(WayKey.of(withEntry) != WayKey.of(a.copy(run = r.copy(cwdEntry = workEntry.copy(path = "/home/me/other")))))
        changes.forEach { assertTrue(WayKey.of(a) != WayKey.of(a.copy(run = it))) }
        assertEquals(WayKey.of(a), WayKey.of(HeldShow.Run(filesRun(), "GitHub token", "Token")))
    }
}
