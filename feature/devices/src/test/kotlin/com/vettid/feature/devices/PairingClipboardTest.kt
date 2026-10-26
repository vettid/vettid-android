package com.vettid.feature.devices

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.vettid.core.ui.components.CLIP_EXTRA_IS_SENSITIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The pairing code on the clipboard (ANDROID-PLAN 0.1.53 AA2, decision 1). */
@RunWith(RobolectricTestRunner::class)
class PairingClipboardTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val cm get() = context.getSystemService(ClipboardManager::class.java)
    private val exp = Instant.parse("2026-10-26T12:10:00Z")

    private fun text() = cm.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun theCodeIsCopiedMarkedSensitive() {
        PairingClipboard.copy(context, "CODE-1", exp, "Pairing code")
        assertEquals("CODE-1", text())
        assertTrue(cm.primaryClipDescription!!.extras!!.getBoolean(CLIP_EXTRA_IS_SENSITIVE))
        PairingClipboard.ended(context)
    }

    @Test
    fun whenThePairingEndsTheClipIsClearedIfItIsStillTheCode() {
        PairingClipboard.copy(context, "CODE-2", exp, "Pairing code")
        PairingClipboard.ended(context)
        assertNull(text())
    }

    @Test
    fun otherTextTheMemberCopiedMeanwhileIsLeftAlone() {
        PairingClipboard.copy(context, "CODE-3", exp, "Pairing code")
        cm.setPrimaryClip(ClipData.newPlainText("x", "my shopping list"))
        PairingClipboard.ended(context)
        assertEquals("my shopping list", text())
    }

    @Test
    fun aLiveCodeStaysOnTheClipboardWhenTheAppReturns() {
        PairingClipboard.copy(context, "CODE-4", exp, "Pairing code")
        PairingClipboard.onFocus(context, exp.minusSeconds(60))
        assertEquals("CODE-4", text())
        // Past its end, the next return clears it.
        PairingClipboard.onFocus(context, exp.plusSeconds(1))
        assertNull(text())
    }

    @Test
    fun nothingIsClearedThatWasNeverCopiedHere() {
        PairingClipboard.ended(context)
        cm.setPrimaryClip(ClipData.newPlainText("x", "unrelated"))
        PairingClipboard.onFocus(context, exp.plusSeconds(1))
        assertNotNull(text())
    }
}
