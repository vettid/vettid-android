// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pair a desktop and the empty list's texts, approved by the owner 2026-11-10 (GC #7195, #7212) for both apps: a changed text is the owner's to approve. */
@RunWith(RobolectricTestRunner::class)
class PairDesktopTextsTest {
    private val r = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun theApprovedTextsAreUnchanged() {
        val approved = listOf(
            R.string.devices_pair_new to "Pair an agent or desktop",
            R.string.devices_pair_desktop to "Pair a desktop",
            R.string.devices_empty_title_any to "Nothing paired yet",
            R.string.devices_empty_body_any to "Pair VettID on your computer, or pair an agent to let an AI assistant use items you choose. They act only while you allow access.",
            R.string.pair_title_desktop to "Pair a desktop",
            R.string.pair_code_body_desktop to "Copy this code into VettID on your computer. It works once and expires in 10 minutes.",
            R.string.pair_calls_itself_note_desktop to "The code proves this phone talks to the desktop you just gave the code to. The name is only what the desktop says about itself.",
            R.string.pair_rejected_body_desktop to "The desktop is told it was rejected. If you did not expect a code, someone else may have the pairing code; it no longer works.",
            R.string.pair_allow_body_desktop to "A desktop acts only while you allow access.",
            R.string.pair_paired_body_desktop to "When it asks for access, the request comes to Approvals.",
        )
        for ((id, text) in approved) assertEquals(text, r.getString(id))
        assertEquals("Does the desktop show 123 456? Approve only if the codes match.", r.getString(R.string.pair_compare_body_desktop, "123 456"))
    }
}
