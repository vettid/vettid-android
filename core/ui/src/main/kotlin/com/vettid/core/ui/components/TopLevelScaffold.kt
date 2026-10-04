package com.vettid.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/** What the app shell gives every top-level screen: drawer and account sheet hooks. */
@Immutable
data class ShellChrome(
    val accountName: String,
    val onMenuClick: () -> Unit,
    val onAvatarClick: () -> Unit,
)

/**
 * Top-level (drawer) screen: [VettIdTopAppBar] over [content], with an overlay
 * slot for [BottomFloatingControls].
 */
@Composable
fun TopLevelScaffold(
    title: String,
    chrome: ShellChrome,
    modifier: Modifier = Modifier,
    onSearchClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            VettIdTopAppBar(
                title = title,
                onMenuClick = chrome.onMenuClick,
                accountName = chrome.accountName,
                onAvatarClick = chrome.onAvatarClick,
                onSearchClick = onSearchClick,
            )
            Box(Modifier.weight(1f).fillMaxSize(), content = content)
        }
        overlay()
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
    background: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.background,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(background),
    ) {
        Column(Modifier.fillMaxSize()) {
            VettIdBackTopBar(onBackClick = onBackClick, actions = actions)
            Box(Modifier.weight(1f).fillMaxSize(), content = content)
        }
        overlay()
    }
}
