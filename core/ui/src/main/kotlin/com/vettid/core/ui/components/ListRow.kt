package com.vettid.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.TagColor

/** Trailing icon action on a [VettIdListRow] (Proton's star). */
data class RowAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val active: Boolean = false,
)

/**
 * List row (Proton mailbox): initial tile, name and preview lines, date on the
 * first line's end and an optional trailing action under it. [emphasized] marks
 * unread rows (bold, full-contrast text); [tileStyle] marks favourites; [tileColors] colours the tile (a tag's
 * colour). [below] goes under the preview line (an item's tags, [TagChipLine]).
 */
@Composable
fun VettIdListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    meta: String? = null,
    tileName: String = title,
    tileStyle: TileStyle = TileStyle.Connection,
    emphasized: Boolean = false,
    action: RowAction? = null,
    onClick: (() -> Unit)? = null,
    tilePhoto: ImageBitmap? = null,
    tileIcon: ImageVector? = null,
    tileColors: TagColor? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val lineColor = if (emphasized) colors.onSurface else colors.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 72.dp)
            .padding(start = Spacing.gutter, end = Spacing.xs, top = Spacing.m, bottom = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialTile(name = tileName, size = 40, style = tileStyle, photo = tilePhoto, icon = tileIcon, colors = tileColors)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                    color = lineColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (meta != null) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                        color = lineColor,
                        modifier = Modifier.padding(start = Spacing.s, end = Spacing.m),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = lineColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (action != null) {
                    IconButton(onClick = action.onClick, modifier = Modifier.size(Spacing.touchTarget)) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.contentDescription,
                            tint = if (action.active) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            if (below != null) {
                Spacer(Modifier.height(Spacing.xs))
                below()
            }
        }
    }
}
