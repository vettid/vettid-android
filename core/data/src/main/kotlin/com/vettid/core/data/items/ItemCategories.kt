package com.vettid.core.data.items

import com.vettid.core.crypto.item.ItemSpec
import java.text.Normalizer
import java.util.Locale

/**
 * Member-defined categories (VAULT-MESSAGING §10.7: any `[a-z][a-z0-9_]{0,31}`; the recommended list is only a
 * suggestion). The member types a name ("Loyalty cards"); the app derives the identifier (`loyalty_cards`) and shows a
 * custom one humanized from it.
 */
object ItemCategories {
    const val MAX_ID = 32

    /** Why no identifier could be derived from a name. */
    enum class Problem {
        /** Nothing usable is left: no letter or digit of a-z, 0-9 (after removing accents). */
        EMPTY,

        /** The identifier would start with a digit; a category starts with a letter. */
        STARTS_WITH_DIGIT,
    }

    /** The identifier a name gives ([id]), or why it gives none ([problem]). */
    data class Derived(val id: String?, val problem: Problem?) {
        /** Whether [id] is one of the recommended categories (choosing it selects that one). */
        val recommended: Boolean get() = id != null && id in ItemSpec.RECOMMENDED_CATEGORIES
    }

    private val combining = Regex("\\p{Mn}+")
    private val separators = Regex("[\\s-]+")
    private val disallowed = Regex("[^a-z0-9_]")
    private val underscores = Regex("_+")

    /**
     * The identifier of a typed category name: accents removed, lower case, spaces and hyphens become `_`, other
     * characters are dropped, runs of `_` collapse, at most [MAX_ID] characters; it must start with a letter.
     */
    fun derive(name: String): Derived {
        val plain = combining.replace(Normalizer.normalize(name.trim(), Normalizer.Form.NFD), "")
        val id = plain.lowercase(Locale.ROOT)
            .replace("ß", "ss")
            .replace(separators, "_")
            .replace(disallowed, "")
            .replace(underscores, "_")
            .trim('_')
            .take(MAX_ID)
            .trimEnd('_')
        return when {
            id.isEmpty() -> Derived(null, Problem.EMPTY)
            !id[0].isLetter() -> Derived(null, Problem.STARTS_WITH_DIGIT)
            ItemSpec.isValidCategory(id) -> Derived(id, null)
            else -> Derived(null, Problem.EMPTY)
        }
    }

    /** A custom category as shown: `loyalty_cards` → "Loyalty cards". */
    fun humanize(id: String): String = id.replace('_', ' ').trim().replaceFirstChar { it.titlecase(Locale.ROOT) }

    fun isRecommended(id: String): Boolean = id in ItemSpec.RECOMMENDED_CATEGORIES

    /** The member's own categories among [categories] (valid, not recommended), distinct and sorted. */
    fun customs(categories: Iterable<String>): List<String> =
        categories.filter { ItemSpec.isValidCategory(it) && !isRecommended(it) }.distinct().sorted()
}
