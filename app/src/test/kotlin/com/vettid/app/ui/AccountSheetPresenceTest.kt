// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.presence.PresenceState
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.ui.components.AvatarSheetContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The avatar sheet's status row (ANDROID-PLAN §4 "Presence", P1): "Status: Available" opening a picker of the four states. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountSheetPresenceTest {
    @get:Rule
    val rule = createComposeRule()

    private val account = AccountInfo("s***@example.org", email = "sam@example.org", state = "member", firstName = "Sam", lastName = "Rivera")

    private fun show(presence: SheetPresence?) = rule.setContent {
        AvatarSheetContent(name = "Sam Rivera", detail = "sam@example.org", optionsHeader = "Options") {
            AccountSheetOptions(account, "https://account.vettid.org", {}, Instant.parse("2026-11-03T12:00:00Z"), presence = presence)
        }
    }

    @Test
    fun oneRowUnderTheNameSaysTheStatus() {
        show(SheetPresence(PresenceState.AVAILABLE) {})
        rule.onNodeWithText("Status: Available").assertIsDisplayed()
        val top = { tag: String -> rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y }
        // One row under the name: before Lock vault and the names.
        assertTrue(top("account_status") < top("account_lock"))
        assertTrue(top("account_lock") < top("account_names"))
    }

    @Test
    fun itOpensAPickerOfTheFourStatesAndPickingSendsThatOne() {
        val picked = mutableListOf<PresenceState>()
        show(SheetPresence(PresenceState.AVAILABLE) { picked += it })
        rule.onNodeWithTag("account_status_row").performScrollTo().performClick()
        for (s in listOf("Busy", "Away", "Invisible")) rule.onNodeWithText(s).assertIsDisplayed()
        rule.onNodeWithText("Busy").performClick()
        assertEquals(listOf(PresenceState.BUSY), picked)
        // The dialog is closed after the pick.
        assertEquals(0, rule.onAllNodesWithTag("account_status_row_picker").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Away").fetchSemanticsNodes().size)
    }

    @Test
    fun theCurrentStateIsNotSentAgain() {
        val picked = mutableListOf<PresenceState>()
        show(SheetPresence(PresenceState.AWAY) { picked += it })
        rule.onNodeWithText("Status: Away").performScrollTo().performClick()
        rule.onNodeWithText("Away").performClick()
        assertTrue(picked.isEmpty())
    }

    @Test
    fun aRefusedPickShowsItsFailureTextInTheSheet() {
        show(SheetPresence(PresenceState.AVAILABLE, PresenceFailure(com.vettid.core.data.vault.FailureKind.NETWORK, null)) {})
        rule.onNodeWithTag("account_status_error").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Status: Available").assertIsDisplayed()
    }

    @Test
    fun noErrorNoticeWithoutAFailure() {
        show(SheetPresence(PresenceState.AVAILABLE) {})
        assertEquals(0, rule.onAllNodesWithTag("account_status_error").fetchSemanticsNodes().size)
    }

    @Test
    fun noRowWithoutPresence() {
        show(null)
        assertEquals(0, rule.onAllNodesWithTag("account_status").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Status:", substring = true).fetchSemanticsNodes().size)
    }
}
