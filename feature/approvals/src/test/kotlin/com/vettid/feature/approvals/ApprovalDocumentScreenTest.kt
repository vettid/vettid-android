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
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import org.robolectric.annotation.GraphicsMode

/** VAULT-MESSAGING 0.89.0 §10.4 "Approval documents": the screen draws the largest documents and holds Approve until it is seen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ApprovalDocumentScreenTest {
    @get:Rule
    val rule = createComposeRule()

    /** The state the screen shows, as the view model keeps it. */
    private var state by mutableStateOf(ApprovalDetailUiState("auth:none"))

    private fun shownOf(json: String): DocumentStep.Shown {
        val r = DocumentSamples.request(json)
        val d = DocumentSamples.document(json, r)
        return DocumentStep.Shown(d, DocumentPresentation.of(d, d.context)!!)
    }

    /** The font scale the screen is drawn at (null: the configuration's), changeable while it is shown. */
    private var scale by mutableStateOf<Float?>(null)

    /** Every "seen" report, in order, and the approvals asked for. */
    private val reports = mutableListOf<Boolean>()
    private var approvals = 0

    /**
     * The screen over [json]'s document, its state kept as the view model keeps it ([ApprovalDetailViewModel.documentSeen]),
     * at [fontScale], with a screen reader or not.
     */
    private fun show(json: String, fontScale: Float? = null, screenReader: Boolean = false, seenBefore: Boolean = false, ime: Boolean = false) {
        val r = DocumentSamples.request(json)
        val shown = shownOf(json)
        state = ApprovalDetailUiState(
            r.key, r, password = "pw-fake-1", document = shown, documentGeneration = 1,
            documentSeen = shown.document.sha256.takeIf { seenBefore },
        )
        fontScale?.let { org.robolectric.RuntimeEnvironment.setFontScale(it) }
        scale = fontScale
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale ?: density.fontScale)) {
                DocumentApprovalScreen(
                    r,
                    state,
                    DecisionActions(
                        onApprove = { approvals++ },
                        onDocumentSeen = { sha, gen, whole ->
                            reports += whole
                            val d = (state.document as? DocumentStep.Shown)?.document
                            if (sha == d?.sha256 && gen == state.documentGeneration) state = state.copy(documentSeen = sha.takeIf { whole })
                        },
                    ),
                    Modifier,
                    imeVisible = ime,
                    screenReader = screenReader,
                )
            }
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
                watched = runCatching { rule.onNode(hasText(watch, substring = true)).assertIsDisplayed() }.isSuccess
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
        assertTrue(scrollThrough(watch = "xZ\""))
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
        // A notice of one sentence has no title: no empty text is read.
        assertEquals(0, rule.onAllNodes(hasText("")).fetchSemanticsNodes().size)
    }

    @Test
    fun aQuorumAbove2To31BallotsIsShownAsItIs() {
        show(DocumentSamples.PROPOSAL.replace("\"min_ballots\":1", "\"min_ballots\":3000000000"))
        list().performScrollToNode(hasText("3000000000 ballots"))
        rule.onNodeWithText("3000000000 ballots").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w240dp-h1600dp")
    // Real text measurement, so that the row wraps to its true height.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRowTallerThanTheScreenSeenPartwayThenJumpedPastDoesNotCount() {
        val json = """{"action":"pay.out","payload":{"k":"${"word ".repeat(19)}end"},"summary":"S"}"""
        show(json, fontScale = 3f)
        val rows = DocumentRows.of(shownOf(json).presentation)
        val tall = rows.indexOfFirst { it is DocRow.Raw && it.piece.level == 3 }
        assertTrue(tall > 0)
        val tallNode = rule.onNode(hasText("level 3, \"k\":", substring = true))
        // Read down until the tall row comes on screen: it is taller than the list, so only its top part is.
        repeat(MAX_STEPS) {
            if (runCatching { tallNode.assertIsDisplayed() }.isSuccess) return@repeat
            val node = list().fetchSemanticsNode()
            list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, node.size.height * 0.4f) }
            rule.waitForIdle()
        }
        tallNode.assertIsDisplayed()
        val viewport = list().fetchSemanticsNode().size.height
        assertTrue("the row (${tallNode.fetchSemanticsNode().size.height}) is taller than the list ($viewport)", tallNode.fetchSemanticsNode().size.height > viewport)
        // Jump past it, then read the rest down to the end.
        list().performScrollToIndex(ROWS_FROM + tall + 1)
        rule.waitForIdle()
        scrollThrough()
        approve().assertIsNotEnabled()
    }

    @Test
    fun aNewShowingStartsAtTheTop() {
        show(DocumentSamples.big(8_000))
        scrollThrough()
        assertTrue(scrollValue() > 0f)
        state = state.copy(document = shownOf(DocumentSamples.big(8_000)), documentGeneration = 2, documentSeen = null)
        rule.waitForIdle()
        assertEquals(0f, scrollValue())
        approve().assertIsNotEnabled()
    }

    @Test
    fun aDisabledApproveSaysWhyUntilTheDocumentIsSeen() {
        show(DocumentSamples.big(8_000))
        val why = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Scroll to the end of the request to approve it.")
        approve().assertIsNotEnabled().assert(why)
        scrollThrough()
        approve().assertIsEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        // Seen, but disabled for the password alone: the plain reading.
        state = state.copy(password = "")
        rule.waitForIdle()
        approve().assertIsNotEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    @Config(qualifiers = "w411dp-h640dp")
    fun withTheKeyboardUpTheListKeepsRoomAndTheHintAndCaptionGo() {
        val json = DocumentSamples.big(8_000)
        val r = DocumentSamples.request(json)
        state = ApprovalDetailUiState(r.key, r, password = "pw-fake-1", document = shownOf(json), documentGeneration = 1)
        var ime by mutableStateOf(false)
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        rule.setContent { DocumentApprovalScreen(r, state, DecisionActions(), Modifier, imeVisible = ime) }
        rule.waitForIdle()
        rule.onNodeWithText("Scroll to the end of the request to approve it.").assertExists()
        rule.onNodeWithText("This action uses your Protean Credential").assertExists()
        ime = true
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(hasText("Scroll to the end of the request to approve it.")).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("This action uses your Protean Credential")).fetchSemanticsNodes().size)
        // The list keeps at least a quarter of the window (640dp here, at density 1: 160px).
        val listHeight = list().fetchSemanticsNode().size.height
        assertTrue("list $listHeight", listHeight >= (640 * LIST_MIN_FRACTION).toInt() - 1)
        // Approve and Deny are still there, in the panel, which scrolls.
        approve().assertExists()
        rule.onNodeWithTag("deny_button").assertExists()
    }

    @Test
    fun theKeyboardsActionApprovesNothingBeforeTheDocumentIsSeen() {
        show(DocumentSamples.big(8_000))
        rule.onNodeWithTag("approval_password").performImeAction()
        rule.waitForIdle()
        assertEquals(0, approvals)
        scrollThrough()
        rule.onNodeWithTag("approval_password").performImeAction()
        rule.waitForIdle()
        assertEquals(1, approvals)
    }

    @Test
    fun aShowingSaysNotSeenFirst() {
        // Seen whole in an earlier composition (another text size, a recreation): this one starts from nothing seen.
        show(DocumentSamples.big(8_000), seenBefore = true)
        assertEquals(false, reports.first())
        approve().assertIsNotEnabled()
    }

    @Test
    fun anotherTextSizeStartsOverAndCanBeReadAgain() {
        show(DocumentSamples.big(8_000))
        scrollThrough()
        approve().assertIsEnabled()
        scale = 1.3f
        rule.waitForIdle()
        approve().assertIsNotEnabled()
        list().performScrollToIndex(0)
        rule.waitForIdle()
        scrollThrough()
        approve().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h1200dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withAScreenReaderTheDocumentIsCutSmaller() {
        show(DocumentSamples.big(3_000), screenReader = true)
        // Raw rows of at most 30 characters (closing punctuation of up to 3 past it).
        val raws = rule.onAllNodes(hasText("\"", substring = true)).fetchSemanticsNodes()
        assertTrue(raws.isNotEmpty())
        scrollThrough()
        approve().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w240dp-h2000dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRowTooTallForTheScreenReaderAtTheSmallestCuttingSaysSo() {
        show(DocumentSamples.big(400), fontScale = 12f, screenReader = true, ime = true)
        scrollThrough()
        rule.waitForIdle()
        rule.onNodeWithTag("document_too_tall").assertExists()
        rule.onNodeWithText("This request can't be shown at this text size").assertExists()
        approve().assertIsNotEnabled()
    }

    private fun scrollValue(): Float = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private companion object {
        const val MAX_STEPS = 4_000
        /** Of the list's visible height. */
        const val STEP = 0.8f
    }
}
