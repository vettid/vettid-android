package com.vettid.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
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

    private val wipers = mutableListOf<() -> Unit>()

    internal fun addWiper(w: () -> Unit) { wipers += w }

    internal fun removeWiper(w: () -> Unit) { wipers -= w }

    /**
     * The check is about to cover the shell (VAULT-MESSAGING §3.6.5, the zeroize rule): every secret typed and not sent (a PIN
     * or password, the words of a recovery phrase) is blanked where it lives, through the screen's own setter. Drafts that are
     * not secrets stay.
     */
    fun wipeSecrets() { wipers.toList().forEach { it() } }
}

/**
 * Registers [wipe] with the shell's guard while this is composed: it blanks a secret the screen holds (it calls the screen's
 * own setter with an empty value) when the owner check covers the shell. Dialogs and sheets are hidden when it covers, so
 * this runs first, while they still compose.
 */
@Composable
fun WipesOnCover(wipe: () -> Unit) {
    val guard = LocalInputGuard.current ?: return
    val latest by rememberUpdatedState(wipe)
    DisposableEffect(guard) {
        val w = { latest() }
        guard.addWiper(w)
        onDispose { guard.removeWiper(w) }
    }
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

/**
 * True while the daily owner check covers the shell (VAULT-MESSAGING §3.6.5). Dialogs and sheets are windows of their own,
 * drawn above anything the shell draws, so while this is true the wrappers in `ShellOverlays.kt` do not compose them; their
 * state lives in the screens' view models and comes back when the check has passed.
 */
val LocalCheckCovered = compositionLocalOf { false }
