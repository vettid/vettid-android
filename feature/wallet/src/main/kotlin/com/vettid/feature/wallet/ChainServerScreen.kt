package com.vettid.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.UrlProblem
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.theme.Spacing

/** What the Chain server screen asks of its host. */
data class ChainServerActions(
    val onBack: () -> Unit = {},
    val onText: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onReset: () -> Unit = {},
)

/** Wallet → ⋯ → Chain server (ANDROID-PLAN §4 "The chain source"): the setting, "Use my own server", "Reset to default", the note. */
@Composable
fun ChainServerScreen(state: ChainServerUiState, actions: ChainServerActions, modifier: Modifier = Modifier) {
    val offered = state.server != ChainServer.NotOffered
    FormScaffold(
        title = stringResource(R.string.wallet_menu_chain_server),
        body = state.server?.let { settingText(it) },
        primaryLabel = if (offered && state.server != null) {
            stringResource(if (state.checking) R.string.wallet_server_checking else R.string.wallet_server_save)
        } else {
            null
        },
        onPrimary = actions.onSave,
        primaryEnabled = state.text.isNotBlank(),
        busy = state.checking,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_chain_server"),
    ) {
        if (offered && state.server != null) {
            OwnServer(state, actions)
        }
        Text(
            stringResource(R.string.wallet_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("wallet_chain_server_note"),
        )
    }
}

@Composable
private fun settingText(server: ChainServer): String = when (server) {
    is ChainServer.Default -> stringResource(R.string.wallet_server_default, server.url.host)
    is ChainServer.Own -> stringResource(R.string.wallet_server_own, server.url.host)
    ChainServer.NotSet -> stringResource(R.string.wallet_server_not_set)
    ChainServer.Unreadable -> stringResource(R.string.wallet_server_unreadable)
    ChainServer.NotOffered -> stringResource(R.string.wallet_server_not_offered)
}

@Composable
private fun OwnServer(state: ChainServerUiState, actions: ChainServerActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.wallet_server_use_own), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.text,
            onValueChange = actions.onText,
            label = { Text(stringResource(R.string.wallet_server_url_label)) },
            singleLine = true,
            enabled = !state.checking,
            isError = state.problem != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("wallet_server_url"),
        )
        if (state.checking) {
            Text(stringResource(R.string.wallet_server_checking), modifier = Modifier.testTag("wallet_server_checking"))
        }
        state.problem?.let { p ->
            Text(problemText(p, state), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("wallet_server_problem"))
        }
        if (state.saved) Text(stringResource(R.string.wallet_server_saved), modifier = Modifier.testTag("wallet_server_saved"))
        if (state.server !is ChainServer.Default) {
            TextButton(onClick = actions.onReset, enabled = !state.checking, modifier = Modifier.testTag("wallet_server_reset")) {
                Text(stringResource(R.string.wallet_server_reset))
            }
        }
    }
}

@Composable
private fun problemText(p: ServerProblem, state: ChainServerUiState): String = when (p) {
    is ServerProblem.BadUrl -> stringResource(urlProblemRes(p.problem))
    ServerProblem.Failed -> stringResource(R.string.wallet_server_failed)
    ServerProblem.WrongNetwork -> stringResource(R.string.wallet_server_wrong_network, networkLabel(state.network).orEmpty())
    ServerProblem.NotWritten -> stringResource(R.string.wallet_server_not_written)
    ServerProblem.NotOffered -> stringResource(R.string.wallet_server_not_offered)
    ServerProblem.ResetFailed -> stringResource(R.string.wallet_server_reset_failed)
}

/** Each way a typed address is refused, in words. */
internal fun urlProblemRes(p: UrlProblem): Int = when (p) {
    UrlProblem.NOT_A_URL -> R.string.wallet_url_not_a_url
    UrlProblem.NOT_HTTPS -> R.string.wallet_url_not_https
    UrlProblem.CREDENTIALS -> R.string.wallet_url_credentials
    UrlProblem.QUERY_OR_FRAGMENT -> R.string.wallet_url_query
}
