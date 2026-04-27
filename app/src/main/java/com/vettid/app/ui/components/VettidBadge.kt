package com.vettid.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.app.ui.theme.VettidBlack
import com.vettid.app.ui.theme.VettidGold

/**
 * Theme-independent notification badge styled with the VettID gold
 * accent so it pops in both light and dark themes. The default
 * Material3 Badge uses `colorScheme.error` which renders as dark red
 * in light mode and washes out against dark icons. Gold + black gives
 * high contrast in either theme.
 *
 * Pass `count = null` to render a dot-only badge (no number).
 */
@Composable
fun VettidBadgedIcon(
    count: Int?,
    modifier: Modifier = Modifier,
    pulse: Boolean = true,
    content: @Composable () -> Unit,
) {
    val show = count == null || count > 0
    if (!show) {
        BadgedBox(modifier = modifier, badge = {}) { content() }
        return
    }

    val scale = if (pulse) rememberPulseScale() else 1f

    BadgedBox(
        modifier = modifier,
        badge = {
            if (count == null) {
                Badge(
                    containerColor = VettidGold,
                    contentColor = VettidBlack,
                    modifier = Modifier
                        .size(10.dp)
                        .scale(scale)
                        .clip(CircleShape),
                )
            } else {
                Badge(
                    containerColor = VettidGold,
                    contentColor = VettidBlack,
                    modifier = Modifier.scale(scale),
                ) {
                    Text(
                        text = if (count > 99) "99+" else count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = VettidBlack,
                        ),
                    )
                }
            }
        },
    ) {
        content()
    }
}

/**
 * Single-character "alert" badge — same gold styling, used for filter
 * indicators ("!") where there's no count.
 */
@Composable
fun VettidAlertBadge(
    label: String = "!",
    pulse: Boolean = true,
) {
    val scale = if (pulse) rememberPulseScale() else 1f
    Badge(
        containerColor = VettidGold,
        contentColor = VettidBlack,
        modifier = Modifier.scale(scale),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = VettidBlack,
            ),
        )
    }
}

@Composable
private fun rememberPulseScale(): Float {
    val transition = rememberInfiniteTransition(label = "badgePulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "badgePulseScale",
    )
    return scale
}
