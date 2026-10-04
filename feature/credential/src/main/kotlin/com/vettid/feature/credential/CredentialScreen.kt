package com.vettid.feature.credential

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import kotlinx.serialization.Serializable

/** Type-safe navigation route of the Credential screen. */
@Serializable
data object CredentialRoute

/** Registers the Credential destination. */
fun NavGraphBuilder.credentialDestination(chrome: ShellChrome) {
    composable<CredentialRoute> { CredentialScreen(chrome = chrome) }
}

/**
 * Credential (ANDROID-PLAN §4). Phase A0 placeholder: the empty state only; the
 * list, its ViewModel and data arrive with the feature's phase.
 */
@Composable
fun CredentialScreen(
    chrome: ShellChrome,
    modifier: Modifier = Modifier,
) {
    TopLevelScaffold(
        title = stringResource(R.string.credential_title),
        chrome = chrome,
        modifier = modifier,
    ) {
        EmptyState(
            icon = Icons.Outlined.Shield,
            title = stringResource(R.string.credential_empty_title),
            body = stringResource(R.string.credential_empty_body),
        )
    }
}
