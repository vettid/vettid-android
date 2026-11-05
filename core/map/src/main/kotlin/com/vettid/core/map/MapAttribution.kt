package com.vettid.core.map

import androidx.core.text.HtmlCompat

/** The credit shown on the map: the style's sources' own text, entities decoded, tags removed, at most [MAX] characters. */
object MapAttribution {
    const val MAX = 200

    fun clean(raw: List<String?>): String =
        raw.filterNotNull()
            .map { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
            .take(MAX)

    private val WHITESPACE = Regex("\\s+")
}
