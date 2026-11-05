package com.vettid.feature.history

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.vettid.core.data.vault.ExportAnswer
import com.vettid.core.data.vault.ExportFormat
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** A6 audit A28: the wait counts down in the supporting line; the live error says it once, without the count. */
@RunWith(RobolectricTestRunner::class)
class HistoryExportPinBackoffTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theLiveErrorHasNoCountdownAndTheSupportingLineCounts() {
        rule.mainClock.autoAdvance = false
        val step = ExportStep.Pin(
            ExportAnswer(count = 3, more = false, uptoSeq = 3, uptoHash = "h"),
            ExportFormat.JSON,
            error = FailureKind.BACKOFF,
            retryUntil = Instant.now().plusSeconds(60),
        )
        rule.setContent { HistoryExportContent(step, ExportFilterWords(), HistoryExportActions()) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("secret_field_error", useUnmergedTree = true).assertTextEquals("Too many wrong PINs. Try again later.")
        rule.onNodeWithTag("secret_field_supporting", useUnmergedTree = true).assertTextContains("Try again in", substring = true)
        val live = { tag: String ->
            rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        }
        assertEquals(LiveRegionMode.Polite, live("secret_field_error"))
        assertNull(live("secret_field_supporting"))
    }
}
