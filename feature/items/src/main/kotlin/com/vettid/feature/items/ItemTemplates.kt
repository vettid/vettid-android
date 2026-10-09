package com.vettid.feature.items

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Description
import androidx.compose.ui.graphics.vector.ImageVector
import com.vettid.core.data.items.DraftField
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.ItemDraft
import com.vettid.core.data.items.Sensitivity

/** A recommended category (VAULT-MESSAGING §10.7): its label and icon (vettid-vault `docs/item-templates.json`). */
data class ItemCategory(val id: String, @param:StringRes val label: Int, val icon: ImageVector)

/**
 * One field a template pre-fills: an English default label (translated in resources), a kind, and the registry's
 * presentation hint [format] (VAULT-ITEMS 0.1.2, VAULT-MESSAGING 0.23.0 §10.7): `"format": "month"` on a `date` field
 * asks for a month and a year only and stores `YYYY-MM`, as for a payment card's expiry. The hint lives in the
 * registry and the apps; it is not stored in the item and the vault never sees it.
 */
data class TemplateField(@param:StringRes val label: Int, val kind: String, val format: String? = null) {
    /** A `date` with `"format": "month"`: entered with the month picker. */
    val monthYear: Boolean get() = kind == FieldKinds.DATE && format == FORMAT_MONTH

    companion object {
        /** The registry's month hint (vettid-vault `docs/item-templates.json` version 3). */
        const val FORMAT_MONTH = "month"
    }
}

/**
 * A template (§10.7: templates live in the apps): it suggests the name (as the name's placeholder: nothing is pre-typed,
 * owner request 2026-10-08), and pre-fills the category, the fields (labels and kinds, values empty), suggested tags
 * and a suggested sensitivity; the member can add, remove or rename anything. [id] goes into `item.template`.
 */
data class ItemTemplate(
    val id: String,
    @param:StringRes val name: Int,
    val category: String,
    val sensitivity: Sensitivity,
    val tags: List<String>,
    val fields: List<TemplateField>,
) {
    /**
     * The draft this template starts, with its labels in the app's language and the name empty: the editor shows the
     * template's name as the placeholder and saves under it when the member leaves the name empty.
     */
    fun draft(context: Context): ItemDraft = ItemDraft(
        name = "",
        category = category,
        template = id,
        sensitivity = sensitivity,
        tags = tags,
        fields = fields.map { DraftField(label = context.getString(it.label), kind = it.kind, monthYear = it.monthYear) },
    )
}

/**
 * The shared registry of recommended categories and templates (vettid-vault `docs/item-templates.json`, format 1,
 * version 1), in its order.
 */
object ItemTemplates {
    val categories: List<ItemCategory> = listOf(
        ItemCategory("identity_document", R.string.items_category_identity_document, Icons.Outlined.Badge),
        ItemCategory("login", R.string.items_category_login, Icons.Outlined.Key),
        ItemCategory("payment_card", R.string.items_category_payment_card, Icons.Outlined.CreditCard),
        ItemCategory("bank_account", R.string.items_category_bank_account, Icons.Outlined.AccountBalance),
        ItemCategory("medical", R.string.items_category_medical, Icons.Outlined.MedicalServices),
        ItemCategory("insurance", R.string.items_category_insurance, Icons.Outlined.HealthAndSafety),
        ItemCategory("vehicle", R.string.items_category_vehicle, Icons.Outlined.DirectionsCar),
        ItemCategory("contact", R.string.items_category_contact, Icons.Outlined.Person),
        ItemCategory("note", R.string.items_category_note, Icons.Outlined.Description),
        ItemCategory("crypto_wallet", R.string.items_category_crypto_wallet, Icons.Outlined.AccountBalanceWallet),
        ItemCategory("other", R.string.items_category_other, Icons.Outlined.Folder),
    )

    private val byCategory = categories.associateBy { it.id }

    /** A recommended category, or null for a member-defined one (§10.7 allows any `[a-z][a-z0-9_]{0,31}`). */
    fun category(id: String): ItemCategory? = byCategory[id]

    fun icon(category: String): ImageVector = byCategory[category]?.icon ?: Icons.Outlined.Folder

    /** A member-defined category, as shown: `home_lab` → "Home lab". */
    fun customLabel(id: String): String = com.vettid.core.data.items.ItemCategories.humanize(id)

    private fun f(@StringRes label: Int, kind: String, format: String? = null) = TemplateField(label, kind, format)

