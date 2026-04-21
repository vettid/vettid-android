package com.vettid.app.features.personaldata

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Renders a peer's published profile using the same BusinessCardView
 * the user sees for their own public-profile preview. Callers pass a
 * PublishedProfileData built by peerProfileToPublishedProfileData() so
 * every peer-profile surface (scanner preview, connected-peer detail)
 * gets the hero avatar + clickable contact card + categorized fields +
 * public keys layout for free — and inherits fixes like the
 * family-phone / emergency-phone hero exclusion.
 */
@Composable
fun PeerProfileView(
    profile: PublishedProfileData,
    modifier: Modifier = Modifier,
) {
    var qrItem by remember { mutableStateOf<PersonalDataItem?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
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
