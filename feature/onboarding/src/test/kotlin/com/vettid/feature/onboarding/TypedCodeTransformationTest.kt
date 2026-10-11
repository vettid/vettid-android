package com.vettid.feature.onboarding

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.VerbatimTtsAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The typed recovery code as the field draws it (ANDROID-PLAN 0.1.33): groups of four, four groups to a row, each
 * group marked to be spoken character by character. The characters are VAULT-MESSAGING §16's TEST ONLY vector.
 */
class TypedCodeTransformationTest {
    private val typed = "7ZK3M9QX2HTDCDEFGHJKMNPQRSTVWXYZ0123456789ABV3A1"

    private fun shown(raw: String) = TypedCodeTransformation.filter(AnnotatedString(raw))

    @Test
    fun groupsOfFourInThreeRowsAsItFills() {
        assertEquals("", shown("").text.text)
        assertEquals("7ZK", shown("7ZK").text.text)
        assertEquals("no separator before there is a fifth character", "7ZK3", shown("7ZK3").text.text)
        assertEquals("7ZK3 M", shown("7ZK3M").text.text)
        assertEquals("7ZK3 M9QX 2HTD CDEF", shown(typed.take(16)).text.text)
        assertEquals("7ZK3 M9QX 2HTD CDEF\nG", shown(typed.take(17)).text.text)
        assertEquals(
            "7ZK3 M9QX 2HTD CDEF\nGHJK MNPQ RSTV WXYZ\n0123 4567 89AB V3A1",
            shown(typed).text.text,
        )
        assertEquals(3, TypedCodeTransformation.ROWS)
    }

    @Test
    fun everyGroupIsSpokenCharacterByCharacter() {
        val t = shown(typed.take(10)).text
        val spoken = t.getTtsAnnotations(0, t.length)
        assertEquals(listOf("7ZK3", "M9QX", "2H"), spoken.map { (it.item as VerbatimTtsAnnotation).verbatim })
        assertEquals(listOf(0 to 4, 5 to 9, 10 to 12), spoken.map { it.start to it.end })
    }

    @Test
    fun theCursorMapsBothWaysAtEveryPosition() {
        for (n in 0..typed.length) {
            val t = shown(typed.take(n))
            val text = t.text.text
            for (o in 0..n) {
                val at = t.offsetMapping.originalToTransformed(o)
                // The same characters are before the cursor on both sides.
                assertEquals("n=$n o=$o", typed.take(o), text.take(at).filter { it != ' ' && it != '\n' })
                assertEquals("n=$n o=$o", o, t.offsetMapping.transformedToOriginal(at))
            }
            for (at in 0..text.length) {
                val o = t.offsetMapping.transformedToOriginal(at)
                assertEquals("n=$n at=$at", text.take(at).count { it != ' ' && it != '\n' }, o)
            }
        }
    }
}
