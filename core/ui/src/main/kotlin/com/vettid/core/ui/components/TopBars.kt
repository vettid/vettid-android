package com.vettid.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

/**
 * Main top bar (Proton inbox): menu, large start-aligned title, optional search, the Notifications [bell]
 * (ANDROID-PLAN 0.1.23) and the member's avatar tile which opens the account sheet.
 */
@Composable
fun VettIdTopAppBar(
    title: String,
    onMenuClick: () -> Unit,
    accountName: String,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchClick: (() -> Unit)? = null,
    accountPhoto: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** The search icon's description (default "Search"). */
    searchLabel: String? = null,
    /** Icon actions before the avatar (History's ⋯ menu). */
    actions: @Composable RowScope.() -> Unit = {},
    bell: NotificationBell? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(72.dp)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.core_ui_cd_open_menu))
        }
        Spacer(Modifier.width(Spacing.s))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (onSearchClick != null) {
            IconButton(onClick = onSearchClick, modifier = Modifier.testTag("top_bar_search_open")) {
                Icon(Icons.Outlined.Search, contentDescription = searchLabel ?: stringResource(R.string.core_ui_cd_search))
            }
        }
        actions()
        if (bell != null) NotificationBellButton(bell)
        val avatarLabel = stringResource(R.string.core_ui_cd_account, accountName)
        // Not an IconButton: its circular clip would cut the tile's corners.
        Box(
            modifier = Modifier
                .padding(end = Spacing.s)
                .size(Spacing.touchTarget)
                .clip(VettIdShape.tile(Spacing.touchTarget.value.toInt()))
                .clickable(role = Role.Button, onClick = onAvatarClick)
                .semantics { this.contentDescription = avatarLabel },
            contentAlignment = Alignment.Center,
        ) {
            InitialTile(name = accountName, size = 36, style = TileStyle.Self, photo = accountPhoto)
        }
    }
}

/**
 * Back-arrow bar for detail and settings screens. The title (if any) goes in the
 * content below: centred for detail screens ([CenteredTitle]), large and
 * start-aligned for settings ([LargeTitle]).
 */
@Composable
fun VettIdBackTopBar(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.core_ui_cd_back))
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/**
 * A text action at the end of a top bar ("Save item" in the item editor): gold text, a progress ring in its place
 * while [busy] (then not clickable).
 */
@Composable
fun TopBarTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    TextButton(onClick = onClick, enabled = enabled && !busy, modifier = modifier) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(18.dp).testTag("top_bar_action_busy"),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(Spacing.s))
        }
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/** Centred screen title of a detail screen (Proton message detail). */
@Composable
fun CenteredTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.l)
            .semantics { heading() },
    )
}

/** Large start-aligned title (Proton settings). */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(start = Spacing.gutter + Spacing.xs, end = Spacing.gutter, top = Spacing.s, bottom = Spacing.l)) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
}

