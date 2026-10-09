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
        assertEquals("+44 20 7946 0958", PhoneInput.stored(PhoneInput.raw("+44 20 7946 0958"), "US"))
    }

    @Test
    fun aNumberIsStoredInInternationalFormatWithinThePhoneRule() {
        val stored = listOf(
            PhoneInput.stored("6502530000", "US"),
            PhoneInput.stored("02079460958", "GB"),
            PhoneInput.stored("030123456", "DE"),
            PhoneInput.stored("+442079460958", "DE"),
        )
        assertEquals(listOf("+1 650-253-0000", "+44 20 7946 0958", "+49 30 123456", "+44 20 7946 0958"), stored)
        stored.forEach { assertTrue(it, valid(it)) }
        // Stored again, a stored number does not change.
        assertEquals("+1 650-253-0000", PhoneInput.stored("+1 650-253-0000", "GB"))
    }

    @Test
    fun anUnknownNumberIsKeptAsTypedWithAHintNeverABlock() {
        assertTrue(PhoneInput.doubtful("55512", "US"))
        // Kept as the member saw it while typing (the as-you-type format), within §10.7's characters.
        assertEquals("555-12", PhoneInput.stored("55512", "US"))
        assertTrue(valid(PhoneInput.stored("55512", "US")))
        assertFalse(PhoneInput.doubtful("6502530000", "US"))
        assertFalse(PhoneInput.doubtful("+442079460958", "US"))
        assertFalse(PhoneInput.doubtful("", "US"))
        // A region the phone does not know reads only numbers with a +.
        assertTrue(PhoneInput.doubtful("6502530000", "ZZ"))
        assertEquals("+1 650-253-0000", PhoneInput.stored("+16502530000", "ZZ"))
    }

    @Test
    fun aStoredNumberIsShownForTheViewersRegion() {
        assertEquals("(650) 253-0000", PhoneInput.display("+1 650-253-0000", "US"))
        assertEquals("+1 650-253-0000", PhoneInput.display("+1 650-253-0000", "GB"))
        assertEquals("020 7946 0958", PhoneInput.display("+44 20 7946 0958", "GB"))
        assertEquals("+44 20 7946 0958", PhoneInput.display("+44 20 7946 0958", "US"))
        assertEquals("555 12", PhoneInput.display("555 12", "US"))
    }
}
