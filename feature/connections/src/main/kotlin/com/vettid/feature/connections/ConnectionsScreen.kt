package com.vettid.feature.connections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.ui.components.BottomFloatingControls
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.components.VettIdFab
import kotlinx.serialization.Serializable

/** Type-safe navigation route of the Connections screen. */
@Serializable
data object ConnectionsRoute

/** Registers the Connections destination. */
fun NavGraphBuilder.connectionsDestination(chrome: ShellChrome) {
    composable<ConnectionsRoute> { ConnectionsScreen(chrome = chrome) }
}

/**
 * Connections (ANDROID-PLAN §4). Phase A0 placeholder: the empty state only; the
 * list, its ViewModel and data arrive with the feature's phase.
 */
@Composable
fun ConnectionsScreen(
    chrome: ShellChrome,
    modifier: Modifier = Modifier,
    onAddClick: () -> Unit = {},
) {
    TopLevelScaffold(
        title = stringResource(R.string.connections_title),
        chrome = chrome,
        modifier = modifier,
        overlay = {
            BottomFloatingControls(
                end = {
                    VettIdFab(
                        icon = Icons.Outlined.PersonAdd,
                        contentDescription = stringResource(R.string.connections_add),
                        onClick = onAddClick,
                    )
                },
            )
        },
    ) {
        EmptyState(
            icon = Icons.Outlined.People,
            title = stringResource(R.string.connections_empty_title),
            body = stringResource(R.string.connections_empty_body),
        )
    }
}
