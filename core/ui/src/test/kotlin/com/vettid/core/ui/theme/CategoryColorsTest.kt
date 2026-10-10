package com.vettid.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** History's category colours (owner request 2026-10-09): one fixed colour per hue, readable (WCAG AA) in both themes. */
class CategoryColorsTest {
    @Test
    fun everyHueHasAColourInBothThemesAndTheSameOneEachTime() {
        CategoryHue.entries.forEach { h ->
            assertEquals(h.name, CategoryColors.of(h, dark = false), CategoryColors.of(h, dark = false))
            assertEquals(h.name, CategoryColors.of(h, dark = true), CategoryColors.of(h, dark = true))
        }
        assertEquals(CategoryHue.entries.toSet(), CategoryColors.light.keys)
        assertEquals(CategoryHue.entries.toSet(), CategoryColors.dark.keys)
        // Distinct within a theme, so that the groups can be told apart.
        assertEquals(CategoryHue.entries.size, CategoryColors.light.values.map { it.container }.distinct().size)
        assertEquals(CategoryHue.entries.size, CategoryColors.dark.values.map { it.container }.distinct().size)
    }

    @Test
    fun theVaultIsTheMembersGold() {
        // The tag palette changed on 2026-10-09 (distinct hues); History's categories keep their own colours.
        assertEquals(TagColors.own, CategoryColors.of(CategoryHue.GOLD, dark = false))
        assertEquals(TagColors.own, CategoryColors.of(CategoryHue.GOLD, dark = true))
    }

    @Test
    fun everyPairMeetsAaInBothThemes() {
        val all = CategoryColors.light.map { "light ${it.key}" to it.value } + CategoryColors.dark.map { "dark ${it.key}" to it.value }
        all.forEach { (name, c) ->
            val ratio = contrastRatio(c.onContainer, c.container)
            assertTrue("$name: ${"%.2f".format(ratio)} < 4.5", ratio >= 4.5)
        }
    }
}
