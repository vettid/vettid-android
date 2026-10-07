package com.vettid.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.NameRequestState
import com.vettid.core.data.vault.NameRequestView
import com.vettid.core.data.vault.OwnProfile
import com.vettid.core.ui.format.Times
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The shared profile and the change-name flow as rendered (ANDROID-PLAN 0.1.10, VAULT-MESSAGING 0.18.0 §10.8). */
@RunWith(RobolectricTestRunner::class)
class ChangeNameScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val account = AccountInfo("a***@example.org", state = "member", firstName = "Ada", lastName = "Lovelace")
    private val pending = NameRequestView(4, "Ada", "King", now, NameRequestState.PENDING)

    @Test
    fun theSharedProfileShowsTheNamesReadOnlyAndTheDisplayNameEditable() {
        rule.setContent {
            SharedProfileContent(
                SharedProfileUiState(
                    account,
                    OwnProfile(2, "Countess", "Ada", "Lovelace", "9a1f bb7d 873e eafb 494b ef94 f072 7b25"),
                    "Countess",
                ),
                SharedProfileActions(),
            )
        }
        rule.onNodeWithTag("profile_full_name").assertTextContains("Ada Lovelace")
        rule.onNodeWithTag("display_name").assertTextContains("Countess")
        rule.onNodeWithTag("change_name").assertIsDisplayed()
        rule.onNodeWithText("9a1f bb7d 873e eafb 494b ef94 f072 7b25").assertExists()
        assertEquals(0, rule.onAllNodesWithText("verified", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aPendingRequestIsShown() {
        rule.setContent {
            SharedProfileContent(SharedProfileUiState(account.copy(nameRequest = pending), null), SharedProfileActions())
        }
        rule.onNodeWithTag("name_status").assertTextContains("Name change requested", substring = true)
        rule.onNodeWithTag("name_status").assertTextContains("Ada King", substring = true)
    }

    @Test
    fun tooSoonSaysWhenAndBlocksTheNames() {
        val after = now.plusSeconds(86_400 * 12)
        rule.setContent {
            ChangeNameContent(
                ChangeNameUiState(account = account.copy(nameAllowedAfter = after), first = "Ada", last = "King"),
                ChangeNameActions(),
                now,
            )
        }
        rule.onNodeWithTag("name_too_soon").assertTextContains(
            "You can change your name once every 30 days. You can change it again on ${Times.dayLabel(Times.day(after))}.",
            substring = true,
        )
        rule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun theRuleIsShownOnTheNames() {
        rule.setContent {
            ChangeNameContent(ChangeNameUiState(account = account, first = "Ada1", last = "", checked = true), ChangeNameActions(), now)
        }
        rule.onNodeWithText("Use letters, spaces and ' ’ . - only, starting with a letter.").assertIsDisplayed()
        rule.onNodeWithText("Enter a name.").assertIsDisplayed()
    }

    @Test
    fun theConfirmStepAsksForThePinAndPasswordTogether() {
        rule.setContent {
            ChangeNameContent(
                ChangeNameUiState(ChangeNameStep.CONFIRM, account, "Ada", "King", message = ChangeNameMessage.BadPassword(6)),
                ChangeNameActions(),
                now,
            )
        }
        rule.onNodeWithTag("name_pin").assertIsDisplayed()
        rule.onNodeWithTag("name_password").assertIsDisplayed()
        rule.onNodeWithText("That password is not correct.").assertIsDisplayed()
        rule.onNodeWithTag("name_checks_left").assertTextContains("6 more wrong tries lock your vault.")
    }

    @Test
    fun theResultFollowsTheRequest() {
        rule.setContent {
            ChangeNameContent(
                ChangeNameUiState(
                    ChangeNameStep.SENT, account, "Ada", "King",
                    request = pending.copy(state = NameRequestState.REFUSED, reason = NameRequestView.REASON_ACCOUNT),
                ),
                ChangeNameActions(),
                now,
            )
        }
        rule.onNodeWithTag("change_name_sent_refused").assertIsDisplayed()
        rule.onNodeWithText("Your account cannot change its name right now.").assertIsDisplayed()
    }
}
