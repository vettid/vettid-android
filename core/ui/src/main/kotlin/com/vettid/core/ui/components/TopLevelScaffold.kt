package com.vettid.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** What the app shell gives every top-level screen: drawer and account sheet hooks. */
@Immutable
data class ShellChrome(
    val accountName: String,
    val onMenuClick: () -> Unit,
    val onAvatarClick: () -> Unit,
    /** The member's own profile photo (§10.8), shown in the avatar tile when set. */
    val accountPhoto: androidx.compose.ui.graphics.ImageBitmap? = null,
)

/**
 * Top-level (drawer) screen: [VettIdTopAppBar] over [content], with an overlay
 * slot for [BottomFloatingControls]. With [search], the bar has a search icon next to the avatar and becomes the
 * search field while it is open ([TopBarSearch]).
 */
@Composable
fun TopLevelScaffold(
    title: String,
    chrome: ShellChrome,
    modifier: Modifier = Modifier,
    onSearchClick: (() -> Unit)? = null,
    search: TopBarSearch? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    ScreenSurface(modifier, MaterialTheme.colorScheme.background, overlay) {
        if (search != null && search.active) {
            SearchTopBar(search)
        } else {
            VettIdTopAppBar(
                title = title,
                onMenuClick = chrome.onMenuClick,
                accountName = chrome.accountName,
                onAvatarClick = chrome.onAvatarClick,
                onSearchClick = search?.let { s -> s::open } ?: onSearchClick,
                accountPhoto = chrome.accountPhoto,
                searchLabel = search?.label,
                actions = actions,
            )
        }
        Box(Modifier.weight(1f).fillMaxSize(), content = content)
    }
}

/**
 * Secondary screen: back arrow, then [content] (which starts with a
 * [LargeTitle] or [CenteredTitle]), with an overlay slot for [FloatingPillActionBar].
 */
@Composable
fun DetailScaffold(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.background,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    ScreenSurface(modifier, background, overlay) {
        VettIdBackTopBar(onBackClick = onBackClick, actions = actions)
        Box(Modifier.weight(1f).fillMaxSize(), content = content)
    }
}

/** A Surface (so content colour follows the theme) holding a column and an overlay. */
@Composable
private fun ScreenSurface(
    modifier: Modifier,
    color: Color,
    overlay: @Composable BoxScope.() -> Unit,
    column: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = color) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize(), content = column)
            overlay()
        }
    }
}
