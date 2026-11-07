package com.vettid.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.ui.components.ModalBottomSheet
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
    DetailCard(modifier.testTag("whats_new")) { WhatsNewBody(release, notes, notesUrl) }
}

/**
 * The release offer at unlock, compact (owner decision 2026-11-07, as iOS): shown below the PIN, it says "Update
 * available: release N", the security line when the release fixes one, the offer with its fingerprint, the approval and
 * "What's new in release N", which opens [ReleaseNotesSheet]. The approval never waits for the notes to be opened.
 * Nothing for a rollback (the PIN is not sent) or without an offer. Shared by the unlock and the recovery's PIN step.
 */
@Composable
internal fun ReleaseOfferNotice(p: PreflightInfo, approveOffer: Boolean, onApproveOffer: (Boolean) -> Unit, notes: ReleaseNotes?) {
    val offer = p.offer ?: return
    if (p.rollback) return
    var notesOpen by rememberSaveable(offer.number) { mutableStateOf(false) }
    val title = stringResource(R.string.release_notes_title, offer.number.toInt())
    val offerText = stringResource(R.string.unlock_offer_body, p.routed.number.toInt(), offer.number.toInt(), offer.fingerprint)
    DetailCard(Modifier.testTag("release_offer")) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(R.string.unlock_offer_title, offer.number.toInt()),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            val security = (notes as? ReleaseNotes.Available)?.entry?.security
            if (security != null && security != ReleaseSecurity.NONE) {
                SecurityLine(security, Modifier.testTag("release_offer_security"))
            }
            Text(
                offerText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("release_offer_fingerprint"),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchTarget)
                    .toggleable(value = approveOffer, role = Role.Checkbox, onValueChange = onApproveOffer)
                    .testTag("approve_offer"),
            ) {
                Checkbox(checked = approveOffer, onCheckedChange = null)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.unlock_offer_approve), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                onClick = { notesOpen = true },
                modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("whats_new_open"),
            ) { Text(title) }
        }
    }
    if (notesOpen) ReleaseNotesSheet(p, notes) { notesOpen = false }
}

/**
 * The full notes of [p]'s offered release in a sheet (the shell's [ModalBottomSheet]), titled "What's new in release N":
 * the entry, the earlier releases, "From <host>", the offer with its fingerprint and "Full release notes".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotesSheet(p: PreflightInfo, notes: ReleaseNotes?, onDismiss: () -> Unit) {
    val offer = p.offer ?: return
    val release = offer.number
    val notesUrl = offer.notes
    val offerText = stringResource(R.string.unlock_offer_body, p.routed.number.toInt(), offer.number.toInt(), offer.fingerprint)
    val title = stringResource(R.string.release_notes_title, release.toInt())
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.semantics { paneTitle = title }.testTag("whats_new_sheet")) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
        ) {
            WhatsNewBody(release, notes, notesUrl) {
                Text(
                    offerText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("whats_new_fingerprint"),
                )
            }
        }
    }
}

/** The notes' title, the entry (or its loading or unavailable line), [beforeLink], then "Full release notes". */
@Composable
private fun WhatsNewBody(
    release: Long,
    notes: ReleaseNotes?,
    notesUrl: String,
    beforeLink: @Composable ColumnScope.() -> Unit = {},
) {
    val uri = LocalUriHandler.current
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
        beforeLink()
        if (ReleaseLog.openable(notesUrl)) {
            TextButton(
                onClick = { uri.openUri(notesUrl) },
                modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("whats_new_full"),
            ) { Text(stringResource(R.string.release_notes_full)) }
        }
    }
}

/** "No security fix" / "Security fix: update recommended" / "…urgently", emphasised (urgent in the error colour). */
@Composable
private fun SecurityLine(security: ReleaseSecurity, modifier: Modifier = Modifier) {
    Text(
        stringResource(
            when (security) {
                ReleaseSecurity.NONE -> R.string.release_notes_security_none
                ReleaseSecurity.RECOMMENDED -> R.string.release_notes_security_recommended
                ReleaseSecurity.URGENT -> R.string.release_notes_security_urgent
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (security == ReleaseSecurity.URGENT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
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
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("whats_new_security")) {
        SecurityLine(e.security)
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
