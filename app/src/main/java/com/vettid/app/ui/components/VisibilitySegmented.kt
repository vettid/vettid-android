package com.vettid.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vettid.app.ui.theme.VettidBlack
import com.vettid.app.ui.theme.VettidGold

/**
 * Visibility selector for a data field or secret. States are mutually
 * exclusive — picking one unsets the others. Used on Personal Data
 * rows, Minor Secret rows, and (with allowUseOnly=true) Critical
 * Secret rows.
 *
 * - PROFILE     — value appears on the user's public calling card.
 *                 Highest visibility tier.
 * - CATALOG     — peers see metadata and can request the value
 *                 through the grant flow.
 * - USE_ONLY    — critical-secret-only: peers see metadata and can
 *                 ask the owner to USE the secret on their behalf
 *                 (sign / decrypt / derive / auth). Value never
 *                 leaves the vault. Not selectable for minor data.
 * - PRIVATE     — invisible to peers. Local-only.
 */
enum class FieldVisibility { PROFILE, CATALOG, USE_ONLY, PRIVATE }

@Composable
fun VisibilitySegmented(
    visibility: FieldVisibility,
    onVisibilityChange: (FieldVisibility) -> Unit,
    modifier: Modifier = Modifier,
    allowProfile: Boolean = true,
    allowUseOnly: Boolean = false,
) {
    val divider = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(
                width = 1.dp,
                color = divider,
                shape = RoundedCornerShape(8.dp),
            ),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (allowProfile) {
            SegmentCell(
                icon = Icons.Default.Star,
                contentDescription = "Profile — on your calling card",
                selected = visibility == FieldVisibility.PROFILE,
                selectedTint = VettidGold,
                onClick = { onVisibilityChange(FieldVisibility.PROFILE) },
            )
            CellDivider(divider)
        }
        SegmentCell(
            icon = Icons.Default.LocalOffer,
            contentDescription = "Catalog — peers see metadata, can request value",
            selected = visibility == FieldVisibility.CATALOG,
            selectedTint = MaterialTheme.colorScheme.primary,
            onClick = { onVisibilityChange(FieldVisibility.CATALOG) },
        )
        if (allowUseOnly) {
            CellDivider(divider)
            SegmentCell(
                icon = Icons.Default.HowToReg,
                contentDescription = "Use-only — peers can ask you to use this secret on their behalf; value never leaves your vault",
                selected = visibility == FieldVisibility.USE_ONLY,
                selectedTint = MaterialTheme.colorScheme.tertiary,
                onClick = { onVisibilityChange(FieldVisibility.USE_ONLY) },
            )
        }
        CellDivider(divider)
        SegmentCell(
            icon = Icons.Default.Block,
            contentDescription = "Private — hidden from peers",
            selected = visibility == FieldVisibility.PRIVATE,
            selectedTint = MaterialTheme.colorScheme.error,
            onClick = { onVisibilityChange(FieldVisibility.PRIVATE) },
        )
    }
}

@Composable
private fun CellDivider(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .width(1.dp)
            .height(30.dp)
            .background(color),
    )
}

@Composable
private fun SegmentCell(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    selectedTint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val bg = if (selected) selectedTint.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent
    val tint = if (selected) {
        // Selected pill uses the strong tint so the icon pops.
        // For gold use black for legibility; for primary/error use
        // the colour itself but at full opacity.
        if (selectedTint == VettidGold) VettidBlack else selectedTint
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(width = 36.dp, height = 30.dp)
            .background(bg)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp).padding(0.dp),
            tint = tint,
        )
    }
}
