package com.vettid.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * The app lock over the app (ANDROID-PLAN D6). While [locked], the lock screen is shown INSTEAD of [content], not on
 * top of it: nothing of the app is composed, so no text field under it keeps focus or the keyboard's connection and
 * no keystroke typed into the phone's screen-lock prompt can reach it (owner report 2026-10-09). The content's
 * saveable state (navigation back stacks, drafts kept in `rememberSaveable`) is kept by a [rememberSaveableStateHolder]
 * and comes back on unlock; ViewModels live on in their back stack entries.
 *
 * [onAutoPrompt] asks for the prompt when the lock shows and on every return to the foreground while locked; the
 * activity makes it idempotent ([com.vettid.core.data.lock.UnlockPromptGate]), so a prompt already showing is never
 * doubled.
 */
@Composable
fun AppLockGate(
    locked: Boolean,
    onAutoPrompt: () -> Unit,
    lockScreen: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(locked) {
        if (locked) {
            focus.clearFocus(force = true)
            keyboard?.hide()
            onAutoPrompt()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (locked) onAutoPrompt() }
    if (locked) {
        // The lock layer takes the focus; keys the lock screen does not use stop here.
        val layer = remember { FocusRequester() }
        Box(
            Modifier
                .fillMaxSize()
                .testTag("app_lock_layer")
                .focusRequester(layer)
                .focusable()
                .onKeyEvent { true },
        ) { lockScreen() }
        LaunchedEffect(Unit) { runCatching { layer.requestFocus() } }
    } else {
        holder.SaveableStateProvider(CONTENT_KEY) { content() }
    }
}

private const val CONTENT_KEY = "app"
