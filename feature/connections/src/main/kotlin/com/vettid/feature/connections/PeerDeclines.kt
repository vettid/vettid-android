package com.vettid.feature.connections

import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vettid.core.data.social.PeerDecline
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind

/**
 * What the member is told when the other member declined (VAULT-MESSAGING 0.10.5, §6.4):
 * the accepter "<name> declined your connection request", the inviter "<name> declined
 * the connection".
 */
@StringRes
internal fun peerDeclineRes(outgoing: Boolean): Int =
    if (outgoing) R.string.connections_peer_declined_outgoing else R.string.connections_peer_declined_incoming

/** [peerDeclineRes] with the name the request showed, or "Someone" without one. */
@Composable
internal fun peerDeclineText(outgoing: Boolean, name: String?): String =
    stringResource(peerDeclineRes(outgoing), name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.connections_peer_declined_someone))

/** A peer's decline, shown until the member dismisses it (once). */
@Composable
internal fun PeerDeclineNotice(d: PeerDecline, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    NoticeCard(
        kind = NoticeKind.INFO,
        title = peerDeclineText(d.outgoing, d.name),
        body = stringResource(R.string.connections_peer_declined_body),
        modifier = modifier.testTag("peer_declined_${d.requestId}"),
        actions = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.connections_ok)) } },
    )
}
