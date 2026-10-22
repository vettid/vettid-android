package com.vettid.feature.items

import androidx.compose.ui.text.AnnotatedString
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.ItemChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone numbers (owner request 2026-10-09): formatted as typed for the phone's region (libphonenumber), the region
 * switched by a `+` and country code, stored in international format within §10.7's phone rule, an unknown number kept
 * as typed with a hint, shown for the viewer's region, and pasted numbers taken in.
 */
class PhoneInputTest {
    private fun valid(s: String) = ItemChecks.valueProblems(FieldKinds.PHONE, FieldValue.Text(s)).isEmpty()

    /** What the input shows after each character of [typed] (as the member types it). */
    private fun typing(typed: String, region: String): String {
        var held = ""
        for (c in typed) held = PhoneInput.raw(held + c)
        return PhoneInput.Mask(region).filter(AnnotatedString(held)).text.text
    }

    @Test
    fun formatsAsTypedForTheRegion() {
        assertEquals("(650) 253-0000", typing("6502530000", "US"))
        assertEquals("650-2", typing("6502", "US"))
        assertEquals("020 7946 0958", typing("02079460958", "GB"))
        assertEquals("030 123456", typing("030123456", "DE"))
    }

    @Test
    fun aPlusAndCountryCodeSwitchTheRegion() {
        assertEquals("+44 20 7946 0958", typing("+442079460958", "US"))
        assertEquals("+49 30 123456", typing("+4930123456", "US"))
        assertEquals("+1 650-253-0000", typing("+16502530000", "DE"))
    }

    @Test
    fun theCursorMovesOverTheDrawnSeparators() {
        val t = PhoneInput.Mask("US").filter(AnnotatedString("6502530000"))
        assertEquals("(650) 253-0000", t.text.text)
        assertEquals(0, t.offsetMapping.originalToTransformed(0))
        assertEquals(4, t.offsetMapping.originalToTransformed(3)) // after "(650"
        assertEquals(14, t.offsetMapping.originalToTransformed(10))
        assertEquals(3, t.offsetMapping.transformedToOriginal(6)) // "(650) " holds 3 digits
        assertEquals(10, t.offsetMapping.transformedToOriginal(14))
    }

    @Test
    fun aPastedNumberIsTakenIn() {
        assertEquals("+16502530000", PhoneInput.raw("+1 (650) 253-0000"))
        assertEquals("+16502530000", PhoneInput.raw("+16502530000"))
        assertEquals("6502530000", PhoneInput.raw("tel: 650.253.0000"))
        assertEquals("+442079460958", PhoneInput.stored(PhoneInput.raw("+44 20 7946 0958"), "US"))
    }

    @Test
    fun aNumberIsStoredInE164WithinThePhoneRule() {
        val stored = listOf(
            PhoneInput.stored("6502530000", "US"),
            PhoneInput.stored("02079460958", "GB"),
            PhoneInput.stored("030123456", "DE"),
            PhoneInput.stored("+442079460958", "DE"),
        )
        assertEquals(listOf("+16502530000", "+442079460958", "+4930123456", "+442079460958"), stored)
        stored.forEach { assertTrue(it, valid(it)) }
        // Stored again, a stored number does not change; an older spaced value is stored canonically when saved.
        assertEquals("+16502530000", PhoneInput.stored("+16502530000", "GB"))
        assertEquals("+16502530000", PhoneInput.stored("+1 650-253-0000", "GB"))
    }

    @Test
    fun anUnknownNumberIsKeptAsTypedWithAHintNeverABlock() {
        assertTrue(PhoneInput.doubtful("55512", "US"))
        // Kept as its dialable characters only (owner decision 2026-10-22).
        assertEquals("55512", PhoneInput.stored("55512", "US"))
        assertEquals("+55512", PhoneInput.stored("+555 12", "US"))
        assertTrue(valid(PhoneInput.stored("55512", "US")))
        assertFalse(PhoneInput.doubtful("6502530000", "US"))
        assertFalse(PhoneInput.doubtful("+442079460958", "US"))
        assertFalse(PhoneInput.doubtful("", "US"))
        // A region the phone does not know reads only numbers with a +.
        assertTrue(PhoneInput.doubtful("6502530000", "ZZ"))
        assertEquals("+16502530000", PhoneInput.stored("+16502530000", "ZZ"))
    }

