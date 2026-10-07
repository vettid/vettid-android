package com.vettid.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import com.vettid.core.ui.theme.VettIdTheme

/** Section header above a settings group (Proton "Preferences"). */
@Composable
fun SettingsSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(start = Spacing.gutter + Spacing.xs, end = Spacing.gutter, top = Spacing.xl, bottom = Spacing.m)
            .semantics { heading() },
    )
}

/**
 * Rounded card grouping settings rows, separated by hairlines in the screen
 * colour (Proton). Rows are emitted by [content]; call [SettingsDivider] between them
 * or use [SettingsGroupOf].
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = VettIdShape.card,
        color = VettIdTheme.colors.card,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs + Spacing.xxs),
    ) {
        Column(content = content)
    }
}

/** Convenience: a group whose rows are separated automatically. */
@Composable
fun SettingsGroupOf(
    rows: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(modifier) {
        rows.forEachIndexed { i, row ->
            if (i > 0) SettingsDivider()
            row()
        }
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(thickness = 2.dp, color = VettIdTheme.colors.groupedBackground)
}

/** Settings row: icon, label (+ optional supporting text and tag), chevron. */
@Composable
fun SettingsRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    supporting: String? = null,
    tag: String? = null,
    showChevron: Boolean = true,
) {
    SettingsRowLayout(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        leading = icon?.let { { RowIcon(it, iconTint) } },
        label = label,
        supporting = supporting,
        tag = tag,
        trailing = if (showChevron) {
            {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
    )
}

/** Settings row with a switch; the whole row toggles. */
@Composable
fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
) {
    SettingsRowLayout(
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        leading = icon?.let { { RowIcon(it, Color.Unspecified) } },
        label = label,
        supporting = supporting,
        tag = null,
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/** Account row at the top of Settings: avatar tile, name, address, chevron. */
@Composable
fun SettingsAccountRow(
    name: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    photo: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    SettingsRowLayout(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        leading = { InitialTile(name = name, size = 40, style = TileStyle.Self, photo = photo) },
        label = name,
        supporting = detail,
        tag = null,
        trailing = {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        minHeight = 88,
    )
}

@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
        modifier = Modifier.size(24.dp),
    )
}

@Composable
private fun SettingsRowLayout(
    modifier: Modifier,
    leading: (@Composable () -> Unit)?,
    label: String,
    supporting: String?,
    tag: String?,
    trailing: (@Composable () -> Unit)?,
    minHeight: Int = 72,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .padding(horizontal = Spacing.xl, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Spacing.xl))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tag != null) {
                    Spacer(Modifier.width(Spacing.s))
                    TagLabel(tag)
                }
            }
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.m))
            trailing()
        }
    }
}

/** A read-only settings row: label and value (status screens). Not clickable; read as one item. */
@Composable
fun SettingsInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    SettingsRowLayout(
        modifier = modifier.semantics(mergeDescendants = true) {},
        leading = icon?.let { { RowIcon(it, Color.Unspecified) } },
        label = label,
        supporting = value,
        tag = null,
        trailing = null,
    )
}
