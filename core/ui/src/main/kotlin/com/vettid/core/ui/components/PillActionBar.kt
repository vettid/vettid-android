package com.vettid.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

@Immutable
data class PillAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

/** Floating pill of icon actions at the bottom of a detail screen (Proton). */
@Composable
fun BoxScope.FloatingPillActionBar(
    actions: List<PillAction>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = Spacing.l),
    ) {
        Surface(
            shape = VettIdShape.pill,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 3.dp,
        ) {
            Row(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
                actions.forEach { action ->
                    IconButton(onClick = action.onClick, modifier = Modifier.padding(horizontal = Spacing.xxs)) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.contentDescription,
                            tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
