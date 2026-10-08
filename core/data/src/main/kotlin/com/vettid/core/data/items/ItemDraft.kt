package com.vettid.core.data.items

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.item.ItemSpec
import com.vettid.core.crypto.json.JsonBuilder

/**
 * One field being edited: an existing field ([fieldId] set) or a new one. Address kinds use [address], the rest
 * [text]. [kept]: the field's stored value is not at hand (a `secret` or `critical` item, VAULT-MESSAGING 0.21.0
 * §10.7 Kept values) and the vault keeps it; the field is sent without `value` until the member types into it.
 */
data class DraftField(
    val fieldId: String? = null,
    val label: String,
    val kind: String,
    val text: String = "",
    val address: AddressValue = AddressValue(),
    val kept: Boolean = false,
    /**
     * How a `date` is entered, never sent: as a month and year (`YYYY-MM`, which §10.7 allows), e.g. a card's expiry,
     * instead of a day (`YYYY-MM-DD`).
     */
    val monthYear: Boolean = false,
) {
    val value: FieldValue get() = if (kind == FieldKinds.ADDRESS) FieldValue.Address(address) else FieldValue.Text(text)

    override fun toString(): String = "DraftField($fieldId, $kind${if (kept) ", kept" else ""})"

    companion object {
        fun of(f: ItemFieldView): DraftField = when (val v = f.value) {
            is FieldValue.Address -> DraftField(f.fieldId, f.label, f.kind, address = v.address)
            is FieldValue.Text -> DraftField(
                f.fieldId, f.label, f.kind, text = v.text,
                monthYear = f.kind == FieldKinds.DATE && v.text.length == YEAR_MONTH_LENGTH,
            )
            null -> DraftField(f.fieldId, f.label, f.kind, kept = f.fieldId != null)
        }

        /** `YYYY-MM`. */
        private const val YEAR_MONTH_LENGTH = 7
    }
}

/**
 * What an edit of an item without its values started from (§10.7 Kept values): the ids of the fields whose values
 * the vault keeps, whether it keeps notes, and the bytes those values and notes add to the item's size ([bytes]:
 * `item.get`'s `size` less the metadata's; null when the vault gave no `size`).
 */
data class KeptBase(val fieldIds: Set<String>, val notes: Boolean, val bytes: Int?)

/**
 * An item as the member edits it (§10.7): created from a template or blank, or from an item's content. A `data` item
 * (or a revealed one) is edited with its values; a `secret` or `critical` one with its stored values kept ([kept]
 * fields, [keepNotes]): editing never reveals them. [tags] are as typed; [ItemChecks] normalises them.
 */
