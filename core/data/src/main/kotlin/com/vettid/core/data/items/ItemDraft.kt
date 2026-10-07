package com.vettid.core.data.items

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.item.ItemSpec
import com.vettid.core.crypto.json.JsonBuilder

/** One field being edited: an existing field ([fieldId] set) or a new one. Address kinds use [address], the rest [text]. */
data class DraftField(
    val fieldId: String? = null,
    val label: String,
    val kind: String,
    val text: String = "",
    val address: AddressValue = AddressValue(),
) {
    val value: FieldValue get() = if (kind == FieldKinds.ADDRESS) FieldValue.Address(address) else FieldValue.Text(text)

    override fun toString(): String = "DraftField($fieldId, $kind)"

    companion object {
        fun of(f: ItemFieldView): DraftField = when (val v = f.value) {
            is FieldValue.Address -> DraftField(f.fieldId, f.label, f.kind, address = v.address)
            is FieldValue.Text -> DraftField(f.fieldId, f.label, f.kind, text = v.text)
            null -> DraftField(f.fieldId, f.label, f.kind)
        }
    }
}

/**
 * An item as the member edits it (§10.7): created from a template or blank, or from an item's revealed content.
 * [tags] are as typed; [ItemChecks] normalises them.
 */
data class ItemDraft(
    val name: String = "",
    val category: String = "other",
    val template: String? = null,
    val sensitivity: Sensitivity = Sensitivity.DATA,
    val tags: List<String> = emptyList(),
    val fields: List<DraftField> = emptyList(),
    val notes: String = "",
) {
    override fun toString(): String = "ItemDraft(${sensitivity.wire}, ${fields.size} fields)"

    companion object {
        /** The draft of an item whose values are at hand (a `data` item, or a revealed one). */
        fun of(d: ItemDetail): ItemDraft = ItemDraft(
            name = d.name,
            category = d.category,
            template = d.template,
            sensitivity = d.sensitivity,
            tags = d.tags,
            fields = d.fields.map(DraftField::of),
            notes = d.notes.orEmpty(),
        )
    }
}

/** What is wrong with a draft, before anything is sent (the vault checks the same, §10.7; it stays the authority). */
enum class DraftProblem {
    NAME_EMPTY,
    NAME_TOO_LONG,
    BAD_CHARACTER,
    CATEGORY_INVALID,
    LABEL_EMPTY,
    LABEL_TOO_LONG,
    VALUE_INVALID,
    VALUE_TOO_LONG,
    NOTES_TOO_LONG,
    TOO_MANY_FIELDS,
    TOO_MANY_TAGS,
    TAG_INVALID,

    /** `@profile` is allowed only on `data` items (§10.8). */
    PROFILE_NOT_DATA,

    /** The item's encoding would exceed 65,536 bytes (12,288 for a critical item). */
    TOO_LARGE,
}

/**
 * A draft's check: [problems] of the item, [fieldProblems] by field index, the normalised [tags] (null when a tag
 * is invalid), and the item's [size] as the vault would encode it (an upper bound).
 */
data class DraftCheck(
    val problems: Set<DraftProblem> = emptySet(),
    val fieldProblems: Map<Int, Set<DraftProblem>> = emptyMap(),
    val tags: List<String>? = emptyList(),
    val size: Int = 0,
    val maxSize: Int = ItemSpec.MAX_ITEM_BYTES,
) {
    val ok: Boolean get() = problems.isEmpty() && fieldProblems.isEmpty()
}

/** The checks of §10.7 and §10.8 on what the member typed, and the size of the item it would become. */
@Suppress("TooManyFunctions")
object ItemChecks {
    const val MAX_NAME = ItemSpec.MAX_NAME
    const val MAX_LABEL = ItemSpec.MAX_LABEL
    const val MAX_VALUE = ItemSpec.MAX_VALUE
    const val MAX_NOTES = ItemSpec.MAX_NOTES
    const val MAX_FIELDS = ItemSpec.MAX_FIELDS
    const val MAX_TAGS = ItemSpec.MAX_TAGS
    const val MAX_ITEM_BYTES = ItemSpec.MAX_ITEM_BYTES
    const val MAX_CRITICAL_BYTES = ItemSpec.MAX_CRIT_BYTES
    const val MAX_ADDRESS_PART = ItemSpec.MAX_ADDRESS_PART
    const val PROFILE_TAG = ItemSpec.PROFILE_TAG

    /** A vault holds at most 2,000 items, critical ones included, and at most 1,000 critical items (§10.7). */
    const val MAX_ITEMS = 2_000
    const val MAX_CRITICAL_ITEMS = 1_000

    /** At most 32 items carry `@profile` (§10.8). */
    const val MAX_PROFILE_ITEMS = 32

    val RECOMMENDED_CATEGORIES: List<String> get() = ItemSpec.RECOMMENDED_CATEGORIES

    private val countryRe = Regex("^[A-Z]{2}$")

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    fun isValidCategory(s: String): Boolean = ItemSpec.isValidCategory(s)

    /** The tag as the vault stores it (§10.8: trimmed, lower case, single spaces), or null if it is not one; `@profile` is reserved. */
    fun normalizeTag(s: String, reserved: Boolean = true): String? = try {
        ItemSpec.normalizeTag(s, reserved)
    } catch (_: CryptoException) {
        null
    }

    /** Normalised, sorted and without duplicates; null if any tag is invalid or there are more than 16. */
    fun normalizeTags(tags: List<String>): List<String>? {
        val n = tags.filter { it.isNotBlank() }.map { normalizeTag(it) ?: return null }.distinct().sorted()
        return n.takeIf { it.size <= MAX_TAGS }
    }

