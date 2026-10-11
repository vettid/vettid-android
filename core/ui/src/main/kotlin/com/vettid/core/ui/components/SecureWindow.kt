package com.vettid.core.ui.components

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * Keeps the window out of screenshots, screen recordings and the recent-apps preview (`FLAG_SECURE`) while this is
 * in the composition, and no longer: the flag is cleared when the last screen that asked for it leaves (owner
 * decision 2026-10-11; first used by the typed recovery code, VAULT-MESSAGING §11.11.10).
 */
@Composable
fun SecureWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.let(SecureWindows::acquire)
        onDispose { window?.let(SecureWindows::release) }
    }
}

/** Counts the screens that hold a window secure (main thread only, as all composition is). */
internal object SecureWindows {
    private val holds = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        val n = holds[window] ?: 0
        if (n == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        holds[window] = n + 1
    }

    fun release(window: Window) {
        val n = holds[window] ?: return
        if (n > 1) {
            holds[window] = n - 1
        } else {
            holds.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
