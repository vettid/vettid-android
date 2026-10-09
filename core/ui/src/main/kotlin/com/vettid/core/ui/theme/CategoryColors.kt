package com.vettid.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The hues of History's categories (owner request 2026-10-09): unlocks green, security amber, blocked and dropped
 * red, connections teal, messages blue, the vault gold, devices purple, agents orange, location indigo, account grey.
 * A sibling of [TagColors], from the same kind of palette: pale fills with deep text of the same hue in the light
 * theme, deep fills with pale text in the dark one, every pair WCAG AA (`CategoryColorsTest`); the vault's gold is the
 * member's own ([TagColors.own]). Only the colour changes; the icons stay as they are.
 */
enum class CategoryHue { GREEN, AMBER, RED, TEAL, BLUE, GOLD, PURPLE, ORANGE, INDIGO, GREY }

object CategoryColors {
    val light: Map<CategoryHue, TagColor> = mapOf(
        CategoryHue.GREEN to TagColor(Color(0xFFC2EFC4), Color(0xFF00210A)),
        CategoryHue.AMBER to TagColor(Color(0xFFFFDF9E), Color(0xFF261A00)),
        CategoryHue.RED to TagColor(Color(0xFFFFDAD6), Color(0xFF410002)),
        CategoryHue.TEAL to TagColor(Color(0xFFB3EDE3), Color(0xFF00201C)),
        CategoryHue.BLUE to TagColor(Color(0xFFD8E2FF), Color(0xFF001A41)),
        CategoryHue.GOLD to TagColors.own,
        CategoryHue.PURPLE to TagColor(Color(0xFFF0DBFF), Color(0xFF2C0051)),
        CategoryHue.ORANGE to TagColor(Color(0xFFFFDBC8), Color(0xFF311300)),
        CategoryHue.INDIGO to TagColor(Color(0xFFE0E0FF), Color(0xFF13126B)),
        CategoryHue.GREY to TagColor(Color(0xFFE2E2E6), Color(0xFF1A1C1E)),
    )

    val dark: Map<CategoryHue, TagColor> = mapOf(
        CategoryHue.GREEN to TagColor(Color(0xFF1B5E2A), Color(0xFFC2EFC4)),
        CategoryHue.AMBER to TagColor(Color(0xFF5C4300), Color(0xFFFFDF9E)),
        CategoryHue.RED to TagColor(Color(0xFF8C1D18), Color(0xFFFFDAD6)),
        CategoryHue.TEAL to TagColor(Color(0xFF005047), Color(0xFFB3EDE3)),
        CategoryHue.BLUE to TagColor(Color(0xFF1F438F), Color(0xFFD8E2FF)),
        CategoryHue.GOLD to TagColors.own,
        CategoryHue.PURPLE to TagColor(Color(0xFF6A1B9A), Color(0xFFF0DBFF)),
        CategoryHue.ORANGE to TagColor(Color(0xFF723600), Color(0xFFFFDBC8)),
        CategoryHue.INDIGO to TagColor(Color(0xFF2E2D8C), Color(0xFFE0E0FF)),
        CategoryHue.GREY to TagColor(Color(0xFF45474A), Color(0xFFE2E2E6)),
    )

    fun of(hue: CategoryHue, dark: Boolean): TagColor = (if (dark) this.dark else light).getValue(hue)
}

/** The colours of [hue] in the current theme. */
@Composable
@ReadOnlyComposable
fun categoryColor(hue: CategoryHue): TagColor = CategoryColors.of(hue, VettIdTheme.colors.isDark)
