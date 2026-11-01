package com.vettid.core.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A copied secret leaves the clipboard after 60 s (or when the member next returns to VettID, if the app was in the
 * background) and when the vault closes, but never something copied since.
 */
@RunWith(RobolectricTestRunner::class)
class SecureClipboardTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val cm get() = context.getSystemService(ClipboardManager::class.java)
    private val t0 = 1_000_000L

    private fun text() = cm.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun aSecretIsCopiedSensitiveAndMarkedWithTheTimeItIsClearedAt() {
        SecureClipboard.copy(context, "Card number", "4111", sensitive = true, nowMs = t0)
        assertEquals("4111", text())
        val extras = cm.primaryClipDescription!!.extras!!
        assertTrue(extras.getBoolean(CLIP_EXTRA_IS_SENSITIVE))
        assertEquals(t0 + 60_000, extras.getLong(SecureClipboard.EXTRA_CLEAR_AT))
    }

    @Test
    fun itIsClearedAfterSixtySecondsAndNotBefore() {
        SecureClipboard.copy(context, "Card number", "4111", sensitive = true, nowMs = t0)
        SecureClipboard.onFocus(context, t0 + 59_999)
        assertEquals("4111", text())
        SecureClipboard.onFocus(context, t0 + 60_000)
        assertNull(text())
    }

    @Test
    fun somethingCopiedSinceIsNeverCleared() {
        SecureClipboard.copy(context, "Card number", "4111", sensitive = true, nowMs = t0)
        cm.setPrimaryClip(ClipData.newPlainText("other", "the member copied this"))
        SecureClipboard.onFocus(context, t0 + 120_000)
        SecureClipboard.vaultClosed(context)
        assertEquals("the member copied this", text())
        // Even the same label and sensitive flag, without our mark, is not ours.
        cm.setPrimaryClip(textClip("Card number", "5500", sensitive = true))
        SecureClipboard.vaultClosed(context)
        assertEquals("5500", text())
    }

    @Test
    fun whenTheVaultClosesTheSecretGoesAtOnce() {
        SecureClipboard.copy(context, "Password", "hunter2", sensitive = true, nowMs = t0)
        SecureClipboard.vaultClosed(context)
        assertNull(text())
    }

    /** The mark is in the clip, so a process that died after the copy still clears it at the next start. */
    @Test
    fun aClipOfAnEarlierProcessIsClearedAtStartBecauseTheVaultIsNotOpenYet() {
        cm.setPrimaryClip(ClipData.newPlainText("Card number", "4111").also {
            it.description.extras = android.os.PersistableBundle().apply {
                putBoolean(CLIP_EXTRA_IS_SENSITIVE, true)
                putLong(SecureClipboard.EXTRA_CLEAR_AT, t0 + 60_000)
            }
        })
        SecureClipboard.vaultClosed(context)
        assertNull(text())
    }

    @Test
    fun anOrdinaryCopyIsNotMarkedAndStays() {
        SecureClipboard.copy(context, "Name", "Sam", sensitive = false, nowMs = t0)
        assertNull(cm.primaryClipDescription!!.extras?.takeIf { it.containsKey(SecureClipboard.EXTRA_CLEAR_AT) })
        SecureClipboard.vaultClosed(context)
        SecureClipboard.onFocus(context, t0 + 600_000)
        assertEquals("Sam", text())
    }

    /** A receive address (the owner's decision 2a): hidden from previews, but never cleared, not at 60 s and not at a lock. */
    @Test
    fun aHiddenCopyIsMarkedSensitiveButNeverCleared() {
        SecureClipboard.copyHidden(context, "Bitcoin address", "tb1qaddress")
        val extras = cm.primaryClipDescription!!.extras!!
        assertTrue(extras.getBoolean(CLIP_EXTRA_IS_SENSITIVE))
        assertTrue(!extras.containsKey(SecureClipboard.EXTRA_CLEAR_AT))
        SecureClipboard.onFocus(context, System.currentTimeMillis() + 600_000)
        SecureClipboard.vaultClosed(context)
        assertEquals("tb1qaddress", text())
    }

    @Test
    fun aHiddenCopyAfterASecretIsNotClearedByTheSecretsTime() {
        SecureClipboard.copy(context, "Password", "hunter2", sensitive = true, nowMs = t0)
        SecureClipboard.copyHidden(context, "Bitcoin address", "tb1qaddress")
        SecureClipboard.onFocus(context, t0 + 60_000)
        assertEquals("tb1qaddress", text())
    }
}