    val all: List<ItemTemplate> = listOf(
        ItemTemplate(
            "passport", R.string.items_template_passport, "identity_document", Sensitivity.DATA, listOf("identity", "travel"),
            listOf(
                f(R.string.items_label_number, FieldKinds.TEXT), f(R.string.items_label_full_name, FieldKinds.TEXT),
                f(R.string.items_label_nationality, FieldKinds.TEXT), f(R.string.items_label_date_of_birth, FieldKinds.DATE),
                f(R.string.items_label_issued, FieldKinds.DATE), f(R.string.items_label_expires, FieldKinds.DATE),
                f(R.string.items_label_issuing_authority, FieldKinds.TEXT),
            ),
        ),
        ItemTemplate(
            "drivers_license", R.string.items_template_drivers_license, "identity_document", Sensitivity.DATA,
            listOf("identity", "driving"),
            listOf(
                f(R.string.items_label_number, FieldKinds.TEXT), f(R.string.items_label_full_name, FieldKinds.TEXT),
                f(R.string.items_label_classes, FieldKinds.TEXT), f(R.string.items_label_expires, FieldKinds.DATE),
                f(R.string.items_label_address, FieldKinds.ADDRESS),
            ),
        ),
        ItemTemplate(
            "national_id", R.string.items_template_national_id, "identity_document", Sensitivity.DATA, listOf("identity"),
            listOf(
                f(R.string.items_label_number, FieldKinds.TEXT), f(R.string.items_label_full_name, FieldKinds.TEXT),
                f(R.string.items_label_date_of_birth, FieldKinds.DATE), f(R.string.items_label_expires, FieldKinds.DATE),
            ),
        ),
        ItemTemplate(
            "login", R.string.items_template_login, "login", Sensitivity.SECRET, emptyList(),
            listOf(
                f(R.string.items_label_website, FieldKinds.URL), f(R.string.items_label_username, FieldKinds.TEXT),
                f(R.string.items_label_password, FieldKinds.PASSWORD), f(R.string.items_label_one_time_code, FieldKinds.OTP),
            ),
        ),
        ItemTemplate(
            "wifi", R.string.items_template_wifi, "login", Sensitivity.SECRET, listOf("home"),
            listOf(f(R.string.items_label_network_name, FieldKinds.TEXT), f(R.string.items_label_password, FieldKinds.PASSWORD)),
        ),
        ItemTemplate(
            "payment_card", R.string.items_template_payment_card, "payment_card", Sensitivity.SECRET, listOf("money"),
            listOf(
                f(R.string.items_label_cardholder, FieldKinds.TEXT), f(R.string.items_label_number, FieldKinds.TEXT),
                // The registry's `"format": "month"` (VAULT-ITEMS 0.1.2): a card expires in a month, stored `YYYY-MM`.
                f(R.string.items_label_expires, FieldKinds.DATE, format = TemplateField.FORMAT_MONTH),
                f(R.string.items_label_security_code, FieldKinds.PASSWORD),
                f(R.string.items_label_pin, FieldKinds.PASSWORD),
            ),
        ),
        ItemTemplate(
            "bank_account", R.string.items_template_bank_account, "bank_account", Sensitivity.SECRET, listOf("money"),
            listOf(
                f(R.string.items_label_bank, FieldKinds.TEXT), f(R.string.items_label_account_holder, FieldKinds.TEXT),
                f(R.string.items_label_iban, FieldKinds.TEXT), f(R.string.items_label_bic, FieldKinds.TEXT),
                f(R.string.items_label_online_banking, FieldKinds.URL),
            ),
        ),
        ItemTemplate(
            "allergies", R.string.items_template_allergies, "medical", Sensitivity.DATA, listOf("medical"),
            listOf(f(R.string.items_label_allergies, FieldKinds.MULTILINE), f(R.string.items_label_reactions, FieldKinds.MULTILINE)),
        ),
        ItemTemplate(
            "medications", R.string.items_template_medications, "medical", Sensitivity.DATA, listOf("medical"),
            listOf(f(R.string.items_label_medications, FieldKinds.MULTILINE), f(R.string.items_label_prescribed_by, FieldKinds.TEXT)),
        ),
        ItemTemplate(
            "blood_type", R.string.items_template_blood_type, "medical", Sensitivity.DATA, listOf("medical"),
            listOf(f(R.string.items_label_blood_type, FieldKinds.TEXT)),
        ),
        ItemTemplate(
            "insurance_card", R.string.items_template_insurance_card, "insurance", Sensitivity.DATA, listOf("insurance"),
            listOf(
                f(R.string.items_label_insurer, FieldKinds.TEXT), f(R.string.items_label_policy_number, FieldKinds.TEXT),
                f(R.string.items_label_member_number, FieldKinds.TEXT), f(R.string.items_label_valid_until, FieldKinds.DATE),
                f(R.string.items_label_phone, FieldKinds.PHONE),
            ),
        ),
        ItemTemplate(
            "vehicle", R.string.items_template_vehicle, "vehicle", Sensitivity.DATA, listOf("vehicle"),
            listOf(
                f(R.string.items_label_make_model, FieldKinds.TEXT), f(R.string.items_label_registration, FieldKinds.TEXT),
                f(R.string.items_label_vin, FieldKinds.TEXT), f(R.string.items_label_insurance_policy, FieldKinds.TEXT),
                f(R.string.items_label_next_inspection, FieldKinds.DATE),
            ),
        ),
        // Contact information is one item per contact point, so each can be shared on its own; no template adds a
        // reserved tag (VAULT-ITEMS 0.1.1, owner decision 2026-10-08). Items made from the former `contact_card` keep
        // their template id and are shown and edited as any other item.
        ItemTemplate(
            "email_address", R.string.items_template_email_address, "contact", Sensitivity.DATA, emptyList(),
            listOf(f(R.string.items_label_email, FieldKinds.EMAIL)),
        ),
        ItemTemplate(
            "phone_number", R.string.items_template_phone_number, "contact", Sensitivity.DATA, emptyList(),
            listOf(f(R.string.items_label_phone, FieldKinds.PHONE)),
        ),
        ItemTemplate(
            "postal_address", R.string.items_template_postal_address, "contact", Sensitivity.DATA, emptyList(),
            listOf(f(R.string.items_label_address, FieldKinds.ADDRESS)),
        ),
        ItemTemplate(
            "website", R.string.items_template_website, "contact", Sensitivity.DATA, emptyList(),
            listOf(f(R.string.items_label_website, FieldKinds.URL)),
        ),
        ItemTemplate(
            "emergency_contact", R.string.items_template_emergency_contact, "contact", Sensitivity.DATA, listOf("medical"),
            listOf(
                f(R.string.items_label_name, FieldKinds.TEXT), f(R.string.items_label_relationship, FieldKinds.TEXT),
                f(R.string.items_label_phone, FieldKinds.PHONE),
            ),
        ),
        ItemTemplate(
            "note", R.string.items_template_note, "note", Sensitivity.DATA, emptyList(),
            listOf(f(R.string.items_label_text, FieldKinds.MULTILINE)),
        ),
        ItemTemplate(
            "secure_note", R.string.items_template_secure_note, "note", Sensitivity.SECRET, emptyList(),
            listOf(f(R.string.items_label_text, FieldKinds.MULTILINE)),
        ),
        ItemTemplate(
            "recovery_phrase", R.string.items_template_recovery_phrase, "crypto_wallet", Sensitivity.CRITICAL, listOf("crypto"),
            listOf(
                f(R.string.items_label_wallet, FieldKinds.TEXT), f(R.string.items_label_words, FieldKinds.MULTILINE),
                f(R.string.items_label_passphrase, FieldKinds.PASSWORD),
            ),
        ),
        ItemTemplate(
            "signing_key", R.string.items_template_signing_key, "crypto_wallet", Sensitivity.CRITICAL, listOf("crypto"),
            listOf(f(R.string.items_label_signing_key, FieldKinds.PASSWORD), f(R.string.items_label_public_key, FieldKinds.TEXT)),
        ),
        ItemTemplate(
            "recovery_codes", R.string.items_template_recovery_codes, "login", Sensitivity.CRITICAL, emptyList(),
            listOf(f(R.string.items_label_service, FieldKinds.URL), f(R.string.items_label_codes, FieldKinds.MULTILINE)),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun template(id: String): ItemTemplate? = byId[id]

    /**
     * A blank item: a name, in the category the member picks, and no field yet. Each field is added through "Add a
     * field", which asks what it is called (a pre-made "Text" field read as "Text" twice: caption and type).
     */
    fun blank(): ItemDraft = ItemDraft()

    /**
     * An item's draft with its template's entry hints (§10.7, 0.23.0): a stored `date` is a month when its value is
     * one (`YYYY-MM`, read from the value's form by [ItemDraft.of]) and a day otherwise, whatever the template says;
     * an empty `date` takes the `"format": "month"` hint of its template's field of the same label.
     */
    @Suppress("ReturnCount")
    fun withHints(d: ItemDraft, context: Context): ItemDraft {
        val t = d.template?.let { template(it) } ?: return d
        val monthLabels = monthLabels(t, context)
        if (monthLabels.isEmpty()) return d
        return d.copy(fields = d.fields.map { f -> if (monthHint(f.kind, f.text, f.label, monthLabels)) f.copy(monthYear = true) else f })
    }

    /** The labels (in the app's language) of [t]'s `date` fields with `"format": "month"`. */
    fun monthLabels(t: ItemTemplate, context: Context): Set<String> =
        t.fields.filter { it.monthYear }.map { context.getString(it.label) }.toSet()

    /** Whether an empty `date` field labelled [label] takes the template's month hint (a stored value decides by its form). */
    fun monthHint(kind: String, text: String, label: String, monthLabels: Set<String>): Boolean =
        kind == FieldKinds.DATE && text.isEmpty() && label in monthLabels
}
