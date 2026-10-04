package com.vettid.feature.settings

import com.vettid.core.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun cyclesThroughAllModes() {
        var mode = ThemeMode.System
        val seen = mutableListOf(mode)
        repeat(3) {
            mode = nextThemeMode(mode)
            seen += mode
        }
        assertEquals(listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark, ThemeMode.System), seen)
    }
}
