package com.vettid.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

/**
 * Empty state (Proton): one illustration, a title and one line, centred.
 * The illustration is the destination's icon on a raised tile with sparkles.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyIllustration(icon)
        Spacer(Modifier.height(Spacing.xxl))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Optical centring: Proton sits the block slightly below the middle.
        Spacer(Modifier.height(Spacing.xxl))
    }
}

@Composable
private fun EmptyIllustration(icon: ImageVector) {
    val sparkle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Box(Modifier.size(160.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawSparkle(Offset(size.width * 0.14f, size.height * 0.16f), 14.dp.toPx(), sparkle)
            drawSparkle(Offset(size.width * 0.84f, size.height * 0.10f), 8.dp.toPx(), sparkle)
            drawSparkle(Offset(size.width * 0.90f, size.height * 0.34f), 7.dp.toPx(), sparkle)
        }
        Box(
            modifier = Modifier
                .padding(top = Spacing.l)
                .size(104.dp)
                .clip(VettIdShape.tile(104))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

/** Four-pointed star. */
private fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val waist = radius * SPARKLE_WAIST
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x + waist, center.y - waist, center.x + radius, center.y)
        quadraticTo(center.x + waist, center.y + waist, center.x, center.y + radius)
        quadraticTo(center.x - waist, center.y + waist, center.x - radius, center.y)
        quadraticTo(center.x - waist, center.y - waist, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}

private const val SPARKLE_WAIST = 0.22f
