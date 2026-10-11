package com.vettid.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.core.data.vault.ReleaseLog
import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.ReleaseSecurity
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.theme.Spacing

/**
 * **What's new in release N** (ANDROID-PLAN 0.1.31, RELEASE-UPDATES 0.3.0 §2): the release log entry of the offered
 * release, labelled with the log's host, as plain text (never linkified); while [notes] is null, a progress line.
 * Information only: nothing here gates the update. "Full release notes" opens the manifest's [notesUrl] (https only).
 */
@Composable
fun WhatsNew(release: Long, notes: ReleaseNotes?, notesUrl: String, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    DetailCard(modifier.testTag("whats_new")) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(R.string.release_notes_title, release.toInt()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            when (notes) {
                null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("whats_new_loading"),
                ) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.release_notes_loading), style = MaterialTheme.typography.bodyMedium)
                }
                ReleaseNotes.Unavailable -> Text(
                    stringResource(R.string.release_notes_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("whats_new_unavailable"),
                )
                is ReleaseNotes.Available -> Entry(notes)
            }
            if (ReleaseLog.openable(notesUrl)) {
                TextButton(
                    onClick = { uri.openUri(notesUrl) },
                    modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("whats_new_full"),
                ) { Text(stringResource(R.string.release_notes_full)) }
            }
        }
    }
}

@Composable
private fun Entry(notes: ReleaseNotes.Available) {
    val e = notes.entry
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(e.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("whats_new_summary"))
    if (e.changes.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            e.changes.forEach { c ->
                Row(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(Spacing.s))
                    Text(c, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    val security = when (e.security) {
        ReleaseSecurity.NONE -> stringResource(R.string.release_notes_security_none)
        ReleaseSecurity.RECOMMENDED -> stringResource(R.string.release_notes_security_recommended)
        ReleaseSecurity.URGENT -> stringResource(R.string.release_notes_security_urgent)
    }
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("whats_new_security")) {
        Text(
            security,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (e.security == ReleaseSecurity.URGENT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        e.securityText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    if (notes.earlier.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.testTag("whats_new_earlier")) {
            Text(stringResource(R.string.release_notes_earlier), style = MaterialTheme.typography.titleSmall)
            notes.earlier.forEach { r ->
                Text(
                    stringResource(R.string.release_notes_earlier_item, r.release.toInt(), r.summary),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("whats_new_from")) {
        Text(stringResource(R.string.release_notes_from, notes.host), style = MaterialTheme.typography.labelLarge, color = muted)
        Text(stringResource(R.string.release_notes_information), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}
