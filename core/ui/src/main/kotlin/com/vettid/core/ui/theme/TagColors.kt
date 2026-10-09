package com.vettid.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** A tag chip's fill and the text and icons on it. */
@Immutable
data class TagColor(val container: Color, val onContainer: Color)

/**
 * Tag colours (owner request 2026-10-09): every tag has one colour, the same everywhere and on every device, picked
 * by a stable hash of its normalised name from a fixed palette of ten (the member never picks one). The palettes are
 * pairs that meet WCAG AA for text in each theme (`TagColorsTest`); none is gold, which stays the member's own: the
 * reserved `@profile` tag ("Shared profile") always takes the gold of the member's avatar.
 */
object TagColors {
    /** The reserved tag of the member's shared profile (VAULT-ITEMS §10.8, `ItemChecks.PROFILE_TAG`). */
    const val OWN_TAG = "@profile"

    /** Light theme: pale fills with deep text of the same hue. Order is part of the mapping: never reorder. */
    val light: List<TagColor> = listOf(
        TagColor(Color(0xFFFFDAD6), Color(0xFF410002)), // red
        TagColor(Color(0xFFFFDBCB), Color(0xFF341100)), // rust
        TagColor(Color(0xFFE2E6A6), Color(0xFF1B1D00)), // olive
        TagColor(Color(0xFFC2EFC4), Color(0xFF00210A)), // green
        TagColor(Color(0xFFB3EDE3), Color(0xFF00201C)), // teal
        TagColor(Color(0xFFC5E7FF), Color(0xFF001E2D)), // sky
        TagColor(Color(0xFFD8E2FF), Color(0xFF001A41)), // blue
        TagColor(Color(0xFFE9DDFF), Color(0xFF22005D)), // violet
        TagColor(Color(0xFFFFD7F1), Color(0xFF3A0032)), // magenta
        TagColor(Color(0xFFDCE3EA), Color(0xFF151C22)), // slate
    )

    /** Dark theme: the same hues, deep fills with pale text. */
    val dark: List<TagColor> = listOf(
        TagColor(Color(0xFF8C1D18), Color(0xFFFFDAD6)), // red
        TagColor(Color(0xFF7A3000), Color(0xFFFFDBCB)), // rust
        TagColor(Color(0xFF4A4E00), Color(0xFFE2E6A6)), // olive
        TagColor(Color(0xFF1B5E2A), Color(0xFFC2EFC4)), // green
        TagColor(Color(0xFF005047), Color(0xFFB3EDE3)), // teal
        TagColor(Color(0xFF004C6D), Color(0xFFC5E7FF)), // sky
        TagColor(Color(0xFF1F438F), Color(0xFFD8E2FF)), // blue
        TagColor(Color(0xFF5328A8), Color(0xFFE9DDFF)), // violet
        TagColor(Color(0xFF7D1A6C), Color(0xFFFFD7F1)), // magenta
        TagColor(Color(0xFF3D4852), Color(0xFFDCE3EA)), // slate
    )

    /** The member's own colour (the avatar's gold), for [OWN_TAG]. */
    val own: TagColor = TagColor(VettIdPalette.Gold, VettIdPalette.Ink)

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

    /** The palette slot of [tag]: [hash] modulo the palette size; -1 for [OWN_TAG]. */
    fun index(tag: String): Int = if (isOwn(tag)) -1 else (hash(tag) % light.size).toInt()

    fun isOwn(tag: String): Boolean = normalize(tag) == OWN_TAG

    /** The colours of [tag] in a theme. */
    fun of(tag: String, dark: Boolean): TagColor {
        val i = index(tag)
        return when {
            i < 0 -> own
            dark -> this.dark[i]
            else -> light[i]
        }
    }

    private const val FNV_OFFSET = 0x811C9DC5L
    private const val FNV_PRIME = 0x01000193L
    private const val MASK = 0xFFFFFFFFL
    private const val BYTE = 0xFFL
}

/** The colours of [tag] in the current theme. */
@Composable
@ReadOnlyComposable
fun tagColor(tag: String): TagColor = TagColors.of(tag, VettIdTheme.colors.isDark)
