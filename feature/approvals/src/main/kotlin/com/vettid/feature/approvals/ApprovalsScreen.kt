package com.vettid.feature.approvals

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import kotlinx.serialization.Serializable

/** Type-safe navigation route of the Approvals screen. */
@Serializable
data object ApprovalsRoute

/** Registers the Approvals destination. */
fun NavGraphBuilder.approvalsDestination(chrome: ShellChrome) {
    composable<ApprovalsRoute> { ApprovalsScreen(chrome = chrome) }
}

/**
 * Approvals (ANDROID-PLAN §4). Phase A0 placeholder: the empty state only; the
 * list, its ViewModel and data arrive with the feature's phase.
 */
@Composable
fun ApprovalsScreen(
    chrome: ShellChrome,
    modifier: Modifier = Modifier,
) {
    TopLevelScaffold(
        title = stringResource(R.string.approvals_title),
        chrome = chrome,
        modifier = modifier,
    ) {
        EmptyState(
            icon = Icons.Outlined.TaskAlt,
            title = stringResource(R.string.approvals_empty_title),
            body = stringResource(R.string.approvals_empty_body),
        )
    }
}
