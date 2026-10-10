package com.vettid.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** A tag chip's fill and the text and icons on it. */
@Immutable
data class TagColor(val container: Color, val onContainer: Color)

/**
 * Tag colours (owner decisions of 2026-10-09): every tag has one colour, the same everywhere and on every device. The
 * colour is the one stored in the vault's tag registry (VAULT-MESSAGING §10.8 `color`, `#rrggbb`): a palette colour
 * stored as its light fill ([stored]) shows as its pair in each theme, any other `#rrggbb` (a future desktop's) as is,
 * with black or white text, whichever reads better. A tag with none is given the least-used palette colour ([next]),
 * which the app stores; until then it shows its hash colour (FNV-1a of the normalised name, [index]). The palette is
 * ten clearly different hues, as pairs that meet WCAG AA for text in each theme (`TagColorsTest`); none is gold, which
 * stays the member's own: the reserved `@profile` tag ("Shared profile") always takes the gold of the member's avatar
 * and never has a colour stored.
 */
@Suppress("MagicNumber")
object TagColors {
    /** The reserved tag of the member's shared profile (VAULT-ITEMS §10.8, `ItemChecks.PROFILE_TAG`). */
    const val OWN_TAG = "@profile"

    /** The palette's hues, by slot (for content descriptions and tests). */
    val names: List<String> = listOf("red", "orange", "green", "teal", "blue", "indigo", "violet", "pink", "brown", "slate")

    /**
     * Light theme: mid-light fills with deep text of the same hue. Order is part of the hash fallback's mapping and
     * the least-used tie-break: never reorder (stored colours do not depend on it).
     */
    val light: List<TagColor> = listOf(
        TagColor(Color(0xFFF88D96), Color(0xFF42151B)), // red
        TagColor(Color(0xFFFEA669), Color(0xFF3E1C01)), // orange
        TagColor(Color(0xFF99D079), Color(0xFF162E06)), // green
        TagColor(Color(0xFF37CCAC), Color(0xFF012E25)), // teal
        TagColor(Color(0xFF59BBFB), Color(0xFF022A41)), // blue
        TagColor(Color(0xFFB2CAFE), Color(0xFF152448)), // indigo
        TagColor(Color(0xFFB0A2FE), Color(0xFF271F46)), // violet
        TagColor(Color(0xFFED9EE5), Color(0xFF391836)), // pink
        TagColor(Color(0xFFE3C2B7), Color(0xFF371F17)), // brown
        TagColor(Color(0xFFA6B3C1), Color(0xFF1D2732)), // slate
    )

    /** Dark theme: the same hues, deep fills with pale text. */
    val dark: List<TagColor> = listOf(
        TagColor(Color(0xFF8C2B3B), Color(0xFFFEE8E9)), // red
        TagColor(Color(0xFF602D00), Color(0xFFFEEADD)), // orange
        TagColor(Color(0xFF224801), Color(0xFFE6F3DF)), // green
        TagColor(Color(0xFF036150), Color(0xFFDBF6ED)), // teal
        TagColor(Color(0xFF025884), Color(0xFFE0F1FF)), // blue
        TagColor(Color(0xFF1A3680), Color(0xFFE7EFFE)), // indigo
        TagColor(Color(0xFF5E4DA1), Color(0xFFEEECFE)), // violet
        TagColor(Color(0xFF783274), Color(0xFFFBE8F8)), // pink
        TagColor(Color(0xFF72564D), Color(0xFFFFE9E2)), // brown
        TagColor(Color(0xFF333E4A), Color(0xFFE3F0FE)), // slate
    )

    /** What the registry stores for each slot (§10.8 `#rrggbb`, lower case): the light theme's fill. */
    val stored: List<String> = light.map { hex(it.container) }

    /** The member's own colour (the avatar's gold), for [OWN_TAG]. */
    val own: TagColor = TagColor(VettIdPalette.Gold, VettIdPalette.Ink)

    /** Text on a fill outside the palette: black or white, whichever has the higher contrast. */
    private val inkDark = Color(0xFF000000)
    private val inkLight = Color(0xFFFFFFFF)

    /**
     * The tag as hashed, normalised as VAULT-ITEMS §10.7 does (`ItemSpec.normalizeTag`): outer spaces trimmed, runs
     * of spaces made one, ASCII letters lower case.
     */
    fun normalize(tag: String): String {
        val sb = StringBuilder()
        var space = false
        for (c in tag.trim(' ')) {
            if (c == ' ') {
                if (!space) sb.append(c)
                space = true
                continue
            }
            space = false
            sb.append(if (c in 'A'..'Z') c + ('a' - 'A') else c)
        }
        return sb.toString()
    }

