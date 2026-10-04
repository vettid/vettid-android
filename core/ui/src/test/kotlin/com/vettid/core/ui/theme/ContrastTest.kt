package com.vettid.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text/icon pairs used by the components must meet WCAG AA: 4.5:1 for body text,
 * 3:1 for large text and icons.
 */
class ContrastTest {

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("$name: ${"%.2f".format(ratio)} < $min", ratio >= min)
    }

    @Test
    fun contrastOfKnownPairs() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.0001)
    }

    @Test
    fun darkSchemeMeetsAa() = checkScheme("dark", VettIdSchemes.dark, VettIdSchemes.darkExtras)

    @Test
    fun lightSchemeMeetsAa() = checkScheme("light", VettIdSchemes.light, VettIdSchemes.lightExtras)

    private fun checkScheme(theme: String, c: ColorScheme, x: VettIdColors) {
        val surfaces = mapOf(
            "background" to c.background,
            "surfaceContainerLow" to c.surfaceContainerLow,
            "surfaceContainer" to c.surfaceContainer,
        )
        for ((name, bg) in surfaces) {
            assertContrast("$theme onSurface/$name", c.onSurface, bg, 4.5)
            assertContrast("$theme onSurfaceVariant/$name", c.onSurfaceVariant, bg, 4.5)
            assertContrast("$theme primary/$name", c.primary, bg, 4.5)
        }
        assertContrast("$theme onSurface/card", c.onSurface, x.card, 4.5)
        assertContrast("$theme onSurfaceVariant/card", c.onSurfaceVariant, x.card, 4.5)
        assertContrast("$theme onSurfaceVariant/groupedBackground", c.onSurfaceVariant, x.groupedBackground, 4.5)
        assertContrast("$theme onSurface/surfaceContainerHigh", c.onSurface, c.surfaceContainerHigh, 4.5)
        assertContrast("$theme primary/surfaceContainerHigh", c.primary, c.surfaceContainerHigh, 3.0)
        assertContrast("$theme onPrimaryContainer/primaryContainer", c.onPrimaryContainer, c.primaryContainer, 4.5)
        assertContrast("$theme onTile/tile", x.onTile, x.tile, 4.5)
        assertContrast("$theme onAvatar/avatar", x.onAvatar, x.avatar, 4.5)
        assertContrast("$theme error/background", c.error, c.background, 4.5)
    }
}