    @Test
    fun aStoredNumberIsShownForTheViewersRegion() {
        assertEquals("(650) 253-0000", PhoneInput.display("+1 650-253-0000", "US"))
        assertEquals("+1 650-253-0000", PhoneInput.display("+1 650-253-0000", "GB"))
        assertEquals("020 7946 0958", PhoneInput.display("+44 20 7946 0958", "GB"))
        assertEquals("+44 20 7946 0958", PhoneInput.display("+44 20 7946 0958", "US"))
        assertEquals("555 12", PhoneInput.display("555 12", "US"))
    }

    @Test
    fun e164AndOlderSpacedValuesAreReadAlike() {
        for (v in listOf("+16502530000", "+1 650-253-0000")) {
            assertEquals(v, "(650) 253-0000", PhoneInput.display(v, "US"))
            assertEquals(v, "+1 650-253-0000", PhoneInput.display(v, "GB"))
            assertFalse(v, PhoneInput.doubtful(v, "GB"))
            assertEquals(v, "+16502530000", PhoneInput.raw(v))
        }
        assertEquals("020 7946 0958", PhoneInput.display("+442079460958", "GB"))
        assertEquals("+44 20 7946 0958", PhoneInput.display("+442079460958", "US"))
        assertEquals("55512", PhoneInput.display("55512", "US"))
    }

    /** Owner decision 2026-10-22: only the main number is saved; the extension is dropped, with a note. */
    @Test
    fun anExtensionIsDroppedWhenSavedAndTheEditorSaysSo() {
        val typedWithExtension = listOf(
            "+44 20 7946 0958 x123", "+442079460958 ext. 123", "+44 20 7946 0958;ext=123", "+44 20 7946 0958#123", "+442079460958,123",
        )
        for (typed in typedWithExtension) {
            assertTrue(typed, PhoneInput.hasExtension(typed))
            assertEquals(typed, "+442079460958", PhoneInput.stored(PhoneInput.raw(typed), "US"))
            assertFalse(typed, PhoneInput.doubtful(typed, "US"))
        }
        assertFalse(PhoneInput.hasExtension("+442079460958"))
        // The fallback never appends the extension's digits.
        assertEquals("55512", PhoneInput.stored("55512 x99", "US"))
        assertEquals("+55512", PhoneInput.stored("+555 12 ext 99", "US"))
        assertEquals("(650) 253-0000", PhoneInput.display("+16502530000", "US"))
    }

    @Test
    fun noDigitLeftIsEmptyAndTheFallbackIsAtMost15Digits() {
        assertEquals("", PhoneInput.stored("+", "US"))
        assertEquals("123", PhoneInput.stored("x123", "US")) // no digit before the marker: not an extension
        assertEquals("", PhoneInput.stored("abc", "US"))
        assertEquals(15, PhoneInput.stored("1234567890123456789", "ZZ").length)
        // Case does not matter for a marker.
        assertEquals("+442079460958", PhoneInput.stored(PhoneInput.raw("+442079460958 EXT. 7"), "US"))
        assertEquals("+442079460958", PhoneInput.stored(PhoneInput.raw("+442079460958 X7"), "US"))
    }

    /** `IS_POSSIBLE_LOCAL_ONLY` is not enough for E.164: the dialable characters are kept, with the hint. */
    @Test
    fun aLocalOnlyNumberIsKeptAsDialableCharactersWithAHint() {
        assertEquals("2530000", PhoneInput.stored("2530000", "US"))
        assertTrue(PhoneInput.doubtful("2530000", "US"))
    }

    /** A marker needs a digit before it; the member's own marker text stays in the input until saved. */
    @Test
    fun aMarkerWithoutADigitBeforeItIsNotAnExtension() {
        for (typed in listOf("Fax: +44 20 7946 0958", "Text 6502530000")) {
            assertFalse(typed, PhoneInput.hasExtension(typed))
        }
        assertEquals("+442079460958", PhoneInput.stored("Fax: +44 20 7946 0958", "US"))
        assertEquals("+16502530000", PhoneInput.stored("Text +1 650 253 0000", "US"))
        assertEquals("123", PhoneInput.stored("123 ext 4", "US"))
        assertTrue(PhoneInput.hasExtension("123 ext 4"))
        for (typed in listOf(",123", "#123", "ext. 123", "x123")) {
            val held = PhoneInput.raw("+442079460958$typed")
            assertEquals("+442079460958$typed", held)
            assertEquals("+442079460958", PhoneInput.stored(held, "US"))
            // Typing on does not change what is held.
            assertEquals(held, PhoneInput.raw(held))
        }
    }

