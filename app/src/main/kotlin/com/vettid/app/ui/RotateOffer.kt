package com.vettid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import com.vettid.app.R
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.ui.components.InfoBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * (Release R, ANDROID-PLAN "Transfer") After a transfer, a PIN change or a password change the app offers to rotate the
 * vault's keys (`credential.rotate`); a completed recovery rotates by itself.
 */
@HiltViewModel
class RotateOfferViewModel @Inject constructor(private val vault: VaultRepository) : ViewModel() {
    val offered: StateFlow<Boolean> = vault.rotateOffered

    fun dismiss() = vault.dismissRotateOffer()
}

/** The offer, above the shell's screens: "Rotate now" opens the rotation, the close button puts it away. */
@Composable
fun RotateOfferBanner(onRotate: () -> Unit, onDismiss: () -> Unit, statusBarPadding: Boolean) {
    InfoBanner(
        stringResource(R.string.rotate_offer),
        Modifier.testTag("rotate_offer"),
        actionLabel = stringResource(R.string.rotate_offer_action),
        onAction = onRotate,
        statusBarPadding = statusBarPadding,
        onDismiss = onDismiss,
    )
}
