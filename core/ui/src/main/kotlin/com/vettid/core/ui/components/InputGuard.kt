package com.vettid.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the screen in front holds input the member has typed and not sent (an editor with changes, a draft, a form with
 * a PIN typed). The shell reads it for the daily owner check (VAULT-MESSAGING §3.6.5): only where input is in progress
 * does the check wait (with a time limit); everywhere else it comes at once. A screen reports with [HoldsInput]; a
 * [SecretField] with text reports by itself. Each holder counts once, and leaving the screen releases it.
 */
@Stable
class InputGuard {
    private var holders by mutableIntStateOf(0)

    /** True while at least one composed screen reports unsent input. */
    val active: Boolean get() = holders > 0

    internal fun acquire() { holders += 1 }

    internal fun release() { holders = (holders - 1).coerceAtLeast(0) }
}

/** The shell's guard; null outside the shell (reporting then does nothing). */
val LocalInputGuard = staticCompositionLocalOf<InputGuard?> { null }

/** Reports, while [active], that this screen holds input not yet sent. */
@Composable
fun HoldsInput(active: Boolean) {
    val guard = LocalInputGuard.current ?: return
    DisposableEffect(guard, active) {
        if (active) guard.acquire()
        onDispose { if (active) guard.release() }
    }
}
