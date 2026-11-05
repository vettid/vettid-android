package com.vettid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import com.vettid.app.R
import com.vettid.core.ui.components.UrgentBanner

/**
 * The lasting notice of a relay key the relay refused (VAULT-MESSAGING 0.84.0 §3.4 "What the member sees", owner
 * decision a, 2026-11-05): shown while the latest `vault.status` has `relay: "refused"`, gone when a later one has no
 * `relay` (`OwnerCheckRepository.relayRefused`). An [UrgentBanner], as the clone alarm and the pending start-over, whose
 * surface fills the status bar: the vault may not receive messages and only the member can fix it. Title and body are two
 * lines of its text. Not dismissible, because it lasts while the field is present; announced politely, because it comes
 * back after every unlock. The action is the existing `credential.rotate` flow ([onReplaceKeys]). [first] says whether it
 * is the topmost banner (which pads for the status bar).
 */
@Composable
fun RelayRefusedNotice(first: Boolean, onReplaceKeys: () -> Unit) {
    UrgentBanner(
        text = stringResource(R.string.relay_refused_title) + "\n" + stringResource(R.string.relay_refused_body),
        actionLabel = stringResource(R.string.relay_refused_action),
        onClick = onReplaceKeys,
        modifier = Modifier.testTag("relay_refused_notice"),
        statusBarPadding = first,
        liveRegion = LiveRegionMode.Polite,
    )
}
