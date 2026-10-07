package com.vettid.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The hero glyph in a [FormScaffold] header (the lock on the unlock screen, the rook, the check mark) is centred
 * horizontally without each screen having to align it; the title stays start-aligned.
 */
@RunWith(RobolectricTestRunner::class)
class FormScaffoldHeaderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun headerGlyphIsCentredAndTitleStaysStartAligned() {
        rule.setContent {
            FormScaffold(
                title = "Unlock your vault",
                primaryLabel = "Unlock",
                onPrimary = {},
                modifier = Modifier.testTag("scaffold"),
                header = { Icon(Icons.Outlined.Lock, null, Modifier.testTag("glyph").size(40.dp)) },
            ) {}
        }
        val root = rule.onNodeWithTag("scaffold").getBoundsInRoot()
        val glyph = rule.onNodeWithTag("glyph").getBoundsInRoot()
        val title = rule.onNodeWithText("Unlock your vault").getBoundsInRoot()

        val rootCentre = (root.left + root.right).value / 2
        val glyphCentre = (glyph.left + glyph.right).value / 2
        assertEquals(rootCentre, glyphCentre, 1f)
        assertTrue("title starts at the left padding", title.left.value < glyph.left.value)
    }
}
