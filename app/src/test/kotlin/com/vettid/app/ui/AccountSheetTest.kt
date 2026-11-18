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
import androidx.compose.ui.test.onNodeWithText
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

    /**
     * While the vault is not open (owner decision 2026-10-21) the sheet shows no name and no address, not even the masked
     * hint the account record keeps for the unlock screen.
     */
    @Test
    fun whileTheVaultIsNotOpenTheSheetShowsNoAddressAndNoName() {
        val locked = AccountInfo("s***@example.org")
        rule.setContent {
            Box(Modifier.height(640.dp)) {
                AvatarSheetContent(name = "VettID member", detail = "", optionsHeader = "Options") {
                    AccountSheetOptions(locked, "https://account.vettid.org", {}, now)
                }
            }
        }
        rule.onNodeWithTag("account_email").assertDoesNotExist()
        rule.onNodeWithText("s***@example.org", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Sam", substring = true).assertDoesNotExist()
    }

    /** The snapshot always carries `email` (VAULT-MESSAGING 0.20.0): without a readable one, no address, never the hint. */
    @Test
    fun anOpenVaultWithoutAnAddressShowsNoHint() {
        val noEmail = AccountInfo("s***@example.org", state = "member")
        rule.setContent {
            AccountSheet(noEmail, "https://account.vettid.org", onDismiss = {}, onLockVault = {}, now = now)
        }
        // The sheet is drawn (its header and options), and nothing of the masked hint is in it.
        rule.onNodeWithTag("account_lock_vault").assertExists()
        rule.onNodeWithText("VettID member", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("account_email").assertDoesNotExist()
        rule.onNodeWithText("s***@example.org", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theOpenVaultsAddressIsShown() {
        show()
        rule.onNodeWithTag("account_email").performScrollTo().assertIsDisplayed()
    }

    /** Rule 3 (the release gate's sheet): without "Change name" and "Shared profile"; Lock vault stays first. */
    @Test
    fun theGatesSheetHasNoNameOrProfileRows() {
        rule.setContent {
            Box(Modifier.height(640.dp)) {
                AvatarSheetContent(name = "Sam Rivera", detail = "sam@example.org", optionsHeader = "Options") {
                    AccountSheetOptions(account, "https://account.vettid.org", {}, now, onChangeName = null, onSharedProfile = null)
                }
            }
        }
        rule.onNodeWithTag("account_lock_vault", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("account_names", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("account_change_name", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("account_shared_profile", useUnmergedTree = true).assertDoesNotExist()
    }
}
