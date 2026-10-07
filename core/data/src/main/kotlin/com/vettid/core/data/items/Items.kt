package com.vettid.core.data.items

import java.time.Instant

/**
 * Where the vault keeps an item (VAULT-MESSAGING §10.7, VAULT-ITEMS §4): [DATA] in DEK state, values in `item.get`;
 * [SECRET] in DEK state, values only through `item.reveal` (audited); [CRITICAL] inside the Protean Credential, every
 * read or change a credential operation with the password. The interface calls the three "Standard", "Secret" and
 * "Critical".
 */
enum class Sensitivity(val wire: String) {
    DATA("data"),
    SECRET("secret"),
    CRITICAL("critical"),
    ;

    companion object {
        /** An unknown value (a newer vault) is treated as [SECRET]: its values stay hidden until revealed on purpose. */
        fun of(wire: String?): Sensitivity = entries.firstOrNull { it.wire == wire } ?: if (wire == null) DATA else SECRET
    }
}

/** The field kinds of §10.7; `file` is reserved (files later) and refused by the vault. */
object FieldKinds {
    const val TEXT = "text"
    const val MULTILINE = "multiline"
    const val NUMBER = "number"
    const val DATE = "date"
    const val EMAIL = "email"
    const val PHONE = "phone"
    const val URL = "url"
    const val PASSWORD = "password"
    const val OTP = "otp"
    const val ADDRESS = "address"

    /** The kinds a member can choose, in the order the add-field menu offers them. */
    val CHOOSABLE = listOf(TEXT, MULTILINE, PASSWORD, NUMBER, DATE, EMAIL, PHONE, URL, OTP, ADDRESS)

    /** Kinds the apps mask and reveal on purpose (§10.7: `password`; an `otp` seed is a secret too). */
    fun masked(kind: String): Boolean = kind == PASSWORD || kind == OTP

    /** Kinds whose value may span lines. */
    fun multiline(kind: String): Boolean = kind == MULTILINE || kind == PASSWORD
}

/** An `address` value (§10.7): members of at most 256 bytes; `country` an ISO 3166-1 alpha-2 code in upper case. */
data class AddressValue(
    val street: String = "",
    val street2: String = "",
    val city: String = "",
    val region: String = "",
    val postalCode: String = "",
    val country: String = "",
) {
    val isEmpty: Boolean get() = parts().all { it.second.isEmpty() }

    /** The members in the order the vault encodes them, with their wire names. */
    fun parts(): List<Pair<String, String>> = listOf(
        "street" to street, "street2" to street2, "city" to city, "region" to region, "postal_code" to postalCode, "country" to country,
    )

    /** For display: the non-empty lines (city line joins postal code, city and region). */
    fun lines(): List<String> = listOfNotNull(
        street.takeIf { it.isNotBlank() },
        street2.takeIf { it.isNotBlank() },
        listOf(postalCode, city, region).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() },
        country.takeIf { it.isNotBlank() },
    )

    companion object {
        fun of(parts: Map<String, String>): AddressValue = AddressValue(
            street = parts["street"].orEmpty(),
            street2 = parts["street2"].orEmpty(),
            city = parts["city"].orEmpty(),
            region = parts["region"].orEmpty(),
            postalCode = parts["postal_code"].orEmpty(),
            country = parts["country"].orEmpty(),
        )
    }
}

/** A field's value: a string (every kind but `address`) or an address. */
sealed interface FieldValue {
    data class Text(val text: String) : FieldValue {
        override fun toString(): String = "Text(…)"
    }

    data class Address(val address: AddressValue) : FieldValue {
        override fun toString(): String = "Address(…)"
    }

    val isEmpty: Boolean
        get() = when (this) {
            is Text -> text.isEmpty()
            is Address -> address.isEmpty
        }
}

/** One field as the app shows it. [value] null: not revealed (a `secret` or `critical` item's values, §10.7). */
data class ItemFieldView(val fieldId: String?, val label: String, val kind: String, val value: FieldValue? = null)

/** An item in the list (`item.list`: metadata, never values or notes). */
data class ItemSummary(
    val itemId: String,
    val version: Long,
    val name: String,
    val category: String,
    val sensitivity: Sensitivity,
    val template: String? = null,
    val tags: List<String> = emptyList(),
    /** The fields' labels, in order (shown as the row's second line). */
    val labels: List<String> = emptyList(),
    val updatedAt: Instant? = null,
)

/**
 * An item with its fields (§10.7). A `data` item comes with its values; a `secret` or `critical` item without them
 * ([revealed] false, every [ItemFieldView.value] null, [notes] null and [hasNotes] telling whether there are notes)
 * until the member reveals it on purpose.
 */
data class ItemDetail(
    val itemId: String,
    val version: Long,
    val name: String,
    val category: String,
    val sensitivity: Sensitivity,
    val template: String? = null,
    val tags: List<String> = emptyList(),
    val fields: List<ItemFieldView> = emptyList(),
    val notes: String? = null,
    val hasNotes: Boolean = false,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val revealed: Boolean = sensitivity == Sensitivity.DATA,
) {
    val summary: ItemSummary
        get() = ItemSummary(itemId, version, name, category, sensitivity, template, tags, fields.map { it.label }, updatedAt)

    /** The same item without its values, as `item.get` gives a secret or critical one (what the screen keeps when it hides them). */
    fun hidden(): ItemDetail = if (sensitivity == Sensitivity.DATA) {
        this
    } else {
        copy(fields = fields.map { it.copy(value = null) }, notes = null, hasNotes = hasNotes || !notes.isNullOrEmpty(), revealed = false)
    }

    override fun toString(): String = "ItemDetail($itemId, v$version, ${sensitivity.wire}, revealed=$revealed)"
}

/** The member's choice of what the Vault list shows (all on this phone: the list holds every item's metadata). */
data class ItemFilter(
    val query: String = "",
    val tag: String? = null,
    val category: String? = null,
    val sensitivity: Sensitivity? = null,
) {
    val isEmpty: Boolean get() = query.isBlank() && tag == null && category == null && sensitivity == null

    fun matches(i: ItemSummary): Boolean {
        val q = query.trim()
        return (tag == null || tag in i.tags) &&
            (category == null || i.category == category) &&
            (sensitivity == null || i.sensitivity == sensitivity) &&
            (q.isEmpty() || i.name.contains(q, ignoreCase = true) || i.tags.any { it.contains(q, ignoreCase = true) })
    }

    /** The matching items, by name (case-insensitive), then id. */
    fun apply(items: List<ItemSummary>): List<ItemSummary> =
        items.filter(::matches).sortedWith(compareBy({ it.name.lowercase() }, { it.itemId }))
}
