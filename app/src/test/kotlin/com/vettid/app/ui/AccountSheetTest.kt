package com.vettid.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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

/**
 * The avatar sheet (owner feedback 2026-10-08): "Lock vault" right under the name, before the names, the shared
 * profile and the address; the sheet scrolls, so every entry is reachable on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val account = AccountInfo(
        "s***@example.org",
        email = "sam@example.org",
        state = "member",
        firstName = "Sam",
        lastName = "Rivera",
    )

    /** The sheet's body in a phone-sized window: shorter than its options. */
    private fun show(onLock: () -> Unit = {}) = rule.setContent {
        Box(Modifier.height(640.dp)) {
            AvatarSheetContent(name = "Sam Rivera", detail = "sam@example.org", optionsHeader = "Options") {
                AccountSheetOptions(account, "https://account.vettid.org", onLock, now)
            }
        }
    }

    /** The node's top in the sheet's (unclipped) layout: entries below the window still have their place. */
    private fun top(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    @Test
    fun lockVaultComesRightAfterTheHeaderBeforeTheOtherEntries() {
        show()
        rule.onNodeWithTag("account_lock_vault").assertIsDisplayed()
        val lock = top("account_lock")
        assertTrue(lock < top("account_names"))
        assertTrue(lock < top("account_change_name"))
        assertTrue(lock < top("account_shared_profile"))
        assertTrue(lock < top("account_email"))
        assertTrue(lock < top("account_details"))
        assertTrue(top("account_details") < top("account_vault"))
    }

    @Test
    fun theSheetScrollsSoTheLastEntryIsReachable() {
        show()
        rule.onNodeWithTag("avatar_sheet_content").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        rule.onNodeWithTag("account_portal").assertIsNotDisplayed()
        rule.onNodeWithTag("account_portal").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun lockVaultLocksAtOnce() {
        var locked = 0
        show(onLock = { locked++ })
        rule.onNodeWithTag("account_lock_vault").performClick()
        assertEquals(1, locked)
    }
}
