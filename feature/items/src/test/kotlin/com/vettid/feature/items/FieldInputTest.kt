package com.vettid.feature.items

import androidx.compose.ui.text.AnnotatedString
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.ItemChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Formats enforced while typing and on paste (owner request 2026-10-08, VAULT-MESSAGING §10.7 kinds): what each kind
 * lets in, how a paste is normalised, and that what it lets through is what the vault's checks accept.
 */
class FieldInputTest {
    private fun valid(kind: String, s: String) = ItemChecks.valueProblems(kind, FieldValue.Text(s)).isEmpty()

    @Test
    fun numbersTakeDigitsOneMinusAndOnePoint() {
        assertEquals("-3.5", FieldInput.number("-3.5"))
        assertEquals("42", FieldInput.number("4a2"))
        assertEquals("12", FieldInput.number("1-2"))
        assertEquals("1.25", FieldInput.number("1.2.5"))
        assertEquals("0.5", FieldInput.number(".5"))
        assertEquals("-0.5", FieldInput.number("-.5"))
        // A decimal comma is a point; with a point too, commas group thousands (paste).
        assertEquals("3.5", FieldInput.number("3,5"))
        assertEquals("1234.50", FieldInput.number("1,234.50"))
        assertEquals("1000000", FieldInput.number("1 000 000"))
        assertEquals("-7", FieldInput.number("−7"))
        assertEquals(32, FieldInput.number("9".repeat(40)).length)
        listOf("-3.5", "42", "0.5", "1234.50").forEach { assertTrue(it, valid(FieldKinds.NUMBER, it)) }
    }

    @Test
    fun phonesTakeOnlyDigitsSpacesAndPlusMinusBracketsPoint() {
        assertEquals("+44 (20) 7946-0000.", FieldInput.phone("+44 (20) 7946-0000."))
        assertEquals("+1 555 0100 ", FieldInput.phone("+1 555 0100 ext#"))
        assertEquals("", FieldInput.phone("call"))
        assertEquals(32, FieldInput.phone("1".repeat(40)).length)
        assertTrue(valid(FieldKinds.PHONE, FieldInput.phone("tel: +44 20 7946 0000")))
    }

    @Test
    fun emailsAndUrlsHoldNoWhiteSpace() {
        assertEquals("sam@example.org", FieldInput.accept(FieldKinds.EMAIL, " sam @example.org\n"))
        assertEquals("https://example.com/a", FieldInput.accept(FieldKinds.URL, "https://example.com/ a"))
        assertEquals(254, FieldInput.accept(FieldKinds.EMAIL, "a".repeat(300)).length)
        // Incomplete values say their rule while typing.
        assertTrue(FieldInput.incomplete(FieldKinds.EMAIL, "sam@"))
        assertFalse(FieldInput.incomplete(FieldKinds.EMAIL, "sam@example.org"))
        assertTrue(FieldInput.incomplete(FieldKinds.URL, "example.com"))
        assertFalse(FieldInput.incomplete(FieldKinds.URL, ""))
        assertFalse(FieldInput.incomplete(FieldKinds.TEXT, "anything"))
    }

    @Test
    fun otpSecretsAreBase32InUpperCaseAndLinksKeepTheirCase() {
        // A key as sites show it: lower case, in groups.
        assertEquals("JBSWY3DPEHPK3PXP", FieldInput.otp("jbsw y3dp ehpk 3pxp"))
        assertEquals("JBSWY3DPEHPK3PXP", FieldInput.otp("JBSW-Y3DP-EHPK-3PXP"))
        // 0, 1, 8 and 9 are not in the alphabet.
        assertEquals("ABC", FieldInput.otp("A0B1C89"))
        assertEquals("JBSWY3DPEHPK3PXP==", FieldInput.otp("JBSW=Y3DPEHPK3PXP=="))
        // Typing the scheme stays as typed until it is one; then the link keeps its case.
        assertEquals("otp", FieldInput.otp("OTP"))
        val uri = "otpauth://totp/VettID:sam?secret=JBSWY3DPEHPK3PXP&issuer=VettID"
        assertEquals(uri, FieldInput.otp(uri))
        assertEquals(uri, FieldInput.otp("OTPAUTH://totp/VettID:sam?secret=JBSWY3DPEHPK3PXP&issuer=VettID"))
        assertTrue(valid(FieldKinds.OTP, FieldInput.otp(" otpauth://totp/VettID:sam?secret=JBSWY3DPEHPK3PXP ")))
        assertTrue(valid(FieldKinds.OTP, FieldInput.otp("jbsw y3dp ehpk 3pxp")))
    }

