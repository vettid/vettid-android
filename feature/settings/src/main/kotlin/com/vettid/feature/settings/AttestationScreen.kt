package com.vettid.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vettid.core.attestation.CheckResult
import com.vettid.core.attestation.EnclaveCheck
import com.vettid.core.attestation.EnclaveRecord
import com.vettid.core.attestation.EnclaveRefusal
import com.vettid.core.attestation.manifest.ReleaseStatus
import com.vettid.core.data.vault.AttestationInfo
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.CanaryManifestView
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.FullScreenProgress
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecureClipboard
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsInfoRow
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdTheme

/**
 * Settings → Security → Advanced security → **Attestation** (ANDROID-PLAN 0.1.124 §4 "Attestation in detail"): this phone's
 * last verification of the enclave its vault runs on ([record]; null: none yet), each check the app makes with its result
 * ("Passed" only with a recorded pass), the values each rests on, the release's source, this phone, and "Copy all" of the
 * public material. [canary]: the installed canary manifest, shown only when there is one. [copy]: puts a text on the
 * clipboard, marked sensitive and cleared after 60 seconds (the app's clipboard rule).
 */
@Composable
fun AttestationContent(
    state: LoadState<AttestationInfo>,
    onBack: () -> Unit,
    canary: CanaryManifestView? = null,
    onRemoveCanary: () -> Unit = {},
    record: EnclaveRecord? = null,
    copy: ((label: String, text: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val put = copy ?: { label, text -> SecureClipboard.copy(context, label, text, sensitive = true) }
    DetailScaffold(onBackClick = onBack, background = VettIdTheme.colors.groupedBackground) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LargeTitle(stringResource(R.string.settings_attestation_title))
            Text(
                stringResource(R.string.settings_attestation_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gutter + Spacing.xs),
            )
            val a = state.value
            when {
                state.loading -> FullScreenProgress(stringResource(R.string.settings_loading))
                a == null -> LoadFailure(state.error ?: FailureKind.OTHER, null, state.errorCode)
                record == null -> {
                    Text(
                        stringResource(R.string.settings_attestation_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(horizontal = Spacing.gutter + Spacing.xs, vertical = Spacing.s)
                            .testTag("attestation_none"),
                    )
                    PhoneSection(a)
                    if (canary != null) CanaryManifestSection(canary, onRemoveCanary)
                    Spacer(Modifier.height(Spacing.xxl))
                }
                else -> {
                    RecordNotice(record)
                    EnclaveSection(record, a.environment, put)
                    ChecksSection(record, a.rootFingerprint)
                    ReleaseSection(record, a.releaseTagPrefix)
                    PhoneSection(a)
                    CopyAll(AttestationDetail.copyAll(record, a.environment, a.releaseTagPrefix, a.rootFingerprint), put)
                    if (canary != null) CanaryManifestSection(canary, onRemoveCanary)
                    Spacer(Modifier.height(Spacing.xxl))
                }
            }
        }
    }
}

@Composable
private fun RecordNotice(r: EnclaveRecord) {
    val body = when (val n = AttestationDetail.notice(r)) {
        null -> return
        AttestationDetail.Notice.Failed -> stringResource(R.string.settings_attestation_failed_body)
        is AttestationDetail.Notice.Refused ->
            stringResource(R.string.settings_attestation_refused_body, stringResource(refusalText(n.why)))
    }
    NoticeCard(
        NoticeKind.URGENT,
        stringResource(R.string.settings_attestation_failed_title),
        body,
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("attestation_failed"),
    )
}

/** Why the release was refused after every check passed (owner-approved, GC #8641 a). */
private fun refusalText(why: EnclaveRefusal): Int = when (why) {
    EnclaveRefusal.NOT_ACTIVE -> R.string.settings_attestation_refused_not_active
    EnclaveRefusal.OLDER_RELEASE -> R.string.settings_attestation_refused_older
    EnclaveRefusal.OTHER_INSTANCE -> R.string.settings_attestation_refused_other_instance
    EnclaveRefusal.TIME_SCALE -> R.string.settings_attestation_refused_time_scale
}

@Composable
private fun statusText(s: ReleaseStatus?): String = when (s) {
    ReleaseStatus.ACTIVE -> stringResource(R.string.settings_attestation_status_active)
    ReleaseStatus.DEPRECATED -> stringResource(R.string.settings_attestation_status_deprecated)
    ReleaseStatus.RETIRED -> stringResource(R.string.settings_attestation_status_retired)
    ReleaseStatus.REMOVED -> stringResource(R.string.settings_attestation_status_removed)
    null -> ""
}

/** A value, with "Not verified" after it unless every check it rests on passed. */
@Composable
private fun shown(value: String, verified: Boolean): String =
    if (verified) value else "$value · ${stringResource(R.string.settings_attestation_not_verified)}"

@Composable
private fun EnclaveSection(r: EnclaveRecord, environment: String, put: (String, String) -> Unit) {
    SettingsSectionHeader(stringResource(R.string.settings_attestation_enclave))
    SettingsGroup {
        SettingsInfoRow(stringResource(R.string.settings_attestation_environment), environment, icon = Icons.Outlined.Storage)
        if (AttestationDetail.releaseShown(r)) {
            SettingsDivider()
            val ends = r.releaseEndsAt?.let { stringResource(R.string.settings_attestation_ends, Times.full(it)) }.orEmpty()
            SettingsInfoRow(
                stringResource(R.string.settings_attestation_release),
                stringResource(R.string.settings_attestation_release_value, r.releaseNumber!!.toInt(), statusText(r.releaseStatus)) + ends,
                icon = Icons.Outlined.Tag,
                modifier = Modifier.testTag("attestation_release"),
            )
        }
        val doc = AttestationDetail.documentVerified(r)
        listOf("PCR0" to r.pcr0, "PCR1" to r.pcr1, "PCR2" to r.pcr2).forEach { (label, v) ->
            if (v != null) {
                SettingsDivider()
                PcrRow(label, v, doc, put)
            }
        }
        r.attestedAt?.let {
            SettingsDivider()
            SettingsInfoRow(
                stringResource(R.string.settings_attestation_attested_at), shown(Times.full(it), doc), icon = Icons.Outlined.VerifiedUser,
                modifier = Modifier.testTag("attestation_attested_at"),
            )
        }
        r.notAfter?.let {
            SettingsDivider()
            SettingsInfoRow(
                stringResource(R.string.settings_attestation_valid_until),
                shown(Times.full(it), AttestationDetail.notAfterVerified(r)),
                icon = Icons.Outlined.Lock,
                modifier = Modifier.testTag("attestation_valid_until"),
            )
        }
    }
}

/** A PCR: its full hex, monospaced and wrapped, and "Copy" ("Copied" once copied). */
@Composable
private fun PcrRow(label: String, hex: String, verified: Boolean, put: (String, String) -> Unit) {
    var copied by rememberSaveable(hex) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("attestation_$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (!verified) {
                Text(
                    stringResource(R.string.settings_attestation_not_verified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(onClick = {
            put(label, hex)
            copied = true
        }) {
            Text(stringResource(if (copied) R.string.settings_attestation_copied else R.string.settings_attestation_copy))
        }
    }
}

@Composable
private fun checkLabel(c: EnclaveCheck): String = stringResource(
    when (c) {
        EnclaveCheck.MANIFEST -> R.string.settings_attestation_check_manifest
        EnclaveCheck.CHAIN -> R.string.settings_attestation_check_chain
        EnclaveCheck.SIGNATURE -> R.string.settings_attestation_check_signature
        EnclaveCheck.KEY -> R.string.settings_attestation_check_key
        EnclaveCheck.RECENT -> R.string.settings_attestation_check_recent
        EnclaveCheck.NOT_DEBUG -> R.string.settings_attestation_check_not_debug
        EnclaveCheck.OWN_RELEASE -> R.string.settings_attestation_check_own_release
        EnclaveCheck.VALIDITY -> R.string.settings_attestation_check_validity
        EnclaveCheck.MATCH -> R.string.settings_attestation_check_match
    },
)

@Composable
private fun ChecksSection(r: EnclaveRecord, root: String) {
    SettingsSectionHeader(
        stringResource(R.string.settings_attestation_section_checks) + " · " +
            stringResource(R.string.settings_attestation_as_of, Times.full(r.at)),
    )
    SettingsGroup {
        EnclaveCheck.entries.forEachIndexed { i, c ->
            if (i > 0) SettingsDivider()
            CheckLine(c, r.result(c))
            if (c == EnclaveCheck.MANIFEST && AttestationDetail.manifestShown(r)) {
                SubLine(
                    stringResource(R.string.settings_attestation_manifest_key, r.manifestKeyId!!, r.manifestSerial!!.toInt()),
                    "attestation_manifest_key",
                )
            }
            if (c == EnclaveCheck.CHAIN) {
                SubLine(
                    stringResource(R.string.settings_attestation_root_fingerprint) + "\n" + root,
                    "attestation_root_fingerprint",
                    mono = true,
                )
            }
        }
    }
}

/** One check: its line and its state, in text and glyph (never by color alone), read as "<line>: <state>". */
@Composable
private fun CheckLine(c: EnclaveCheck, result: CheckResult) {
    val label = checkLabel(c)
    val state = stringResource(
        when (result) {
            CheckResult.PASSED -> R.string.settings_attestation_check_passed
            CheckResult.FAILED -> R.string.settings_attestation_check_failed
            CheckResult.NOT_RUN -> R.string.settings_attestation_check_not_run
        },
    )
    val spoken = stringResource(R.string.settings_attestation_check_spoken, label, state)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s)
            .clearAndSetSemantics { contentDescription = spoken }
            .testTag("check_${c.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        val (glyph, tint) = when (result) {
            CheckResult.PASSED -> Icons.Outlined.CheckCircle to VettIdTheme.colors.success
            CheckResult.FAILED -> Icons.Outlined.Cancel to MaterialTheme.colorScheme.error
            CheckResult.NOT_RUN -> Icons.Outlined.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(glyph, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(state, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@Composable
private fun SubLine(text: String, tag: String, mono: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (mono) FontFamily.Monospace else null,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.gutter + 32.dp, end = Spacing.gutter, bottom = Spacing.s).testTag(tag),
    )
}

@Composable
private fun ReleaseSection(r: EnclaveRecord, prefix: String?) {
    val tag = AttestationDetail.tag(r, prefix) ?: return
    val uri = LocalUriHandler.current
    SettingsSectionHeader(stringResource(R.string.settings_attestation_section_release))
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("attestation_source")) {
            Text(stringResource(R.string.settings_attestation_source), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { uri.openUri(AttestationDetail.tagUrl(tag)) }) { Text(tag, fontFamily = FontFamily.Monospace) }
        }
        SettingsDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("attestation_reproducible")) {
            Text(stringResource(R.string.settings_attestation_reproducible), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_attestation_rebuild_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { uri.openUri(AttestationDetail.rebuildUrl(tag)) }) {
                Text(stringResource(R.string.settings_attestation_rebuild))
            }
        }
    }
}

/** This phone: the rows of the phone's own key (unchanged), then the app's version. Never copied. */
@Composable
private fun PhoneSection(a: AttestationInfo) {
    SettingsSectionHeader(stringResource(R.string.settings_attestation_device))
    SettingsGroup(Modifier.testTag("attestation_phone")) {
        SettingsInfoRow(
            stringResource(R.string.settings_attestation_key),
            if (a.keyPresent) {
                a.keyLevel ?: stringResource(R.string.settings_unknown)
            } else {
                stringResource(R.string.settings_attestation_key_none)
            },
            icon = Icons.Outlined.Key,
        )
        SettingsDivider()
        SettingsInfoRow(
            stringResource(R.string.settings_attestation_strongbox), yesNo(a.strongBoxAvailable), icon = Icons.Outlined.Fingerprint,
        )
        SettingsDivider()
        SettingsInfoRow(stringResource(R.string.settings_attestation_boot), a.verifiedBoot ?: "-", icon = Icons.Outlined.VerifiedUser)
        SettingsDivider()
        SettingsInfoRow(stringResource(R.string.settings_attestation_locked), yesNo(a.deviceLocked), icon = Icons.Outlined.Lock)
        SettingsDivider()
        SettingsInfoRow(stringResource(R.string.settings_attestation_boot_key), a.bootKeyFingerprint ?: "-", icon = Icons.Outlined.Key)
        SettingsDivider()
        SettingsInfoRow(stringResource(R.string.settings_attestation_app_version), a.appVersion.ifEmpty { "-" }, icon = Icons.Outlined.Tag)
    }
}

@Composable
private fun CopyAll(text: String, put: (String, String) -> Unit) {
    var copied by rememberSaveable(text) { mutableStateOf(false) }
    val label = stringResource(R.string.settings_attestation_copy_all)
    TextButton(
        onClick = {
            put(label, text)
            copied = true
        },
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("attestation_copy_all"),
    ) {
        Text(stringResource(if (copied) R.string.settings_attestation_copied else R.string.settings_attestation_copy_all))
    }
}
