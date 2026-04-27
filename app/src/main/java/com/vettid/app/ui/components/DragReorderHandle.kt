package com.vettid.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lightweight drag-to-reorder handle. Replaces the paired up/down
 * arrow buttons on data and secret rows. The user long-presses the
 * handle, then drags vertically — every time the cumulative finger
 * movement crosses ~60% of the configured row height, the underlying
 * `onMoveUp` / `onMoveDown` callbacks fire, walking the item through
 * its neighbors. The list re-lays out under the finger as each step
 * resolves.
 *
 * Why hand-rolled vs a library: keeps us free of `sh.calvin.reorderable`
 * and the mental overhead of LazyListState plumbing for two short
 * within-category lists. The trade-off is no smooth translation
 * preview of the dragged row — the row "steps" to each new slot as
 * the threshold is crossed.
 *
 * @param rowHeightDp Approx height of a single row in this list.
 *        Used to compute the swap threshold; precise within ~10dp
 *        is fine — error just makes the reorder slightly slower or
 *        faster than the user's finger.
 */
@Composable
fun DragReorderHandle(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
    rowHeightDp: Dp = 60.dp,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Re-read the latest values from inside the long-lived gesture
    // coroutine without re-keying pointerInput (which would cancel
    // an in-progress drag whenever a row's neighbors change after
    // a successful swap).
    val canMoveUpState by rememberUpdatedState(canMoveUp)
    val canMoveDownState by rememberUpdatedState(canMoveDown)
    val onMoveUpState by rememberUpdatedState(onMoveUp)
    val onMoveDownState by rememberUpdatedState(onMoveDown)

    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(40.dp)
            .pointerInput(Unit) {
                val rowHeightPx = with(density) { rowHeightDp.toPx() }
                val threshold = rowHeightPx * 0.6f
                var dragOffset = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        isDragging = true
                        dragOffset = 0f
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { _, drag ->
                        dragOffset += drag.y
                        // Down: drag the row toward the bottom of
                        // the list, one slot at a time. The
                        // remainder after each move stays in
                        // dragOffset so successive thresholds keep
                        // tracking finger position.
                        while (dragOffset >= threshold && canMoveDownState) {
                            onMoveDownState()
                            dragOffset -= rowHeightPx
                        }
                        while (dragOffset <= -threshold && canMoveUpState) {
                            onMoveUpState()
                            dragOffset += rowHeightPx
                        }
                        // Hit the floor or ceiling — clamp so the
                        // user can't accumulate a huge offset that
                        // would pop loose later.
                        if (!canMoveDownState && dragOffset > threshold) {
                            dragOffset = threshold
                        }
                        if (!canMoveUpState && dragOffset < -threshold) {
                            dragOffset = -threshold
                        }
                    },
                    onDragEnd = { isDragging = false; dragOffset = 0f },
                    onDragCancel = { isDragging = false; dragOffset = 0f },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Drag to reorder",
            modifier = Modifier.size(22.dp),
            tint = if (isDragging) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
        )
    }
}
