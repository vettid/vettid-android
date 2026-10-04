package com.vettid.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing

/**
 * One message in a conversation (ANDROID-PLAN §4): the member's own messages
 * on the right in a gold fill, the connection's on the left in a card colour.
 * [meta] is the time and, for own messages, the delivery state; the whole
 * bubble reads as one item ([accessibilityLabel]). [onLongClick] opens the
 * message's actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    text: String,
    outgoing: Boolean,
    meta: String,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = if (outgoing) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xxs),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = shape,
            color = if (outgoing) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = if (outgoing) colors.onPrimaryContainer else colors.onSurface,
            modifier = Modifier
                .widthIn(max = maxWidth * BUBBLE_WIDTH)
                .then(if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick) else Modifier)
                .clearAndSetSemantics { contentDescription = accessibilityLabel },
        ) {
            Column(Modifier.padding(horizontal = Spacing.m + Spacing.xxs, vertical = Spacing.s)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = (if (outgoing) colors.onPrimaryContainer else colors.onSurfaceVariant).copy(alpha = META_ALPHA),
                    modifier = Modifier.align(Alignment.End).padding(top = Spacing.xxs),
                )
            }
        }
    }
}

/** A day separator in a conversation. */
@Composable
fun DayDivider(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.m)
            .semantics { heading() },
    )
}

/**
 * The 6-digit safety code (VAULT-MESSAGING §6.3 SAS) in two groups of three,
 * large and monospaced; screen readers hear the digits one by one.
 */
@Composable
fun SafetyCode(code: String, modifier: Modifier = Modifier) {
    val digits = code.filter { it.isDigit() }
    val spoken = stringResource(R.string.core_ui_cd_safety_code, digits.toList().joinToString(" "))
    Row(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        digits.chunked(GROUP).forEach { g ->
            Text(
                g,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 40.sp,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private const val BUBBLE_WIDTH = 0.78f
private const val META_ALPHA = 0.8f
private const val GROUP = 3
