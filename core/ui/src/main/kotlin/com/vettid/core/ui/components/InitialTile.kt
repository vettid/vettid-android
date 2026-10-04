package com.vettid.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vettid.core.ui.theme.VettIdShape
import com.vettid.core.ui.theme.VettIdTheme

/** First letter of the first word, upper-cased; "?" for blank names. */
fun initialOf(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/** Visual role of an [InitialTile]: a connection, a favourite connection, or the member. */
enum class TileStyle { Connection, Favorite, Self }

/**
 * Rounded-square tile with an initial (Proton's avatar tile). Decorative by
 * default: the row or button around it carries the accessible label.
 */
@Composable
fun InitialTile(
    name: String,
    modifier: Modifier = Modifier,
    size: Int = 40,
    style: TileStyle = TileStyle.Connection,
) {
    val colors = VettIdTheme.colors
    val (bg: Color, fg: Color) = when (style) {
        TileStyle.Connection -> colors.tile to colors.onTile
        TileStyle.Favorite -> colors.favoriteTile to colors.onFavoriteTile
        TileStyle.Self -> colors.avatar to colors.onAvatar
    }
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(VettIdShape.tile(size))
            .background(bg)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialOf(name),
            color = fg,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size * 0.45f).sp,
        )
    }
}
