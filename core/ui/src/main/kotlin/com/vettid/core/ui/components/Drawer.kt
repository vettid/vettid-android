package com.vettid.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing

/** One drawer destination. [badge] > 0 shows a gold count pill. */
@Immutable
data class DrawerItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val badge: Int = 0,
)

/**
 * Navigation drawer sheet (Proton): logo header, then groups of destinations
 * separated by hairlines, and the app version at the bottom.
 */
@Composable
fun VettIdDrawerSheet(
    sections: List<List<DrawerItem>>,
    selectedKey: String?,
    onItemClick: (DrawerItem) -> Unit,
    versionLabel: String,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier.width(312.dp),
        drawerShape = RectangleShape,
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        windowInsets = WindowInsets(0),
    ) {
        Column(Modifier.fillMaxHeight()) {
            DrawerHeader()
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                sections.forEachIndexed { index, section ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    section.forEach { item ->
                        DrawerRow(item = item, selected = item.key == selectedKey, onClick = { onItemClick(item) })
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = versionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
            )
        }
    }
}

@Composable
private fun DrawerHeader() {
    Row(
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.s, bottom = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_rook),
            contentDescription = null,
            modifier = Modifier.size(width = 21.dp, height = 28.dp),
        )
        Spacer(Modifier.width(Spacing.m))
        Text(
            text = stringResource(R.string.core_ui_brand_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DrawerRow(item: DrawerItem, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = if (selected) colors.surfaceContainer else colors.surfaceContainerLow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(background)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.xl))
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onSurface else colors.onSurface.copy(alpha = 0.87f),
            modifier = Modifier.weight(1f),
        )
        if (item.badge > 0) CountBadge(count = item.badge)
    }
}