    @Test
    fun aCountryIsTwoUpperCaseLetters() {
        assertEquals("DE", FieldInput.country("de"))
        assertEquals("US", FieldInput.country("u.s.a"))
    }

    @Test
    fun aDateIsTypedAsDigitsAndStoredInTheSpecFormat() {
        var v = ""
        for (c in "20310430") v = DateInput.accept(v, DateInput.digits(v) + c, monthOnly = false) ?: v
        assertEquals("2031-04-30", v)
        assertTrue(valid(FieldKinds.DATE, v))
        assertEquals("2031", DateInput.accept("", "2031", false))
        assertEquals("2031-0", DateInput.accept("2031", "20310", false))
        // Letters and separators never get in; a ninth digit does not either.
        assertEquals("2031-04", DateInput.accept("2031-0", "20310x4", false))
        assertEquals("2031-04-30", DateInput.accept("2031-04-30", "203104301", false))
    }

    @Test
    fun impossibleDatesAreRefused() {
        assertNull(DateInput.accept("2031", "20312", false)) // month 2x
        assertNull(DateInput.accept("2031-1", "203113", false)) // month 13
        assertNull(DateInput.accept("2031-0", "203100", false)) // month 00
        assertNull(DateInput.accept("2031-04", "2031044", false)) // day 4x
        assertNull(DateInput.accept("2031-02-3", "20310230", false)) // 30 February
        assertNull(DateInput.accept("2030-02-2", "20300229", false)) // not a leap year
        assertEquals("2028-02-29", DateInput.accept("2028-02-2", "20280229", false))
        // Taking a digit out of the middle always works, even if what is left is not a date yet.
        assertEquals("2031-23-1", DateInput.accept("2031-12-31", "2031231", false))
    }

    @Test
    fun aPasteIsNormalised() {
        assertEquals("2031-04-30", DateInput.accept("", "2031-04-30", false))
        assertEquals("2031-04-30", DateInput.accept("", "2031/04/30", false))
        assertEquals("2031-04-30", DateInput.accept("", " 2031.04.30 ", false))
        assertNull(DateInput.accept("", "30.04.2031", false))
    }

    @Test
    fun aMonthAndYearStopsAtTheMonth() {
        assertEquals("2031-04", DateInput.accept("2031-04", "20310430", monthOnly = true))
        assertEquals("2031-04", DateInput.accept("", "2031-04-30", monthOnly = true))
        assertTrue(valid(FieldKinds.DATE, "2031-04"))
        assertEquals("2031-04", DateInput.month(YearMonth.of(2031, 4)))
        assertTrue(DateInput.isMonth("2031-04"))
        assertFalse(DateInput.isMonth("2031-04-30"))
    }

    @Test
    fun thePickersResultIsTheSpecFormat() {
        val millis = LocalDate.of(2031, 4, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("2031-04-30", DateInput.fromPicker(millis))
        assertEquals(millis, DateInput.toPicker("2031-04-30"))
        assertEquals(LocalDate.of(2031, 4, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), DateInput.toPicker("2031-04"))
        assertNull(DateInput.toPicker("soon"))
        assertEquals("0005-01-09", DateInput.fromPicker(LocalDate.of(5, 1, 9).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
    }

    @Test
    fun theMaskDrawsTheDashesAndMovesTheCursorOverThem() {
        val t = DateInput.Mask.filter(AnnotatedString("20310430"))
        assertEquals("2031-04-30", t.text.text)
        val m = t.offsetMapping
        assertEquals(4, m.originalToTransformed(4))
        assertEquals(6, m.originalToTransformed(5))
        assertEquals(10, m.originalToTransformed(8))
        assertEquals(4, m.transformedToOriginal(5))
        assertEquals(6, m.transformedToOriginal(8))
        assertEquals(8, m.transformedToOriginal(10))
        val short = DateInput.Mask.filter(AnnotatedString("2031"))
        assertEquals("2031", short.text.text)
        assertEquals(4, short.offsetMapping.originalToTransformed(4))
    }
}
