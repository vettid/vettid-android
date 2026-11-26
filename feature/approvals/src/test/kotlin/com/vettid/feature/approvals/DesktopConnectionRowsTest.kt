// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.HeldRequest
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * ANDROID-PLAN 0.1.145 §4 "Requests held for approval": a desktop's held block and removal of a connection, the owner's
 * texts (GC #19089) word for word, Approve and Deny, and the deny-only row when the connection is not resolved.
 */
@RunWith(RobolectricTestRunner::class)
class DesktopConnectionRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.now()
    private val laptop = "\u2068Laptop\u2069"
    private val sam = "\u2068Sam Lee\u2069"

    private fun held(h: HeldRequest, name: String? = "Sam Lee", service: Boolean = false, type: String = if ((h as? HeldRequest.DesktopConnection)?.remove == true) "connection.remove" else "block.add") =
        Approval.DeviceRequest(
            "approval.pending", "h1", "Laptop", "desktop", type, now, now.plusSeconds(4 * 60 + 30),
            heldConnection = name, deviceId = "desk1", held = h, heldService = service,
        )

    private fun state(a: Approval.DeviceRequest) = ApprovalDetailUiState(a.key, a, items = emptyList())

    private fun show(s: ApprovalDetailUiState, actions: DecisionActions = DecisionActions()) = rule.setContent { ApprovalDetailScreen(s, actions) }

    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aBlockShowsTheOwnersTextsAndTheNoteApartWithApproveAndDeny() {
        var approved = 0
        var denied = 0
        show(
            state(held(HeldRequest.DesktopConnection(false, "c1", "  spam\r\nagain\u202E "))),
            DecisionActions(onApprove = { approved++ }, onDeny = { denied++ }),
        )
        rule.onNodeWithText("$laptop wants to block $sam").assertExists()
        rule.onNodeWithText("Blocking ends your connection with $sam. They can't connect with you again until you unblock them.").assertExists()
        // The note, cleaned with its lines kept (the override gone), each line isolated, in its own box.
        rule.onNodeWithTag("desktop_note").assertExists()
        rule.onNodeWithText("\u2068spam\u2069\n\u2068again\u2069").assertExists()
        assertTrue("not a service", !has("Service"))
        rule.onNodeWithText("Approve").performClick()
        rule.onNodeWithText("Deny").performClick()
        assertEquals(1, approved)
        assertEquals(1, denied)
    }

    @Test
    fun aRemovalShowsTheOwnersTextsAndNoNoteBox() {
        show(state(held(HeldRequest.DesktopConnection(true, "c1", null))))
        rule.onNodeWithText("$laptop wants to remove $sam").assertExists()
        rule.onNodeWithText("Removing ends your connection with $sam.").assertExists()
        rule.onNodeWithTag("desktop_note").assertDoesNotExist()
        rule.onNodeWithText("Approve").assertExists()
        rule.onNodeWithText("Deny").assertExists()
    }

    @Test
    fun aBlockWithANoteEmptyOnceCleanedHasNoBox() {
        show(state(held(HeldRequest.DesktopConnection(false, "c1", " \u200B\n\t "))))
        rule.onNodeWithText("$laptop wants to block $sam").assertExists()
        rule.onNodeWithTag("desktop_note").assertDoesNotExist()
    }

    @Test
    fun everyFormForAPersonAndAServiceShowsItsTexts() {
        var shown by mutableStateOf(state(held(HeldRequest.DesktopConnection(false, "c1", "spam"))))
        rule.setContent { ApprovalDetailScreen(shown, DecisionActions()) }
        // Block with a note, block without, removal (which carries no note); each for a person and a service.
        val cases = listOf(false to "spam", false to null, true to null).flatMap { (r, n) -> listOf(false, true).map { Triple(r, n, it) } }
        for ((remove, note, service) in cases) {
            val name = if (service) "Shop" else "Sam Lee"
            val who = "\u2068$name\u2069"
            shown = state(held(HeldRequest.DesktopConnection(remove, "c1", note), name = name, service = service))
            val case = "remove=$remove note=$note service=$service"
            if (remove) {
                rule.onNodeWithText("$laptop wants to remove $who").assertExists()
                rule.onNodeWithText("Removing ends your connection with $who.").assertExists()
            } else {
                rule.onNodeWithText("$laptop wants to block $who").assertExists()
                rule.onNodeWithText("Blocking ends your connection with $who. They can't connect with you again until you unblock them.").assertExists()
            }
            assertEquals(case, note != null, rule.onAllNodesWithTag("desktop_note").fetchSemanticsNodes().isNotEmpty())
            // A service's label beside its name, on its own line under the title (not beside the "Desktop" kind word).
            assertEquals(case, service, rule.onAllNodesWithTag("desktop_service").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithText("Approve").assertExists()
            rule.onNodeWithText("Deny").assertExists()
        }
    }

    @Test
    fun aServicesLineIsAboveTheKindWord() {
        show(state(held(HeldRequest.DesktopConnection(true, "svc", null), name = "Shop", service = true)))
        val line = rule.onNodeWithTag("desktop_service").fetchSemanticsNode().boundsInRoot
        val kind = rule.onNodeWithTag("device_kind").fetchSemanticsNode().boundsInRoot
        assertTrue("the Service line ($line) comes before the kind word ($kind)", line.bottom <= kind.top)
        rule.onNodeWithText("Service", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aServicesListRowCarriesTheLabelBesideItsName() {
        rule.setContent {
            ApprovalsScreen(
                ApprovalsUiState(loading = false, approvals = listOf(held(HeldRequest.DesktopConnection(true, "svc", null), name = "Shop", service = true))),
                com.vettid.core.ui.components.ShellChrome("Sam", onMenuClick = {}, onAvatarClick = {}),
            )
        }
        rule.onNodeWithTag("desktop_service", useUnmergedTree = true).assertExists()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theNoteIsInABorderedBox() {
        show(state(held(HeldRequest.DesktopConnection(false, "c1", "spam"))))
        val px = rule.onNodeWithTag("desktop_note").captureToImage().toPixelMap()
        val mid = px.height / 2
        // The edge is the outline, the inside the background: they differ only with a border.
        assertTrue("no border at the box's edge", px[0, mid] != px[EDGE_IN, mid])
    }

    private companion object {
        /** A pixel inside the box's padding, past its 1 dp border. */
        const val EDGE_IN = 6
    }

    @Test
    fun anUnresolvedConnectionKeepsTheDenyOnlyRow() {
        show(state(held(HeldRequest.DesktopConnection(false, "gone", "spam"), name = null)))
        rule.onNodeWithText("$laptop asks to approve a request").assertExists()
        rule.onNodeWithText("Desktop requests can't be shown in this version of VettID, so you can only deny them.").assertExists()
        assertTrue("no Approve", !has("Approve"))
        rule.onNodeWithText("Deny").assertExists()
        rule.onNodeWithTag("desktop_note").assertDoesNotExist()
        // The removal, and a name that is empty once cleaned, are the same row.
        assertEquals(HeldShow.Desktop, DeviceAsks.show(held(HeldRequest.DesktopConnection(true, "gone", null), name = null), emptyList()))
        assertEquals(HeldShow.Desktop, DeviceAsks.show(held(HeldRequest.DesktopConnection(false, "c1", null), name = " \u200B "), emptyList()))
    }

    @Test
    fun anApprovalsAnswersReadAsThePlanSays() {
        val a = held(HeldRequest.DesktopConnection(false, "c1", null))
        var outcome by mutableStateOf<DeviceOutcome>(DeviceOutcome.Approved("ok"))
        rule.setContent { ApprovalDetailScreen(state(a).copy(outcome = outcome), DecisionActions()) }
        rule.onNodeWithText("Approved").assertExists()
        // The held request's own answer when it ran is not the error not_found: "Any other result".
        for (code in listOf("not_found", "exists", "limit", "bad_request")) {
            outcome = DeviceOutcome.Approved(code)
            rule.onNodeWithText("Approved, but your vault answered: \u2068$code\u2069.").assertExists()
        }
        outcome = DeviceOutcome.NoLongerWaiting
        rule.onNodeWithText("This request is no longer waiting.").assertExists()
    }

    @Test
    fun theListRowIsTitledAsTheScreen() {
        rule.setContent { androidx.compose.material3.Text(titleOf(held(HeldRequest.DesktopConnection(false, "c1", null)))) }
        rule.onNodeWithText("$laptop wants to block $sam").assertExists()
    }
}
