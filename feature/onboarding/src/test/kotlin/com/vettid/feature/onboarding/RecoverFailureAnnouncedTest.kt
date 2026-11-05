package com.vettid.feature.onboarding

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/** A6 audit A1, A16: a failure of the recovery or the move that comes after a tap is announced. */
@RunWith(RobolectricTestRunner::class)
class RecoverFailureAnnouncedTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = Proxy.newProxyInstance(RecoverActions::class.java.classLoader, arrayOf(RecoverActions::class.java)) { _, m, _ ->
        if (m.returnType == Boolean::class.javaPrimitiveType) false else null
    } as RecoverActions

    @Test
    fun theFailureCardIsAPoliteLiveRegion() {
        rule.setContent { RecoverContent(RecoverUiState(step = RecoverStep.PASSWORD, error = FailureKind.NETWORK), actions, {}, {}, {}) }
        rule.onNodeWithTag("error").assertExists()
        val live = rule.onAllNodesWithTag("error", useUnmergedTree = true).fetchSemanticsNodes().first()
        // The live region is the card's text column, a child of the tagged card.
        val regions = generateSequence(listOf(live)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }
            .flatten().filter { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }.toList()
        assertTrue(regions.isNotEmpty())
    }
}
