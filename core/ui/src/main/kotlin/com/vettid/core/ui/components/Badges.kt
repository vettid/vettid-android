package com.vettid.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

private const val MAX_BADGE = 99

/** Gold count pill (drawer badge, pending approvals). */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val label = pluralStringResource(R.plurals.core_ui_badge_count, count, count)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
            .clip(VettIdShape.pill)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = Spacing.s)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Small tinted label next to a row title (Proton's "New"). */
@Composable
fun TagLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(VettIdShape.pill)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = Spacing.s + Spacing.xxs, vertical = Spacing.xxs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
