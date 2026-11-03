package com.vettid.feature.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The daily owner check is due (owner-approved 2026-11-03, as vettid-ios #201): the list says it is on hold, not failed. */
@RunWith(RobolectricTestRunner::class)
class HistoryHeldTest {
    @get:Rule
    val rule = createComposeRule()

    private val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
    private val rows = listOf(AuditRecord("e1", 1, Instant.now().minusSeconds(60), "vault.unlocked"))
    private val title = "History is on hold"
    private val body = "Your daily check is due. They'll show again once it's done."

    @Test
    fun theTextsAreTheOwnersWordForWord() {
        val r = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        assertEquals(title, r.getString(R.string.history_held_title))
        assertEquals(body, r.getString(R.string.history_held_body))
    }

    @Test
    fun aHeldListWithoutRowsShowsTheInfoNoticeAndNoRetry() {
        rule.setContent {
            HistoryScreen(HistoryUiState(loading = false, error = FailureKind.OWNER_CHECK_REQUIRED), chrome, HistoryActions())
        }
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText(body).assertIsDisplayed()
        rule.onNodeWithText("Try again").assertDoesNotExist()
        rule.onNodeWithText("History could not be loaded").assertDoesNotExist()
    }

    @Test
    fun aHeldListWithRowsShowsTheNoticeAboveThemAndNoRetry() {
        rule.setContent {
            HistoryScreen(HistoryUiState(rows, loading = false, error = FailureKind.OWNER_CHECK_REQUIRED), chrome, HistoryActions())
        }
        rule.onNodeWithTag("history_held_notice").assertIsDisplayed()
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun anotherFailureStillShowsTheErrorWithTryAgain() {
        rule.setContent { HistoryScreen(HistoryUiState(loading = false, error = FailureKind.NO_RESPONSE), chrome, HistoryActions()) }
        rule.onNodeWithText("History could not be loaded").assertIsDisplayed()
        rule.onNodeWithText("Try again").assertIsDisplayed()
        rule.onNodeWithText(title).assertDoesNotExist()
    }
}
