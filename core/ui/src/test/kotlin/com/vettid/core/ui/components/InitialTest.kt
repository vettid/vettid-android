package com.vettid.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialTest {
    @Test
    fun firstLetterUpperCased() = assertEquals("M", initialOf("mesmerverse"))

    @Test
    fun skipsLeadingPunctuationAndSpace() = assertEquals("A", initialOf("  @alice"))

    @Test
    fun blankIsQuestionMark() = assertEquals("?", initialOf("   "))

    @Test
    fun nonLatin() = assertEquals("É", initialOf("élodie"))
}