    /** The problems of one value of [kind] (an address checks each member). */
    @Suppress("ReturnCount")
    fun valueProblems(kind: String, v: FieldValue): Set<DraftProblem> {
        if (v is FieldValue.Address || kind == FieldKinds.ADDRESS) {
            val a = (v as? FieldValue.Address)?.address ?: return setOf(DraftProblem.VALUE_INVALID)
            val out = mutableSetOf<DraftProblem>()
            for ((k, s) in a.parts()) {
                if (bytes(s) > MAX_ADDRESS_PART) out += DraftProblem.VALUE_TOO_LONG
                if (!ItemSpec.isCleanText(s, false)) out += DraftProblem.BAD_CHARACTER
                if (k == "country" && s.isNotEmpty() && !countryRe.matches(s)) out += DraftProblem.VALUE_INVALID
            }
            return out
        }
        val s = (v as FieldValue.Text).text
        if (bytes(s) > MAX_VALUE) return setOf(DraftProblem.VALUE_TOO_LONG)
        if (!ItemSpec.isCleanText(s, FieldKinds.multiline(kind))) return setOf(DraftProblem.BAD_CHARACTER)
        // A kind this app does not know is kept as the member typed it; the vault decides.
        if (!ItemSpec.isValidKind(kind)) return emptySet()
        return if (ItemSpec.isValidStringValue(kind, s)) emptySet() else setOf(DraftProblem.VALUE_INVALID)
    }

    fun labelProblems(label: String): Set<DraftProblem> = when {
        label.isEmpty() -> setOf(DraftProblem.LABEL_EMPTY)
        bytes(label) > MAX_LABEL -> setOf(DraftProblem.LABEL_TOO_LONG)
        !ItemSpec.isCleanText(label, false) -> setOf(DraftProblem.BAD_CHARACTER)
        else -> emptySet()
    }

    /** Everything the vault would refuse about [d] (§10.7, §10.8), and its size. */
    @Suppress("CyclomaticComplexMethod")
    fun check(d: ItemDraft): DraftCheck {
        val problems = mutableSetOf<DraftProblem>()
        val name = d.name.trim()
        when {
            name.isEmpty() -> problems += DraftProblem.NAME_EMPTY
            bytes(name) > MAX_NAME -> problems += DraftProblem.NAME_TOO_LONG
            !ItemSpec.isCleanText(name, false) -> problems += DraftProblem.BAD_CHARACTER
        }
        if (!isValidCategory(d.category)) problems += DraftProblem.CATEGORY_INVALID
        if (bytes(d.notes) > MAX_NOTES) {
            problems += DraftProblem.NOTES_TOO_LONG
        } else if (!ItemSpec.isCleanText(d.notes, true)) {
            problems += DraftProblem.BAD_CHARACTER
        }
        if (d.fields.size > MAX_FIELDS) problems += DraftProblem.TOO_MANY_FIELDS
        val tags = normalizeTags(d.tags)
        when {
            tags == null && d.tags.count { it.isNotBlank() } > MAX_TAGS -> problems += DraftProblem.TOO_MANY_TAGS
            tags == null -> problems += DraftProblem.TAG_INVALID
            PROFILE_TAG in tags && d.sensitivity != Sensitivity.DATA -> problems += DraftProblem.PROFILE_NOT_DATA
        }
        val fieldProblems = d.fields.mapIndexedNotNull { i, f ->
            (labelProblems(f.label.trim()) + valueProblems(f.kind, f.value)).takeIf { it.isNotEmpty() }?.let { i to it }
        }.toMap()
        val max = if (d.sensitivity == Sensitivity.CRITICAL) MAX_CRITICAL_BYTES else MAX_ITEM_BYTES
        val size = size(d, tags ?: emptyList())
        if (size > max) problems += DraftProblem.TOO_LARGE
        return DraftCheck(problems, fieldProblems, tags, size, max)
    }

    /**
     * An upper bound of the item's encoding as `item.get` gives it with every value (§10.7 "Size"; the reference's
     * `Item.JSON(true)`): the longest id, version, field ids and timestamps the vault could give it.
     */
    fun size(d: ItemDraft, tags: List<String>): Int {
        val b = JsonBuilder().string("item_id", ULID_SIZED).raw("version", MAX_VERSION).string("name", d.name.trim())
            .string("category", d.category).string("sensitivity", d.sensitivity.wire)
        d.template?.let { b.string("template", it) }
        b.raw("tags", JsonBuilder.array(tags.map { JsonBuilder.quote(it) }))
        b.raw(
            "fields",
            JsonBuilder.array(
                d.fields.map { f ->
                    JsonBuilder().string("field_id", f.fieldId ?: FIELD_ID_SIZED).string("label", f.label.trim()).string("kind", f.kind)
                        .raw("value", valueJson(f.value)).build()
                },
            ),
        )
        if (d.notes.isNotEmpty()) b.string("notes", d.notes)
        b.string("created_at", TS_SIZED).string("updated_at", TS_SIZED)
        return b.bytes().size
    }

    /** A value's JSON as the vault keeps it: a string, or an address object with its non-empty members in fixed order. */
    fun valueJson(v: FieldValue): String = when (v) {
        is FieldValue.Text -> JsonBuilder.quote(v.text)
        is FieldValue.Address -> JsonBuilder().also { b ->
            v.address.parts().filter { it.second.isNotEmpty() }.forEach { (k, s) -> b.string(k, s) }
        }.build()
    }

    private const val ULID_SIZED = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
    private const val MAX_VERSION = "9007199254740991"
    private const val FIELD_ID_SIZED = "f9999999"
    private const val TS_SIZED = "2026-10-07T12:34:56.789123456Z"
}
