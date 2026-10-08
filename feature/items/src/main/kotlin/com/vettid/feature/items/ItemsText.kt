package com.vettid.feature.items

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vettid.core.data.items.DraftProblem
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultLimit
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.message

/** The member-facing names of sensitivities, kinds and categories, and the Vault screens' messages. */
object ItemsText {
    @StringRes
    fun sensitivity(s: Sensitivity): Int = when (s) {
        Sensitivity.DATA -> R.string.items_sensitivity_data
        Sensitivity.SECRET -> R.string.items_sensitivity_secret
        Sensitivity.CRITICAL -> R.string.items_sensitivity_critical
    }

    @StringRes
    fun sensitivityNote(s: Sensitivity): Int = when (s) {
        Sensitivity.DATA -> R.string.items_sensitivity_data_note
        Sensitivity.SECRET -> R.string.items_sensitivity_secret_note
        Sensitivity.CRITICAL -> R.string.items_sensitivity_critical_note
    }

    fun sensitivityIcon(s: Sensitivity): ImageVector = when (s) {
        Sensitivity.DATA -> Icons.Outlined.Description
        Sensitivity.SECRET -> Icons.Outlined.VisibilityOff
        Sensitivity.CRITICAL -> Icons.Outlined.Lock
    }

    @StringRes
    @Suppress("CyclomaticComplexMethod") // one label per kind
    fun kind(kind: String): Int = when (kind) {
        FieldKinds.TEXT -> R.string.items_kind_text
        FieldKinds.MULTILINE -> R.string.items_kind_multiline
        FieldKinds.NUMBER -> R.string.items_kind_number
        FieldKinds.DATE -> R.string.items_kind_date
        FieldKinds.EMAIL -> R.string.items_kind_email
        FieldKinds.PHONE -> R.string.items_kind_phone
        FieldKinds.URL -> R.string.items_kind_url
        FieldKinds.PASSWORD -> R.string.items_kind_password
        FieldKinds.OTP -> R.string.items_kind_otp
        FieldKinds.ADDRESS -> R.string.items_kind_address
        else -> R.string.items_kind_other
    }

    /** What a value of [kind] must look like, for an invalid one (§10.7). */
    @StringRes
    fun kindRule(kind: String): Int = when (kind) {
        FieldKinds.NUMBER -> R.string.items_rule_number
        FieldKinds.DATE -> R.string.items_rule_date
        FieldKinds.EMAIL -> R.string.items_rule_email
        FieldKinds.PHONE -> R.string.items_rule_phone
        FieldKinds.URL -> R.string.items_rule_url
        FieldKinds.OTP -> R.string.items_rule_otp
        FieldKinds.ADDRESS -> R.string.items_rule_address
        else -> R.string.items_rule_text
    }

    @Composable
    fun category(id: String): String = ItemTemplates.category(id)?.let { stringResource(it.label) } ?: ItemTemplates.customLabel(id)

    /** The message of one draft problem ([kind]: the field's, for an invalid value). */
    @Composable
    @Suppress("CyclomaticComplexMethod") // one message per problem
    fun problem(p: DraftProblem, kind: String = FieldKinds.TEXT): String = when (p) {
        DraftProblem.NAME_EMPTY -> stringResource(R.string.items_problem_name_empty)
        DraftProblem.NAME_TOO_LONG -> stringResource(R.string.items_problem_name_long)
        DraftProblem.BAD_CHARACTER -> stringResource(R.string.items_problem_bad_character)
        DraftProblem.CATEGORY_INVALID -> stringResource(R.string.items_problem_category)
        DraftProblem.LABEL_EMPTY -> stringResource(R.string.items_problem_label_empty)
        DraftProblem.LABEL_TOO_LONG -> stringResource(R.string.items_problem_label_long)
        DraftProblem.VALUE_INVALID -> stringResource(kindRule(kind))
        DraftProblem.VALUE_TOO_LONG -> stringResource(R.string.items_problem_value_long)
        DraftProblem.NOTES_TOO_LONG -> stringResource(R.string.items_problem_notes_long)
        DraftProblem.TOO_MANY_FIELDS -> stringResource(R.string.items_problem_fields)
        DraftProblem.TOO_MANY_TAGS -> stringResource(R.string.items_problem_tags)
        DraftProblem.TAG_INVALID -> stringResource(R.string.items_problem_tag)
        DraftProblem.PROFILE_NOT_DATA -> stringResource(R.string.items_problem_profile)
        DraftProblem.TOO_LARGE -> stringResource(R.string.items_problem_size)
    }

    /**
     * A failure on the Vault screens: a `limit` in the member's words from the limit it names ([limit], VAULT-MESSAGING
     * 0.21.0 §10.1); from an older vault, which names none, the likeliest of §10.7 and §10.8 ([creating]: an add, where
     * `limit` is the vault's item count; [critical]: the credential's; [profile]: the shared profile's). A change made
     * on another device (`conflict`), else the app's general message.
     */
    @Composable
    @Suppress("CyclomaticComplexMethod") // one message per failure
    fun failure(
        kind: FailureKind,
        creating: Boolean = false,
        critical: Boolean = false,
        profile: Boolean = false,
        limit: VaultLimit? = null,
    ): String = when {
        kind == FailureKind.LIMIT && limit != null -> limit(limit)
        kind == FailureKind.LIMIT && profile -> stringResource(R.string.items_error_limit_profile)
        kind == FailureKind.LIMIT && creating && critical -> stringResource(R.string.items_error_limit_critical)
        kind == FailureKind.LIMIT && creating -> stringResource(R.string.items_error_limit_items)
        kind == FailureKind.LIMIT -> stringResource(R.string.items_error_limit)
        kind == FailureKind.CONFLICT -> stringResource(R.string.items_error_conflict)
        kind == FailureKind.NOT_FOUND -> stringResource(R.string.items_error_not_found)
        kind == FailureKind.OTHER -> stringResource(R.string.items_error_refused)
        else -> stringResource(kind.messageRes())
    }

    /** A named limit (VAULT-MESSAGING 0.21.0 §10.1) in the member's words, with its bound. */
    @Composable
    fun limit(l: VaultLimit): String = l.message(LocalResources.current)
}
