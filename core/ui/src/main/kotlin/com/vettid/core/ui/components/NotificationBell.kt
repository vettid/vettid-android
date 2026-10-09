package com.vettid.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

/**
 * The Notifications bell of the drawer screens' top bar (ANDROID-PLAN 0.1.23): [unread] items as a badge ("99+" above
 * 99, none at 0), gold, or red (the error colour) while an [urgent] item is unread.
 */
@Immutable
data class NotificationBell(val unread: Int, val urgent: Boolean, val onClick: () -> Unit)

private const val MAX_BELL = 99

/** The bell as an icon button with its badge; the label says the count ("Notifications, 3 unread"). */
@Composable
fun NotificationBellButton(bell: NotificationBell, modifier: Modifier = Modifier) {
    val label = when {
        bell.unread <= 0 -> stringResource(R.string.core_ui_notifications)
        bell.urgent -> pluralStringResource(R.plurals.core_ui_notifications_unread_urgent, bell.unread, bell.unread)
        else -> pluralStringResource(R.plurals.core_ui_notifications_unread, bell.unread, bell.unread)
    }
    IconButton(onClick = bell.onClick, modifier = modifier.testTag("top_bar_bell").semantics { contentDescription = label }) {
        Box {
            Icon(Icons.Outlined.Notifications, contentDescription = null)
            if (bell.unread > 0) BellBadge(bell.unread, bell.urgent, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-8).dp))
        }
    }
}

@Composable
private fun BellBadge(count: Int, urgent: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(VettIdShape.pill)
            .background(if (urgent) colors.error else colors.primaryContainer)
            .padding(horizontal = Spacing.xs)
            .testTag(if (urgent) "bell_badge_urgent" else "bell_badge")
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > MAX_BELL) "$MAX_BELL+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (urgent) colors.onError else colors.onPrimaryContainer,
        )
    }
}
