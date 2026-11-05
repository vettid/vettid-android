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

    private val STOP = setOf("data", "from", "contributors", "©", "(c)", "and", "-", "·", "|")

    /**
     * What the map shows: the [fixed] credit always, first, then whatever the server's own [server] credit adds to it (its words
     * that [fixed] does not already have), capped at [MAX]. A server credit that repeats the same names adds nothing.
     */
    fun combine(fixed: String, server: String): String {
        val have = fixed.lowercase().split(' ').toSet() + STOP
        val extra = server.split(' ').filter { it.isNotBlank() && it.lowercase() !in have }.joinToString(" ")
        return (if (extra.isEmpty()) fixed else "$fixed $extra").take(MAX)
    }

    private val WHITESPACE = Regex("\\s+")
}
