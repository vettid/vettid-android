package com.vettid.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing

/**
 * The line a connection request carries when the invitation declared itself a service (VAULT-MESSAGING 0.69.0 §6.4,
 * ANDROID-PLAN 0.1.90): "This is a service, not a person." with the small print that it is the invitation's claim, then
 * the `ik` fingerprint (§10.8) when the app has it ([fingerprintLabel] names it where a screen has a word for it). Under the
 * name, above the safety code. A claim by the other side, not a fact: no badge, no check mark.
 */
@Composable
fun ServiceRequestLine(modifier: Modifier = Modifier, fingerprint: String? = null, fingerprintLabel: String? = null) {
    Column(modifier.testTag("service_request")) {
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(R.string.service_request_line),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("service_request_line"),
        )
        Text(
            stringResource(R.string.service_request_caveat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("service_request_caveat"),
        )
        if (fingerprint != null) {
            Spacer(Modifier.height(Spacing.s))
            fingerprintLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            Text(
                fingerprint,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("service_request_fingerprint"),
            )
        }
    }
}

/** The small "Service" label beside a service's name (ANDROID-PLAN 0.1.90). */
@Composable
fun ServiceTag(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.service_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.testTag("service_label"),
    )
}
