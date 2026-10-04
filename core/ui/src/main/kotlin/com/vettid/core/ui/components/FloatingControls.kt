package com.vettid.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

/**
 * Bottom overlay row for list screens: a filter chip at the start and the primary
 * action at the end (Proton: Unread + compose). Place inside the screen's Box.
 */
@Composable
fun BoxScope.BottomFloatingControls(
    modifier: Modifier = Modifier,
    start: @Composable () -> Unit = {},
    end: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.xl, vertical = Spacing.l),
    ) {
        Box(Modifier.align(Alignment.CenterStart)) { start() }
        Box(Modifier.align(Alignment.CenterEnd)) { end() }
    }
}

/** Floating toggle chip (Proton's "Unread"). Selected = gold fill. */
@Composable
fun FloatingFilterChip(
    label: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val state = stringResource(if (selected) R.string.core_ui_filter_on else R.string.core_ui_filter_off, label)
    Surface(
        shape = VettIdShape.pill,
        color = if (selected) colors.primaryContainer else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        shadowElevation = 2.dp,
        modifier = modifier
            .height(56.dp)
            .toggleable(value = selected, role = Role.Switch, onValueChange = onSelectedChange)
            .semantics { stateDescription = state },
    ) {
        Box(Modifier.padding(horizontal = Spacing.xl), contentAlignment = Alignment.Center) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Round floating action button (Proton compose): neutral surface, gold icon. */
@Composable
fun VettIdFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = VettIdShape.pill,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        shadowElevation = 2.dp,
        modifier = modifier.size(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}
