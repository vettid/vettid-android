package com.vettid.feature.items

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.ui.components.BottomFloatingControls
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.FloatingFilterChip
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.components.VettIdFab
import kotlinx.serialization.Serializable

/** Type-safe navigation route of the Items screen. */
@Serializable
data object ItemsRoute

/** Registers the Items destination. */
fun NavGraphBuilder.itemsDestination(chrome: ShellChrome) {
    composable<ItemsRoute> { ItemsScreen(chrome = chrome) }
}

/**
 * Items (ANDROID-PLAN §4). Phase A0 placeholder: the empty state only; the
 * list, its ViewModel and data arrive with the feature's phase.
 */
@Composable
fun ItemsScreen(
    chrome: ShellChrome,
    modifier: Modifier = Modifier,
    onAddClick: () -> Unit = {},
) {
    var filterOn by rememberSaveable { mutableStateOf(false) }
    TopLevelScaffold(
        title = stringResource(R.string.items_title),
        chrome = chrome,
        modifier = modifier,
        overlay = {
            BottomFloatingControls(
                start = {
                    FloatingFilterChip(
                        label = stringResource(R.string.items_filter),
                        selected = filterOn,
                        onSelectedChange = { filterOn = it },
                    )
                },
                end = {
                    VettIdFab(
                        icon = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.items_add),
                        onClick = onAddClick,
                    )
                },
            )
        },
    ) {
        EmptyState(
            icon = Icons.Outlined.Inventory2,
            title = stringResource(R.string.items_empty_title),
            body = stringResource(R.string.items_empty_body),
        )
    }
}
