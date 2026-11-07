package com.vettid.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseLog
import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.ReleaseSecurity
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.ModalBottomSheet
import com.vettid.core.ui.theme.Spacing
import java.util.UUID

/**
 * **What's new in release N** (ANDROID-PLAN 0.1.31, RELEASE-UPDATES 0.3.0 §2): the release log entry of the offered
 * release, labelled with the log's host, as plain text (never linkified); while [notes] is null, a progress line.
 * Information only: nothing here gates the update. "Full release notes" opens the manifest's [notesUrl] (https only).
 */
@Composable
fun WhatsNew(release: Long, notes: ReleaseNotes?, notesUrl: String, modifier: Modifier = Modifier) {
    DetailCard(modifier.testTag("whats_new")) { WhatsNewBody(release, notes, notesUrl) }
}

/** This process's mark on a notes sheet saved open (a test may change it to stand for a new process). */
internal var notesSheetProcess: String = UUID.randomUUID().toString()

/**
 * The release offer at unlock, compact (owner decision 2026-11-07, as iOS; ANDROID-PLAN 0.1.122): shown below the PIN, it
 * says "Update available: release N", the security line (from the release log: the progress line while it loads,
 * "Release notes unavailable" when it can't be had), the offer with its fingerprint, "Approve the update with this unlock"
 * and "What's new in release N", which opens [ReleaseNotesSheet]. The approval is enabled whatever the notes' state and
 * never waits for the sheet; `security_text` is only in the sheet.
 * Nothing for a rollback (the PIN is not sent) or without an offer. Shared by the unlock and the recovery's PIN step.
 */
@Composable
internal fun ReleaseOfferNotice(p: PreflightInfo, approveOffer: Boolean, onApproveOffer: (Boolean) -> Unit, notes: ReleaseNotes?) {
    val offer = p.offer ?: return
    if (p.rollback) return
    // Saved, so a rotation keeps the sheet open; tied to this process and this offer, so a sheet saved open before the
    // process died does not open again by itself when the check comes back, nor for another release (review F4).
    var notesOpenFor by rememberSaveable { mutableStateOf<String?>(null) }
    val openKey = "$notesSheetProcess:${offer.number}"
    // A saved key of another process or release is dropped, not kept for later (the lead's review of #327).
    SideEffect { if (notesOpenFor != null && notesOpenFor != openKey) notesOpenFor = null }
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
            NotesLine(notes)
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
                onClick = { notesOpenFor = openKey },
                modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag("whats_new_open"),
            ) { Text(title) }
        }
    }
    if (notesOpenFor == openKey) ReleaseNotesSheet(p, notes) { notesOpenFor = null }
}

/**
 * The full notes of [p]'s offered release in a sheet (the shell's [ModalBottomSheet]), titled "What's new in release N":
 * the entry, the earlier releases, "From <host>", the offer with its fingerprint and "Full release notes".
 */
// The wrapper's default sheet state is Material's experimental API: without the opt-in the call does not compile.
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
            null -> LoadingLine(Modifier.testTag("whats_new_loading"))
            ReleaseNotes.Unavailable -> UnavailableLine(Modifier.testTag("whats_new_unavailable"))
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

/**
 * The compact notice's line from the release log: the progress line, "Release notes unavailable", or the security line.
 * Its height is reserved for the tallest of them (invisible copies, out of the semantics tree), so the approval and the
 * link below never move when the notes arrive (review F2): a tap meant for the link can't land on the checkbox.
 */
@Composable
private fun NotesLine(notes: ReleaseNotes?) {
    Box {
        val hidden = Modifier.alpha(0f).clearAndSetSemantics {}
        // The progress line's size without its animation: the indicator (16dp) is no taller than the text.
        Row(hidden) {
            Spacer(Modifier.width(16.dp + Spacing.s))
            Text(stringResource(R.string.release_notes_loading), style = MaterialTheme.typography.bodyMedium)
        }
        UnavailableLine(hidden)
        ReleaseSecurity.entries.forEach { SecurityLine(it, hidden) }
        // The visible line alone is a polite live region: "Security fix: update urgently" is read out when it arrives.
        val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        when (notes) {
            null -> LoadingLine(live.testTag("release_offer_loading"))
            ReleaseNotes.Unavailable -> UnavailableLine(live.testTag("release_offer_unavailable"))
            is ReleaseNotes.Available -> SecurityLine(notes.entry.security, live.testTag("release_offer_security"))
        }
    }
}

/** "Loading the release notes…" with a small progress indicator, read as one line. */
@Composable
private fun LoadingLine(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.semantics(mergeDescendants = true) {}) {
        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(Spacing.s))
        Text(stringResource(R.string.release_notes_loading), style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Release notes unavailable", muted. */
@Composable
private fun UnavailableLine(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.release_notes_unavailable),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
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
