// Test data: long assertion rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import com.vettid.core.data.social.ApprovalDocument
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** VAULT-MESSAGING 0.89.0 §10.4 "Approval documents": the screen draws the largest documents and holds Approve until it is seen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ApprovalDocumentScreenTest {
    @get:Rule
    val rule = createComposeRule()

    /** The screen over [json]'s document, its state kept as the view model keeps it ([ApprovalDetailViewModel.documentSeen]). */
    private fun show(json: String) {
        val r = DocumentSamples.request(json)
        val d = DocumentSamples.document(json, r)
        val shown = DocumentStep.Shown(d, DocumentPresentation.of(d, d.context)!!)
        var state by mutableStateOf(ApprovalDetailUiState(r.key, r, password = "pw-fake-1", document = shown, documentGeneration = 1))
        rule.setContent {
            ApprovalDetailScreen(
                state,
                DecisionActions(
                    onDocumentSeen = { sha, gen, whole ->
                        if (sha == d.sha256 && gen == state.documentGeneration) state = state.copy(documentSeen = sha.takeIf { whole })
                    },
                ),
            )
        }
        rule.waitForIdle()
    }

    private fun list() = rule.onNodeWithTag(DOCUMENT_LIST_TAG)

    /**
     * Scrolls down most of a screenful at a time to the end (a member reading it through); whether a node with [watch] in
     * its description was on screen on the way.
     */
    private fun scrollThrough(watch: String? = null): Boolean {
        var watched = false
        repeat(MAX_STEPS) {
            if (watch != null && !watched) {
                watched = runCatching { rule.onNode(hasContentDescription(watch, substring = true)).assertIsDisplayed() }.isSuccess
            }
            val node = list().fetchSemanticsNode()
            val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return watched
            if (range.value() >= range.maxValue()) return watched
            list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, node.size.height * STEP) }
            rule.waitForIdle()
        }
        error("did not reach the end")
    }

    private fun approve() = rule.onNodeWithTag("primary_button")

    @Test
    fun aValueOf65KbIsDrawnToItsEndAndApprovableOnceReadThrough() {
        val json = DocumentSamples.big(65_000)
        assertTrue(DocumentSamples.canonical(json).size <= ApprovalDocument.MAX_BYTES)
        show(json)
        approve().assertIsNotEnabled()
        // The string's last piece, with its last character, came on screen and is read as text.
        assertTrue(scrollThrough(watch = "xZ, text"))
        approve().assertIsEnabled()
    }

    @Test
    fun aJumpToTheEndDoesNotCount() {
        show(DocumentSamples.big(8_000))
        rule.onNodeWithText("Scroll to the end of the request to approve it.").assertIsDisplayed()
        list().performScrollToIndex(rowsEnd())
        rule.waitForIdle()
        approve().assertIsNotEnabled()
        // Back to the top and read down: now it counts.
        list().performScrollToIndex(0)
        rule.waitForIdle()
        scrollThrough()
        approve().assertIsEnabled()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag("document_read_to_end")).fetchSemanticsNodes().size)
    }

    private fun rowsEnd(): Int {
        val r = DocumentSamples.request(DocumentSamples.big(8_000))
        val d = DocumentSamples.document(DocumentSamples.big(8_000), r)
        return DocumentRows.of(DocumentPresentation.of(d, d.context)!!).size + 1
    }

    @Test
    fun onlyDenyWhileLoadingFailedOrUnshowable() {
        val r = DocumentSamples.request(DocumentSamples.EXTEND)
        var state by mutableStateOf(ApprovalDetailUiState(r.key, r, document = DocumentStep.Loading))
        rule.setContent { ApprovalDetailScreen(state, DecisionActions()) }
        for (step in listOf(DocumentStep.Loading, DocumentStep.Failed(FailureKind.NETWORK, null), DocumentStep.Unshowable(ApprovalDocument.Failure.HASH))) {
            state = state.copy(document = step)
            rule.waitForIdle()
            assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag("primary_button")).fetchSemanticsNodes().size)
            assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag("approval_password")).fetchSemanticsNodes().size)
            rule.onNodeWithTag("deny_button").assertIsDisplayed()
        }
        rule.onNodeWithText("This request can't be shown").assertIsDisplayed()
    }

    private companion object {
        const val MAX_STEPS = 4_000
        /** Of the list's visible height. */
        const val STEP = 0.8f
    }
}
