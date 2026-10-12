package com.vettid.core.ui.components

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * Keeps the window out of screenshots, screen recordings and the recent-apps preview (`FLAG_SECURE`) while this is
 * in the composition, whatever the app-wide setting says ([protectFromCapture]): the hold is released when the last
 * screen that asked for it leaves (owner decision 2026-10-11; first used by the typed recovery code, VAULT-MESSAGING
 * §11.11.10).
 */
@Composable
fun SecureWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.let(SecureWindows::acquire)
        onDispose { window?.let(SecureWindows::release) }
    }
}

/**
 * The app-wide protection of this window (owner decision 2026-10-11, A6 audit S1): with [protect], `FLAG_SECURE` is
 * set, so screenshots, screen recording and the recent-apps preview show nothing of the app. Without it the flag is
 * still set while a screen holds the window secure ([SecureWindow]); the two never clear each other's protection.
 * Dialogs, sheets and menus are windows of their own that inherit the flag (Compose's `SecureFlagPolicy.Inherit`).
 */
fun Window.protectFromCapture(protect: Boolean) = SecureWindows.protect(this, protect)

/**
 * Why a window is secure: the app-wide protection, and the screens that hold it secure (counted). `FLAG_SECURE` is
 * set while either says so. Main thread only, as all composition is.
 */
internal object SecureWindows {
    private val holds = WeakHashMap<Window, Int>()
    private val protected = WeakHashMap<Window, Boolean>()

    fun protect(window: Window, protect: Boolean) {
        if (protect) protected[window] = true else protected.remove(window)
        apply(window)
    }

    fun acquire(window: Window) {
        holds[window] = (holds[window] ?: 0) + 1
        apply(window)
    }

    fun release(window: Window) {
        val n = holds[window] ?: return
        if (n > 1) holds[window] = n - 1 else holds.remove(window)
        apply(window)
    }

    private fun apply(window: Window) {
        val secure = protected[window] == true || (holds[window] ?: 0) > 0
        val set = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (secure && !set) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!secure && set) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
