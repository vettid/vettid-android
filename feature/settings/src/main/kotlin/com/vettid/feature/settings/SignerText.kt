package com.vettid.feature.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vettid.core.data.vault.SignerClass

/**
 * The member-facing texts of the signer binding (VAULT-MESSAGING 0.27.0 §11.7.1; the wording is ANDROID-PLAN 0.1.36
 * §7 "What members are told"). The vault names a class; the app supplies every word.
 */

/** The "Installed from" line of Settings → Vault status for a class. */
@StringRes
fun SignerClass.installedFromRes(): Int = when (this) {
    SignerClass.GOOGLE_PLAY -> R.string.settings_signer_installed_google_play
    SignerClass.VETTID -> R.string.settings_signer_installed_vettid
    SignerClass.DEVELOPMENT -> R.string.settings_signer_installed_development
}

/** "was installed from …", for the new phone's VettID in the transfer's confirmation; null: a class this app does not know. */
@StringRes
private fun newPhoneRes(c: SignerClass?): Int = when (c) {
    SignerClass.GOOGLE_PLAY -> R.string.settings_transfer_signer_new_google_play
    SignerClass.VETTID -> R.string.settings_transfer_signer_new_vettid
    SignerClass.DEVELOPMENT -> R.string.settings_transfer_signer_new_development
    null -> R.string.settings_transfer_signer_new_other
}

/** "is from …", for this phone's VettID; null: this app's signer could not be read. */
@StringRes
private fun thisPhoneRes(c: SignerClass?): Int = when (c) {
    SignerClass.GOOGLE_PLAY -> R.string.settings_transfer_signer_this_google_play
    SignerClass.VETTID -> R.string.settings_transfer_signer_this_vettid
    SignerClass.DEVELOPMENT -> R.string.settings_transfer_signer_this_development
    null -> R.string.settings_transfer_signer_this_other
}

/**
 * The confirmation before the PIN when a transfer changes the vault's signer (`signer_changed`): "The new phone's
 * VettID was installed from Google Play (signed by Google); this one is from F-Droid or direct (signed by VettID).
 * Continue only if you installed it yourself." (or the reverse; a development build and an unknown signer have
 * their own halves).
 */
@Composable
fun signerChangeText(new: SignerClass?, current: SignerClass?): String =
    stringResource(R.string.settings_transfer_signer_body, stringResource(newPhoneRes(new)), stringResource(thisPhoneRes(current)))
