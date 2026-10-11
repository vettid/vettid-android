package com.vettid.core.ui.components

import androidx.compose.ui.graphics.Color
import com.vettid.core.ui.theme.VettIdSchemes
import com.vettid.core.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The brand kit's horizontal logo has two files: gold wordmark for dark surfaces, VettID Blue wordmark for light
 * ones (BRAND.md: never the gold wordmark on a light background; the rook stays gold on both). They must be the
 * same shapes in the same place, differ only in the wordmark's colour, and each wordmark must be legible on the
 * surfaces of its theme.
 */
class VettIdLogoDrawablesTest {

    private val onDark = File("src/main/res/drawable/ic_logo_horizontal.xml")
    private val onLight = File("src/main/res/drawable/ic_logo_horizontal_on_light.xml")

    private fun attribute(file: File, name: String): List<String> =
        Regex("android:$name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toList()

    private fun color(hex: String) = Color(0xFF000000 or hex.removePrefix("#").toLong(radix = 16))

    @Test
    fun sameShapesAndPlacementInBothFiles() {
        val geometry = listOf(
            "pathData", "fillType", "viewportWidth", "viewportHeight", "width", "height",
            "scaleX", "scaleY", "translateX", "translateY",
        )
        for (name in geometry) {
            assertEquals(name, attribute(onDark, name), attribute(onLight, name))
        }
        assertEquals("battlements, body with keyhole, wordmark", 3, attribute(onDark, "pathData").size)
    }

    @Test
    fun onlyTheWordmarkChangesColour() {
        // In file order: the rook's two paths, then the wordmark.
        assertEquals(listOf("#FFC125", "#FFC125", "#FFC125"), attribute(onDark, "fillColor"))
        assertEquals(listOf("#FFC125", "#FFC125", "#2E2D88"), attribute(onLight, "fillColor"))
    }

    @Test
    fun eachWordmarkIsLegibleOnItsThemesSurfaces() {
        fun check(file: File, scheme: androidx.compose.material3.ColorScheme, name: String) {
            val wordmark = color(attribute(file, "fillColor").last())
            // The drawer and sheets, the screen, and the gallery's surface.
            for (surface in listOf(scheme.surfaceContainerLow, scheme.background, scheme.surface)) {
                val ratio = contrastRatio(wordmark, surface)
                assertTrue("$name wordmark on $surface: ${"%.2f".format(ratio)} < 4.5", ratio >= MIN_CONTRAST)
            }
        }
        check(onDark, VettIdSchemes.dark, "gold")
        check(onLight, VettIdSchemes.light, "blue")
        // And the reason for two files: the gold wordmark fails on the light theme's drawer.
        assertTrue(contrastRatio(color("#FFC125"), VettIdSchemes.light.surfaceContainerLow) < MIN_ICON_CONTRAST)
    }

    private companion object {
        const val MIN_CONTRAST = 4.5
        const val MIN_ICON_CONTRAST = 3.0
    }
}
