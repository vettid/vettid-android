package com.vettid.core.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * A screen that must not be left by accident while [guarded] (an open connection request, 2026-11-01: an edge swipe as the
 * notification shade opened was taken as Back and left the safety code): system Back and the returned action (for the top-bar
 * arrow) ask first with [title] / [text]; [leaveLabel] calls [onLeave], [stayLabel] keeps the screen. Unguarded, both leave at
 * once. Nothing else changes: leaving does not cancel or decline anything.
 */
@Composable
fun rememberConfirmedLeave(
    guarded: Boolean,
    title: String,
    text: String,
    leaveLabel: String,
    stayLabel: String,
    onLeave: () -> Unit,
): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = guarded) { asking = true }
    if (asking && guarded) {
        ConfirmDialog(
            title = title,
            text = text,
            confirmLabel = leaveLabel,
            onConfirm = {
                asking = false
                onLeave()
            },
            onDismiss = { asking = false },
            dismissLabel = stayLabel,
            modifier = Modifier.testTag("confirm_leave"),
        )
    }
    return { if (guarded) asking = true else onLeave() }
}
