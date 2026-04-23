package com.vettid.app.features.personaldata

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vettid.app.core.nats.PeerHandlerInfo
import com.vettid.app.core.nats.PeerPublicSecretMetadata

/**
 * Renders a peer's published profile using the same BusinessCardView
 * the user sees for their own public-profile preview. Callers pass a
 * PublishedProfileData built by peerProfileToPublishedProfileData() so
 * every peer-profile surface (scanner preview, connected-peer detail)
 * gets the hero avatar + clickable contact card + categorized fields +
 * public keys layout for free — and inherits fixes like the
 * family-phone / emergency-phone hero exclusion.
 *
 * Public-metadata badges (Data / Secrets / Handlers) render above
 * the card and match the layout of the user's own preview. Pass the
 * peer's handler catalog and public-secret metadata from their
 * PeerProfileData to populate the Secrets and Handlers dialogs.
 */
@Composable
fun PeerProfileView(
    profile: PublishedProfileData,
    modifier: Modifier = Modifier,
    peerHandlers: List<PeerHandlerInfo>? = null,
    peerPublicSecrets: List<PeerPublicSecretMetadata>? = null,
) {
    var qrItem by remember { mutableStateOf<PersonalDataItem?>(null) }

    val publicDataItems = remember(profile) {
        // What the peer has published as regular personal-data
        // fields. Wallets and identity keys live in the same items
        // list under different types, so filter on type to keep
        // the badge's "Data" count aligned with what the user
        // expects in the peer's categorized sections.
        profile.items.filter { it.type == DataType.PUBLIC && it.isInPublicProfile }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        PublishedProfileBadges(
            dataItems = publicDataItems,
            publicSecrets = peerPublicSecrets.orEmpty(),
            handlers = peerHandlers.orEmpty(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        BusinessCardView(
            profile = profile,
            isReadOnly = true,
            onShowQR = { qrItem = it },
        )
    }

    qrItem?.let { item ->
        PublicKeyQRDialog(
            item = item,
            onDismiss = { qrItem = null },
        )
    }
}
