package com.vettid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vettid.app.R
import com.vettid.core.data.vault.CanaryManifestRepository
import com.vettid.core.data.vault.CanaryManifestView
import com.vettid.core.ui.components.ConfirmDialog

/** The canary manifest dialog for [prompt] (stateless). */
@Composable
fun CanaryManifestDialog(prompt: CanaryManifestPrompt, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (prompt) {
        is CanaryManifestPrompt.Confirm -> ConfirmDialog(
            title = stringResource(R.string.canary_confirm_title),
            text = stringResource(R.string.canary_confirm_body, prompt.view.serial, prompt.view.keyId, releases(prompt.view)),
            confirmLabel = stringResource(R.string.canary_confirm),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            modifier = Modifier.testTag("canary_confirm"),
        )
        is CanaryManifestPrompt.Refused -> ConfirmDialog(
            title = stringResource(R.string.canary_refused_title),
            text = stringResource(refusedText(prompt.code)),
            confirmLabel = stringResource(R.string.canary_ok),
            onConfirm = onDismiss,
            onDismiss = onDismiss,
            modifier = Modifier.testTag("canary_refused"),
        )
        is CanaryManifestPrompt.Installed -> ConfirmDialog(
            title = stringResource(R.string.canary_installed_title),
            text = stringResource(R.string.canary_installed_body, prompt.view.serial),
            confirmLabel = stringResource(R.string.canary_ok),
            onConfirm = onDismiss,
            onDismiss = onDismiss,
            modifier = Modifier.testTag("canary_installed"),
        )
    }
}

@Composable
private fun releases(v: CanaryManifestView): String =
    v.releases.map { stringResource(R.string.canary_release, it.number.toInt(), it.status) }.joinToString(", ")

private fun refusedText(code: String?): Int = when (code) {
    CanaryManifestRepository.CODE_SIGNATURE -> R.string.canary_refused_signature
    CanaryManifestRepository.CODE_OLDER -> R.string.canary_refused_older
    CanaryManifestRepository.CODE_PUBLISHED -> R.string.canary_refused_published
    CanaryManifestRepository.CODE_FORMAT, CanaryManifestRepository.CODE_TOO_LARGE -> R.string.canary_refused_format
    else -> R.string.canary_refused_other
}
