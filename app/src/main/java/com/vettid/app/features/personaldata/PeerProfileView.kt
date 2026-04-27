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
    peerDataCatalog: List<com.vettid.app.core.nats.PeerDataCatalogEntry>? = null,
    peerSecretCatalog: List<PeerPublicSecretMetadata>? = null,
    isPeerOnline: Boolean = false,
) {
    var qrItem by remember { mutableStateOf<PersonalDataItem?>(null) }

    // Data badge shows the FULL data catalog (every personal-data
    // entry the peer hasn't marked private) — metadata only. Falls
    // back to the published-fields when the peer is on an older
    // enclave that doesn't emit data_catalog.
    val dataCatalog = remember(peerDataCatalog, profile) {
        peerDataCatalog?.map { d ->
            PublicMetadataItem(
                name = d.displayName.ifBlank { d.name },
                type = d.fieldType.ifBlank { "TEXT" },
                category = d.category.ifBlank { "Other" },
            )
        } ?: profile.items
            .filter { it.type == DataType.PUBLIC && it.isInPublicProfile }
            .map { item ->
                PublicMetadataItem(
                    name = item.name,
                    type = item.type.name,
                    category = item.category?.displayName ?: "Other",
                )
            }
    }

    // Secrets badge shows the secret catalog. Falls back to the
    // legacy public_secrets array for older enclaves.
    val secretCatalog = remember(peerSecretCatalog, peerPublicSecrets) {
        (peerSecretCatalog ?: peerPublicSecrets).orEmpty().map { s ->
            PublicMetadataItem(
                name = s.name,
                type = s.type.ifBlank { "SECRET" },
                category = s.category.ifBlank { "Other" },
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        PublishedProfileBadges(
            dataCatalog = dataCatalog,
            secretCatalog = secretCatalog,
            handlers = peerHandlers.orEmpty(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        BusinessCardView(
            profile = profile,
            isReadOnly = true,
            onShowQR = { qrItem = it },
            isOnline = isPeerOnline,
        )
    }

    qrItem?.let { item ->
        PublicKeyQRDialog(
            item = item,
            onDismiss = { qrItem = null },
        )
    }
}
