package com.vettid.app.core.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure clipboard operations with automatic clearing
 *
 * Security features:
 * - Auto-clear clipboard after timeout
 * - Mark clips as sensitive (Android 13+)
 * - Clear clipboard before app exit
 */
@Singleton
class SecureClipboard @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private var clearRunnable: Runnable? = null

    companion object {
        private const val DEFAULT_CLEAR_DELAY_MS = 60_000L // 1 minute
        private const val SENSITIVE_CLEAR_DELAY_MS = 30_000L // 30 seconds for sensitive data
        private const val CLIP_LABEL = "VettID"
    }

    /**
     * Copy text to clipboard with auto-clear after delay
     */
    fun copyText(
        text: String,
        clearDelayMs: Long = DEFAULT_CLEAR_DELAY_MS,
        isSensitive: Boolean = false
    ) {
        val clip = ClipData.newPlainText(CLIP_LABEL, text)

        // Mark as sensitive on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isSensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }

        clipboardManager.setPrimaryClip(clip)

        // Schedule auto-clear
        scheduleClipboardClear(if (isSensitive) SENSITIVE_CLEAR_DELAY_MS else clearDelayMs)
    }

    /**
     * Copy sensitive text (like passwords or recovery phrases)
     * Uses shorter timeout and marks as sensitive
     */
    fun copySensitiveText(text: String) {
        copyText(text, SENSITIVE_CLEAR_DELAY_MS, isSensitive = true)
    }

    /**
     * Clear clipboard immediately
     */
    fun clearClipboard() {
        clearScheduledClear()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboardManager.clearPrimaryClip()
            } else {
                // Pre-Android 9: set empty clip
                clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            // Some devices may throw security exceptions
        }
    }

    /**
     * Check if clipboard contains our sensitive data
     */
    fun hasVettIDClipData(): Boolean {
        return try {
            val clip = clipboardManager.primaryClip
            clip?.description?.label == CLIP_LABEL
        } catch (e: Exception) {
            false
        }
    }

    private fun scheduleClipboardClear(delayMs: Long) {
        clearScheduledClear()

        clearRunnable = Runnable {
            if (hasVettIDClipData()) {
                clearClipboard()
            }
        }
        handler.postDelayed(clearRunnable!!, delayMs)
    }

    private fun clearScheduledClear() {
        clearRunnable?.let { handler.removeCallbacks(it) }
        clearRunnable = null
    }

    /**
     * Call when app goes to background to clear sensitive clipboard data
     */
    fun onAppBackground() {
        if (hasVettIDClipData()) {
            clearClipboard()
        }
    }
}
