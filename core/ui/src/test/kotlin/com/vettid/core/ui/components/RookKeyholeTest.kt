package com.vettid.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The keyhole in every rook drawable must be ONE closed contour (round top merged with the slot).
 * Drawn as an overlapping circle + slot in one path, the default nonZero fill rule cancels the
 * overlap (the circle winds clockwise, the slot anticlockwise) and evenOdd does the same, leaving a
 * gold/white band across the keyhole. The website avoids this with an SVG mask; vectors can't.
 */
class RookKeyholeTest {

    private val drawables = listOf(
        "src/main/res/drawable/ic_rook.xml",
        "../../app/src/main/res/drawable/ic_launcher_foreground.xml",
        "../../app/src/main/res/drawable/ic_launcher_monochrome.xml",
    )

    private fun pathData(file: File): List<String> =
        Regex("android:pathData=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toList()

    @Test
    fun keyholeIsOneContourWhoseArcEndsOnTheCircle() {
        for (name in drawables) {
            val file = File(name)
            assertTrue("$name missing", file.isFile)
            val subpaths = pathData(file).flatMap { d -> d.split('M').filter { it.isNotBlank() } }
            // Exactly one subpath draws the round top, and it is also the one that draws the slot.
            val sub = subpaths.single { "A29,29" in it }
            assertTrue("$name: round top and slot must be one contour", "112,292" in sub && "152,292" in sub)
            assertEquals("$name: slot drawn twice", 1, subpaths.count { "112,292" in it })
            val arc = Regex("""^\s*([\d.]+),([\d.]+) .*L([\d.]+),([\d.]+) A29,29 0 1,0 ([\d.]+),([\d.]+) Z\s*$""")
                .find(sub) ?: error("$name: keyhole must be slot + one anticlockwise arc closing at the start: $sub")
            val n = arc.groupValues.drop(1).map(String::toDouble)
            assertEquals("$name: arc must close the contour", n.subList(0, 2), n.subList(4, 6))
            listOf(0, 2).forEach { i ->
                assertEquals("$name: ${n[i]},${n[i + 1]} on circle", 29.0, hypot(n[i] - 132, n[i + 1] - 190), 0.05)
            }
        }
    }
}
