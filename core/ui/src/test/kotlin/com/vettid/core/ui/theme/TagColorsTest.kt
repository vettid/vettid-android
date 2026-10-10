package com.vettid.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Tag colours (owner decisions of 2026-10-09): the registry's stored colour first, the least-used palette colour for a
 * tag without one, the hash colour until one is stored, `@profile` gold, ten clearly different hues, and every pair
 * readable (WCAG AA) in both themes.
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
    fun theSameTagHasTheSameFallbackColour() {
        listOf("travel", "medical", "money", "family", "école", "家族").forEach { t ->
            assertEquals(t, TagColors.index(t), TagColors.index(t))
            assertEquals(t, TagColors.of(t, dark = false), TagColors.of(t, dark = false))
            assertEquals(t, TagColors.index(t), TagColors.index(" $t  "))
        }
        // §10.7 lower-cases ASCII letters only, as the vault does.
        assertEquals(TagColors.index("travel"), TagColors.index("TRAVEL"))
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
        // Whatever the registry says.
        assertEquals(TagColors.own, TagColors.of("@profile", dark = false, stored = TagColors.stored[0]))
        assertEquals(VettIdSchemes.darkExtras.avatar, TagColors.own.container)
        // No other tag is gold.
        (TagColors.light + TagColors.dark).forEach { assertNotEquals(VettIdPalette.Gold, it.container) }
    }

    @Test
    fun everyPairMeetsAaInBothThemes() {
        assertEquals(10, TagColors.light.size)
        assertEquals(TagColors.light.size, TagColors.dark.size)
        assertEquals(TagColors.light.size, TagColors.names.size)
        val all = TagColors.light.map { "light" to it } + TagColors.dark.map { "dark" to it } + listOf("own" to TagColors.own)
        all.forEachIndexed { i, (theme, c) ->
            val ratio = contrastRatio(c.onContainer, c.container)
            assertTrue("$theme #$i: ${"%.2f".format(ratio)} < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun thePaletteHasNoLookAlikesInEitherThemeAndNothingGoldLike() {
        // OKLab distance ×100 between every two fills of a theme, and to the member's gold: the old palette's red and
        // rust were 1.6 apart in the light theme. The new one keeps every pair at least 9 apart.
        listOf(TagColors.light, TagColors.dark).forEach { p ->
            val fills = p.map { it.container } + VettIdPalette.Gold
            for (i in fills.indices) {
                for (j in i + 1 until fills.size) {
                    val d = oklabDistance(fills[i], fills[j])
                    assertTrue("$i/$j: ${"%.1f".format(d)}", d >= 9.0)
                }
            }
        }
        // No hue in gold's neighbourhood (OKLCH hue 58–125°: amber, gold, yellow, olive) unless almost grey.
        (TagColors.light + TagColors.dark).forEachIndexed { i, c ->
            val (_, chroma, hue) = oklch(c.container)
            assertTrue("#$i hue ${"%.0f".format(hue)}", chroma < 0.07 || hue !in 58.0..125.0)
        }
    }

    @Test
    fun aPaletteColourIsStoredAsItsLightFill() {
        assertEquals("#f88d96", TagColors.stored[0])
        assertEquals(10, TagColors.stored.distinct().size)
        TagColors.stored.forEachIndexed { i, s ->
            assertTrue(s, Regex("#[0-9a-f]{6}").matches(s))
            assertEquals(TagColors.light[i].container, TagColors.parse(s))
            assertEquals(i, TagColors.slotOf(s))
            assertEquals(i, TagColors.slotOf(s.uppercase()))
            // Shown as its pair in each theme.
            assertEquals(TagColors.light[i], TagColors.of("travel", dark = false, stored = s))
            assertEquals(TagColors.dark[i], TagColors.of("travel", dark = true, stored = s))
        }
    }

    @Test
    fun aStoredColourOutsideThePaletteIsShownAsIsAndReadable() {
        listOf("#000000", "#ffffff", "#123456", "#ABCDEF", "#808080", "#ff0000", "#00ff00", "#7f7f7f").forEach { s ->
            listOf(false, true).forEach { dark ->
                val c = TagColors.of("travel", dark, stored = s)
                assertEquals(TagColors.parse(s), c.container)
                assertTrue(s, contrastRatio(c.onContainer, c.container) >= 4.5)
            }
        }
    }

    @Test
    fun withoutAValidStoredColourTheHashColourShows() {
        val hash = TagColors.light[TagColors.index("travel")]
        listOf(null, "", "red", "#fff", "#12345", "#1234567", "#12345g", "123456#", "#-12345", "# 12345", "#+12345").forEach { s ->
            assertNull(s, TagColors.parse(s))
            assertEquals(s, hash, TagColors.of("travel", dark = false, stored = s))
        }
        assertEquals(-1, TagColors.slotOf("#123456"))
        assertEquals(-1, TagColors.slotOf(null))
    }

    @Test
    fun thePickerMarksTheSlotShown() {
        assertEquals(3, TagColors.shownSlot("travel", TagColors.stored[3]))
        assertEquals(TagColors.index("travel"), TagColors.shownSlot("travel", null))
        assertEquals(-1, TagColors.shownSlot("travel", "#123456"))
        assertEquals(-1, TagColors.shownSlot("@profile", null))
    }

    // --- the least-used assignment ---

    private fun slot(c: String?) = TagColors.slotOf(c)

    /** Applies [TagColors.next] until every tag has a colour, as the app does one `tag.set` at a time. */
    private fun assignAll(tags: List<Pair<String, String?>>): Map<String, String?> {
        val m = LinkedHashMap(tags.toMap())
        while (true) {
            val (t, c) = TagColors.next(m.toList()) ?: return m
            assertNull("$t assigned twice", TagColors.parse(m[t]))
            m[t] = c
        }
    }

    @Test
    fun fiveNewTagsGetFiveDifferentColours() {
        val out = assignAll(listOf("travel", "medical", "money", "family", "work").map { it to null })
        assertEquals(5, out.values.map(::slot).distinct().size)
        // Ten tags: every palette colour once.
        val ten = assignAll((1..10).map { "tag $it" to null })
        assertEquals((0..9).toSet(), ten.values.map(::slot).toSet())
    }

    @Test
    fun theLeastUsedColourWinsAndTiesGoToTheHashSlotThenPaletteOrder() {
        // Nothing used: every slot ties, so the tag takes its hash slot.
        assertEquals("travel" to TagColors.stored[TagColors.index("travel")], TagColors.next(listOf("travel" to null)))
        // Every slot used once except one: that one, whatever the hash.
        val h = TagColors.index("travel")
        val free = (h + 3) % 10
        val used = (0..9).filter { it != free }.map { "t$it" to TagColors.stored[it] }
        assertEquals("travel" to TagColors.stored[free], TagColors.next(used + ("travel" to null)))
        // The hash slot taken, the rest free: the first free one in palette order.
        val first = if (h == 0) 1 else 0
        assertEquals("travel" to TagColors.stored[first], TagColors.next(listOf("x" to TagColors.stored[h], "travel" to null)))
    }

    @Test
    fun tagsAreAssignedInSortedNameOrderSoDevicesConverge() {
        val tags = listOf("Zoo", "apple", "medical", "b")
        val a = assignAll(tags.map { it to null })
        val b = assignAll(tags.reversed().map { it to null })
        assertEquals(a, b.toList().sortedBy { tags.indexOf(it.first) }.toMap())
        // The first chosen is the first by normalised name.
        assertEquals("apple", TagColors.next(tags.map { it to null })!!.first)
    }

    @Test
    fun theSharedProfileIsNeverAssignedAndColourlessTagsAloneAre() {
        assertNull(TagColors.next(listOf("@profile" to null)))
        assertEquals("travel", TagColors.next(listOf("@profile" to null, "travel" to null))!!.first)
        // Stored colours are kept: nothing left to do.
        assertNull(TagColors.next(listOf("a" to TagColors.stored[1], "b" to "#123456")))
        // An unreadable stored value counts as none.
        assertEquals("b", TagColors.next(listOf("a" to TagColors.stored[1], "b" to "teal"))!!.first)
    }

    @Test
    fun storedColoursOutsideThePaletteCountForNoSlot() {
        val h = TagColors.index("travel")
        val tags = (0 until 10).map { "o$it" to "#12345$it" } + ("travel" to null)
        assertEquals("travel" to TagColors.stored[h], TagColors.next(tags))
    }

    private fun lin(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun oklab(c: Color): Triple<Double, Double, Double> {
        val r = lin(c.red)
        val g = lin(c.green)
        val b = lin(c.blue)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Triple(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun oklabDistance(a: Color, b: Color): Double {
        val (l1, a1, b1) = oklab(a)
        val (l2, a2, b2) = oklab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2)) * 100
    }

    private fun oklch(c: Color): Triple<Double, Double, Double> {
        val (l, a, b) = oklab(c)
        val hue = Math.toDegrees(kotlin.math.atan2(b, a)).let { if (it < 0) it + 360 else it }
        return Triple(l, sqrt(a * a + b * b), abs(hue))
    }
}
