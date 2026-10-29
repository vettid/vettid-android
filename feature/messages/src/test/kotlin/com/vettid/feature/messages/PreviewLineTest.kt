package com.vettid.feature.messages

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A conversation row's preview (VAULT-MESSAGING 0.76.0 §10.8 step 6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreviewLineTest {
    private val you = { s: String -> "You: $s" }

    @Test
    fun firstLineThatShowsSomethingIsolated() {
        assertEquals("\u2068hi\u2069", previewLine("\u3164\n\u202E hi \nmore", false, false, "Photo", you))
        assertEquals("You: \u2068hi\u2069", previewLine("hi", true, false, "Photo", you))
    }

    @Test
    fun anEmptyTextLeavesOutTheLineAndItsLabelExceptForAPhoto() {
        assertEquals("", previewLine("\u200B\u3164", false, false, "Photo", you))
        assertEquals("", previewLine("\u200D", true, false, "Photo", you))
        assertEquals("Photo", previewLine("\u3164", false, true, "Photo", you))
        assertEquals("You: Photo", previewLine("", true, true, "Photo", you))
    }
}