    @Test
    fun aPlusBeforeTheFirstDigitCountsAndRegionsDecideLocalNumbers() {
        assertEquals("+442079460958", PhoneInput.raw("Fax: +44 20 7946 0958"))
        assertEquals("+442079460958", PhoneInput.stored("Fax: +44 20 7946 0958", "US"))
        assertEquals("5550100", PhoneInput.stored("Text 555 0100", "US"))
        assertEquals("+442079460958", PhoneInput.stored("+44 20 7946 0958 X123", "US"))
        assertEquals("123", PhoneInput.stored("#123", "US"))
        assertFalse(PhoneInput.hasExtension("#123"))
    }

    @Suppress("MaxLineLength")
    @Test
    fun aMarkerTypedKeyByKeyIsKeptInTheEditTextAndDroppedOnSave() {
        // Hardware keyboard: the text is filtered after every key, so the start of a marker must survive it.
        fun keys(vararg typed: String): List<String> {
            var held = ""
            return typed.map { held = PhoneInput.raw(held + it); held }
        }
        val n = "+442079460958"
        assertEquals(listOf("${n}e", "${n}ex", "${n}ext", "${n}ext ", "${n}ext 1"), keys("+442079460958", "e", "x", "t", " ", "1").drop(1))
        assertEquals(listOf("$n;", "$n;e", "$n;ex", "$n;ext", "$n;ext=", "$n;ext=1"), keys("+442079460958", ";", "e", "x", "t", "=", "1").drop(1))
        listOf("e", "ex", ";", ";e", ";ex", ";ext", "ext", ";ext=").forEach { tail ->
            assertEquals(tail, "$n$tail", PhoneInput.raw("$n$tail"))
            assertEquals(tail, n, PhoneInput.stored("$n$tail", "US"))
            assertEquals(tail, n, PhoneInput.main("$n$tail"))
        }
        // Only the complete markers count as an extension (the note), not their start.
        listOf("e", "ex", ";", ";e", ";ex").forEach { assertFalse(it, PhoneInput.hasExtension("$n$it")) }
        listOf("ext", ";ext", ";ext=", "x").forEach { assertTrue(it, PhoneInput.hasExtension("$n$it")) }
        // A marker still needs a digit before it, and typing a digit after the start of one is not an extension.
        assertEquals("", PhoneInput.raw("e"))
        assertEquals("", PhoneInput.raw(";e"))
        assertEquals("5", PhoneInput.raw("5"))
        assertEquals("", PhoneInput.stored("ex", "US"))
        assertEquals("+442079460958", PhoneInput.raw("Fax: +44 20 7946 0958").let { PhoneInput.stored(it, "US") })
        // The partial marker is drawn after the number, and the whole edit text is idempotent under raw.
        listOf("${n}e", "${n};ex", "${n}ext=12").forEach { assertEquals(it, PhoneInput.raw(PhoneInput.raw(it))) }
        assertEquals("+44 20 7946 0958 ;ex", typing("+442079460958;ex", "US"))
    }

    @Test
    fun aPartialMarkerOnlyCountsRightAfterTheLastDigit() {
        // A pasted word is not the start of a marker.
        assertEquals("+442079460958", PhoneInput.raw("+44 20 7946 0958 Home"))
        assertEquals("+442079460958", PhoneInput.raw("+44 20 7946 0958 home e"))
        assertEquals("+442079460958", PhoneInput.raw("+44 20 7946 0958 Mobile"))
        assertEquals("+442079460958", PhoneInput.stored("+44 20 7946 0958 Home", "US"))
        // Phone signs and spaces between the last digit and the start of a marker are fine; the text is rebuilt without them.
        assertEquals("+442079460958ex", PhoneInput.raw("+44 20 7946 0958 ex"))
        assertEquals("+442079460958;e", PhoneInput.raw("+44 20 7946 0958) ;e"))
        assertEquals("+442079460958e", PhoneInput.raw("+44 20 7946 0958-e"))
    }
}