data class ItemDraft(
    val name: String = "",
    val category: String = "other",
    val template: String? = null,
    val sensitivity: Sensitivity = Sensitivity.DATA,
    val tags: List<String> = emptyList(),
    val fields: List<DraftField> = emptyList(),
    val notes: String = "",
    /** The stored notes are kept (`keep_notes`, §10.7); [notes] is then empty. */
    val keepNotes: Boolean = false,
    val base: KeptBase? = null,
) {
    override fun toString(): String = "ItemDraft(${sensitivity.wire}, ${fields.size} fields)"

    /** Whether anything is sent as kept (a field without `value`, or `keep_notes`). */
    val keeps: Boolean get() = keepNotes || fields.any { it.kept }

    companion object {
        /**
         * The draft of an item: with its values when they are at hand (a `data` item, or a revealed one), else with
         * every stored value and the notes kept, and the bytes they add from the item's [ItemDetail.size].
         */
        fun of(d: ItemDetail): ItemDraft {
            val draft = ItemDraft(
                name = d.name,
                category = d.category,
                template = d.template,
                sensitivity = d.sensitivity,
                tags = d.tags,
                fields = d.fields.map(DraftField::of),
                notes = d.notes.orEmpty(),
                keepNotes = !d.revealed && d.hasNotes,
            )
            if (!draft.keeps) return draft
            val ids = draft.fields.filter { it.kept }.mapNotNull { it.fieldId }.toSet()
            val bytes = d.size?.let { (it - ItemChecks.visibleSize(draft, d.tags)).coerceAtLeast(0) }
            return draft.copy(base = KeptBase(ids, draft.keepNotes, bytes))
        }
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

    /** The item's size (§10.7 Size) would exceed 65,536 bytes (12,288 for a critical item). */
    TOO_LARGE,
}

/**
 * A draft's check: [problems] of the item, [fieldProblems] by field index, the normalised [tags] (null when a tag
 * is invalid), and the item's [size] as the vault counts it (§10.7 Size, 0.21.0). [exact] is false when kept values
 * are counted from the stored size but some of them left (removed or replaced fields, notes): [size] is then an upper
 * bound; [sizeKnown] is false when the vault gave no stored size (only the values at hand are counted).
 */
data class DraftCheck(
    val problems: Set<DraftProblem> = emptySet(),
    val fieldProblems: Map<Int, Set<DraftProblem>> = emptyMap(),
    val tags: List<String>? = emptyList(),
    val size: Int = 0,
    val maxSize: Int = ItemSpec.MAX_ITEM_BYTES,
    val exact: Boolean = true,
    val sizeKnown: Boolean = true,
) {
    val ok: Boolean get() = problems.isEmpty() && fieldProblems.isEmpty()

    /** The room left before the size limit; null when the size is not known. */
    val roomLeft: Int? get() = if (sizeKnown) maxSize - size else null
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

    /** The problems of one value of [kind] (an address checks each member; `file` is reserved, §10.7). */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun valueProblems(kind: String, v: FieldValue): Set<DraftProblem> {
        if (kind == FieldKinds.FILE) return setOf(DraftProblem.VALUE_INVALID)
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
            // A kept value was checked by the vault for its kind (§10.7 Kept values); a kept file field cannot exist.
            val value = if (f.kept && f.kind != FieldKinds.FILE) emptySet() else valueProblems(f.kind, f.value)
            (labelProblems(f.label.trim()) + value).takeIf { it.isNotEmpty() }?.let { i to it }
        }.toMap()
        val max = if (d.sensitivity == Sensitivity.CRITICAL) MAX_CRITICAL_BYTES else MAX_ITEM_BYTES
        val visible = visibleSize(d, tags ?: emptyList())
        val base = d.base
        val bytes = base?.bytes
        val (size, exact, known) = when {
            !d.keeps -> Triple(visible, true, true)
            bytes == null -> Triple(visible, false, false)
            else -> {
                val keptIds = d.fields.filter { it.kept }.mapNotNull { it.fieldId }.toSet()
                Triple(visible + bytes, keptIds == base.fieldIds && d.keepNotes == base.notes, true)
            }
        }
        // An inexact size is an upper bound: only what is certain (the values at hand) refuses the draft.
        if ((if (exact) size else visible) > max) problems += DraftProblem.TOO_LARGE
        return DraftCheck(problems, fieldProblems, tags, size, max, exact, known)
    }

    /**
     * The item's size as the vault counts it (§10.7 Size, 0.21.0): the length of its content encoding, without the
     * members the vault assigns (`item_id`, `version`, the times and the field ids). Exact for a draft that holds
     * every value; a kept value counts as empty here ([check] adds what the stored size says it holds).
     *
     * `{"name":…,"category":…,"sensitivity":…[,"template":…],"tags":[…],"fields":[{"label":…,"kind":…,"value":…},…][,"notes":…]}`
     */
    fun visibleSize(d: ItemDraft, tags: List<String>): Int {
        val b = JsonBuilder().string("name", d.name.trim()).string("category", d.category).string("sensitivity", d.sensitivity.wire)
        d.template?.let { b.string("template", it) }
        b.raw("tags", JsonBuilder.array(tags.map { JsonBuilder.quote(it) }))
        b.raw(
            "fields",
            JsonBuilder.array(
                d.fields.map { f ->
                    JsonBuilder().string("label", f.label.trim()).string("kind", f.kind).raw("value", valueJson(f.value)).build()
                },
            ),
        )
        if (!d.keepNotes && d.notes.isNotEmpty()) b.string("notes", d.notes)
        return b.bytes().size
    }

    /** A value's JSON as the vault keeps it: a string, or an address object with its non-empty members in fixed order. */
    fun valueJson(v: FieldValue): String = when (v) {
        is FieldValue.Text -> JsonBuilder.quote(v.text)
        is FieldValue.Address -> JsonBuilder().also { b ->
            v.address.parts().filter { it.second.isNotEmpty() }.forEach { (k, s) -> b.string(k, s) }
        }.build()
    }
}