    /** 32-bit FNV-1a over the UTF-8 bytes of [normalize]d [tag], as an unsigned value. */
    fun hash(tag: String): Long {
        var h = FNV_OFFSET
        for (b in normalize(tag).toByteArray(Charsets.UTF_8)) {
            h = ((h xor (b.toLong() and BYTE)) * FNV_PRIME) and MASK
        }
        return h
    }

    /** The hash slot of [tag]: [hash] modulo the palette size; -1 for [OWN_TAG]. */
    fun index(tag: String): Int = if (isOwn(tag)) -1 else (hash(tag) % light.size).toInt()

    fun isOwn(tag: String): Boolean = normalize(tag) == OWN_TAG

    /** Exactly `#` and six hex digits (either case) parsed, opaque; null for anything else. */
    fun parse(color: String?): Color? {
        val digits = color?.takeIf { it.length == HEX_LENGTH && it[0] == '#' }?.substring(1)
        return digits?.takeIf { d -> d.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }
            ?.let { Color(OPAQUE or it.toLong(HEX_RADIX)) }
    }

    /** The palette slot of a stored [color] (any case); -1 when it is not a palette colour. */
    fun slotOf(color: String?): Int = color?.let { stored.indexOf(it.lowercase()) } ?: -1

    /** The colours of [tag] in a theme; [stored] is the registry's colour for it, if any. */
    fun of(tag: String, dark: Boolean, stored: String? = null): TagColor {
        val palette = if (dark) this.dark else light
        val other = parse(stored)
        return when {
            isOwn(tag) -> own
            slotOf(stored) >= 0 -> palette[slotOf(stored)]
            other != null -> TagColor(other, readableOn(other))
            else -> palette[index(tag)]
        }
    }

    /** The palette slot [tag] shows with [stored] (its stored one, else its hash slot); -1 for a colour outside it or [OWN_TAG]. */
    fun shownSlot(tag: String, stored: String?): Int = when {
        isOwn(tag) -> -1
        slotOf(stored) >= 0 -> slotOf(stored)
        parse(stored) != null -> -1
        else -> index(tag)
    }

    /** Black or white on [fill], whichever contrasts more. */
    fun readableOn(fill: Color): Color = if (contrastRatio(inkDark, fill) >= contrastRatio(inkLight, fill)) inkDark else inkLight

    /**
     * The next colour to store (owner decision 2026-10-09): of the member's [tags] (tag to stored colour) without a
     * colour, the first by normalised name, with the palette colour the fewest of their tags have (stored colours
     * outside the palette count for none); a tie goes to the tag's hash slot when it is among the least used, else to
     * the first of them in palette order. Null when every tag has a colour. `@` tags are never coloured. Applied one
     * at a time, in this order, two devices assigning at once pick the same colours.
     */
    fun next(tags: List<Pair<String, String?>>): Pair<String, String>? {
        val mine = tags.filterNot { it.first.startsWith("@") }
        val tag = mine.filter { parse(it.second) == null }.map { it.first }
            .minWithOrNull(compareBy<String> { normalize(it) }.thenBy { it }) ?: return null
        val counts = IntArray(light.size)
        mine.forEach { (_, c) -> slotOf(c).takeIf { it >= 0 }?.let { counts[it]++ } }
        val least = counts.min()
        val h = index(tag)
        return tag to stored[if (counts[h] == least) h else counts.indexOfFirst { it == least }]
    }

    private fun hex(c: Color): String = "#%06x".format(c.toArgb() and RGB)

    private const val FNV_OFFSET = 0x811C9DC5L
    private const val FNV_PRIME = 0x01000193L
    private const val MASK = 0xFFFFFFFFL
    private const val BYTE = 0xFFL
    private const val RGB = 0xFFFFFF
    private const val OPAQUE = 0xFF000000L
    private const val HEX_LENGTH = 7
    private const val HEX_RADIX = 16
}

/**
 * The colours the vault's tag registry stores (§10.8), by normalised tag; provided at the root of the UI from the
 * registry as last listed, empty until it is (tags then show their hash colours).
 */
val LocalTagColors = compositionLocalOf<Map<String, String>> { emptyMap() }

/** The colours of [tag] in the current theme; [stored] when the caller has the registry entry, else [LocalTagColors]. */
@Composable
@ReadOnlyComposable
fun tagColor(tag: String, stored: String? = null): TagColor =
    TagColors.of(tag, VettIdTheme.colors.isDark, stored ?: LocalTagColors.current[TagColors.normalize(tag)])
