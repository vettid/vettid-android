package com.vettid.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tag colours (owner request 2026-10-09): one per tag, the same everywhere and on every device (FNV-1a of the
 * normalised name), spread over the palette, `@profile` gold, and every pair readable (WCAG AA) in both themes.
 */
class TagColorsTest {
    @Test
    fun theHashIsFnv1aOverUtf8() {
        // The published FNV-1a 32-bit vectors.
        assertEquals(0x811C9DC5L, TagColors.hash(""))
        assertEquals(0xE40C292CL, TagColors.hash("a"))
        assertEquals(0xBF9CF968L, TagColors.hash("foobar"))
    }

    @Test
    fun theSameTagHasTheSameColour() {
        listOf("travel", "medical", "money", "family", "école", "家族").forEach { t ->
            assertEquals(t, TagColors.index(t), TagColors.index(t))
            assertEquals(t, TagColors.of(t, dark = false), TagColors.of(t, dark = false))
            assertEquals(t, TagColors.index(t), TagColors.index(" $t  "))
        }
        // §10.7 lower-cases ASCII letters only, as the vault does.
        assertEquals(TagColors.index("travel"), TagColors.index("TRAVEL"))
        // Fixed slots: a change here changes every member's colours.
        assertEquals(TagColors.index("travel"), (TagColors.hash("travel") % 10).toInt())
        assertEquals("work bag", TagColors.normalize("  Work   Bag "))
    }

    @Test
    fun tagsSpreadOverThePalette() {
        val counts = IntArray(TagColors.light.size)
        val words = listOf("travel", "medical", "money", "family", "work", "home", "car", "school", "crypto", "identity")
        val tags = (0 until 2000).map { "${words[it % words.size]}$it" }
        tags.forEach { counts[TagColors.index(it)]++ }
        val mean = tags.size / counts.size
        counts.forEachIndexed { i, n -> assertTrue("slot $i has $n", n > mean / 2 && n < mean * 3 / 2) }
    }

    @Test
    fun theSharedProfileIsGold() {
        assertEquals(-1, TagColors.index("@profile"))
        assertEquals(VettIdPalette.Gold, TagColors.of("@profile", dark = true).container)
        assertEquals(VettIdPalette.Gold, TagColors.of("@profile", dark = false).container)
        assertEquals(VettIdSchemes.darkExtras.avatar, TagColors.own.container)
        // No other tag is gold.
        (TagColors.light + TagColors.dark).forEach { assertNotEquals(VettIdPalette.Gold, it.container) }
    }

    @Test
    fun everyPairMeetsAaInBothThemes() {
        assertEquals(10, TagColors.light.size)
        assertEquals(TagColors.light.size, TagColors.dark.size)
        val all = TagColors.light.map { "light" to it } + TagColors.dark.map { "dark" to it } + listOf("own" to TagColors.own)
        all.forEachIndexed { i, (theme, c) ->
            val ratio = contrastRatio(c.onContainer, c.container)
            assertTrue("$theme #$i: ${"%.2f".format(ratio)} < 4.5", ratio >= 4.5)
        }
        // Distinct colours within a theme.
        assertEquals(10, TagColors.light.map { it.container }.distinct().size)
        assertEquals(10, TagColors.dark.map { it.container }.distinct().size)
    }
}
