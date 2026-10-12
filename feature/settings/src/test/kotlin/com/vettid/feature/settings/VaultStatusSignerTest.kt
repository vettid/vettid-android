package com.vettid.feature.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.vettid.core.data.vault.ReleaseInfoView
import com.vettid.core.data.vault.SignerClass
import com.vettid.core.data.vault.SignerView
import com.vettid.core.data.vault.VaultOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings → Vault status: the "Installed from" line's details open directly under the line (owner report
 * 2026-10-11: they opened below every other row, off-screen on a phone), and the line says whether they are shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp") // a small phone: the rows below the line fill the screen
class VaultStatusSignerTest {
    @get:Rule
    val rule = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val overview = VaultOverview(
        "3f9c2a7be41d4c0e9b8a6f5d2c1e0a9b", "unlocked", ReleaseInfoView(10, "active", null, 10, null), 10, null, false, 1, 4,
        signer = SignerView(SignerClass.GOOGLE_PLAY, "31a19613aa10f209e08945f947f94f7ce3e6e5ac342457ff9969a67986928e65"),
    )

    private fun state(text: Int) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, res.getString(text))

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun theDetailsOpenDirectlyUnderTheLine() {
        rule.setContent { VaultStatusContent(LoadState(false, overview), {}, {}) }
        rule.onNodeWithTag("signer_details").assertDoesNotExist()
        rule.onNodeWithTag("signer_line").assert(state(R.string.settings_signer_details_hidden))
        assertTrue(SemanticsActions.Expand in rule.onNodeWithTag("signer_line").fetchSemanticsNode().config)

        rule.onNodeWithTag("signer_line").performClick()
        rule.onNodeWithTag("signer_details").assertIsDisplayed()
        rule.onNodeWithTag("signer_line").assert(state(R.string.settings_signer_details_shown))
        val line = bounds("signer_line")
        val details = bounds("signer_details")
        val next = rule.onNodeWithText(res.getString(R.string.settings_status_vault_id)).fetchSemanticsNode().boundsInRoot
        assertTrue("under the line: $line, $details", details.top >= line.bottom && details.top - line.bottom < line.height)
        assertTrue("above the next row: $details, $next", details.bottom <= next.top)
        rule.onNodeWithText("31a19613 aa10f209", substring = true).assertExists()

        rule.onNodeWithTag("signer_line").performClick()
        rule.onNodeWithTag("signer_details").assertDoesNotExist()
        rule.onNodeWithTag("signer_line").assert(state(R.string.settings_signer_details_hidden))
    }

    @Test
    fun talkBackExpandsAndCollapsesTheLine() {
        rule.setContent { VaultStatusContent(LoadState(false, overview), {}, {}) }
        rule.onNodeWithTag("signer_line").performSemanticsAction(SemanticsActions.Expand)
        rule.onNodeWithTag("signer_details").assertIsDisplayed()
        val config = rule.onNodeWithTag("signer_line").fetchSemanticsNode().config
        assertTrue(SemanticsActions.Collapse in config && SemanticsActions.Expand !in config)
        assertEquals(res.getString(R.string.settings_signer_details_shown), config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithTag("signer_line").performSemanticsAction(SemanticsActions.Collapse)
        rule.onNodeWithTag("signer_details").assertDoesNotExist()
    }

    @Test
    fun theCatalogsOpenStateShowsTheDetailsUnderTheLineToo() {
        rule.setContent { VaultStatusContent(LoadState(false, overview), {}, {}, signerDetails = true) }
        rule.onNodeWithTag("signer_details").assertIsDisplayed()
        assertTrue(bounds("signer_details").top >= bounds("signer_line").bottom)
    }

    @Test
    fun noLineWithoutASigner() {
        rule.setContent { VaultStatusContent(LoadState(false, overview.copy(signer = null)), {}, {}) }
        rule.onNodeWithTag("signer_line").assertDoesNotExist()
        rule.onNodeWithTag("signer_details").assertDoesNotExist()
    }
}
