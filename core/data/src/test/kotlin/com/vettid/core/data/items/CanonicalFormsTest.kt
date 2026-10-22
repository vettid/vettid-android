// Test data: literal rows are clearer on one line.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canonical stored forms (VAULT-MESSAGING 0.55.3 §10.7 "Canonical forms", number rule 0.55.2; ANDROID-PLAN 0.1.68 §4): exact
 * bytes for each example of the spec, that every result passes the §10.7 check, and that canonicalising twice
 * changes nothing. iOS mirrors these cases.
 */
class CanonicalFormsTest {
    private fun canon(kind: String, v: String, label: String = "Label", month: Boolean = false) =
        CanonicalForms.text(kind, v, label, month) ?: v

    private fun ok(kind: String, v: String) = ItemChecks.valueProblems(kind, FieldValue.Text(v)).isEmpty()

    /** Each row: kind, typed, canonical. Checked for bytes, for the §10.7 check and for idempotence. */
    private val rows = listOf(
        listOf("number", "007", "007"), listOf("number", " 1.0 ", "1.0"), listOf("number", "+5", "5"), listOf("number", "-0", "-0"),
        listOf("number", "0.50", "0.50"), listOf("number", "+-5", "-5"), listOf("number", "\u00A05\u3000", "5"), listOf("number", "-007.500", "-007.500"),
        listOf("number", "0", "0"), listOf("number", "10.01", "10.01"), listOf("number", "", ""),
        listOf("date", "2031-04-30", "2031-04-30"), listOf("date", "2031-04", "2031-04"), listOf("date", "", ""),
        listOf("email", "  Sam.Lee@Example.COM  ", "Sam.Lee@example.com"), listOf("email", "a@b.c", "a@b.c"),
        listOf("email", " x@Y.org　", "x@y.org"), listOf("email", "É@É.COM", "É@É.com"),
        listOf("url", "HTTP://Example.com/A", "http://example.com/A"), listOf("url", "  https://EXAMPLE.com/Path?Q=1#F  ", "https://example.com/Path?Q=1#F"),
        listOf("url", "HTTPS://User:Pw@Example.COM:8080/X", "https://User:Pw@example.com:8080/X"),
        listOf("url", "https://Example.com", "https://example.com"), listOf("url", "https://Example.com?Q=A", "https://example.com?Q=A"),
        listOf("url", "https://[::1]:80/A", "https://[::1]:80/A"), listOf("url", "https://[FE80::A]/A", "https://[fe80::a]/A"),
        listOf("url", "MAILTO:A@B.C", "mailto:A@B.C"), listOf("url", "Tel:+4420", "tel:+4420"), listOf("url", "SMS:123", "sms:123"),
        listOf("url", "localhost:8080/x", "https://localhost:8080/x"), listOf("url", "Example.com/A", "https://example.com/A"),
        listOf("url", "ftp://FILES.example.org/Dir", "ftp://files.example.org/Dir"), listOf("url", "", ""),
        listOf("url", "urn:isbn:1", "urn:isbn:1"), listOf("url", "bitcoin:bc1qxyz", "bitcoin:bc1qxyz"), listOf("url", "geo:1,2", "geo:1,2"),
        listOf("url", "foo:", "foo:"), listOf("url", "tel:5550100", "tel:5550100"), listOf("url", "C:\\path", "c:\\path"),
        listOf("url", "user@Host:22", "https://user@host:22"), listOf("url", "Example.com:443/A", "https://example.com:443/A"),
        listOf("url", "HTTP://[2001:DB8::1]:8080/A", "http://[2001:db8::1]:8080/A"), listOf("url", "Mailto:a@b.c", "mailto:a@b.c"),
        listOf("url", "URN:isbn:1", "urn:isbn:1"), listOf("url", "Bitcoin:bc1qXyz", "bitcoin:bc1qXyz"), listOf("url", "GEO:1,2", "geo:1,2"),
        listOf("url", "a.b:c", "https://a.b:c"), listOf("url", "foo/bar:baz", "https://foo/bar:baz"), listOf("url", "Host:80?Q=1", "https://host:80?Q=1"),
        listOf("text", "  Hello world \t", "Hello world"), listOf("text", "  x y　", "x y"), listOf("text", "a  b", "a  b"),
    )

    @Test
    fun everyExampleIsStoredAsTheSpecSays() {
        rows.forEach { (kind, typed, expected) -> assertEquals("$kind $typed", expected, canon(kind, typed)) }
    }

    @Test
    fun everyResultPassesTheTableAndCanonicalisingTwiceChangesNothing() {
        rows.forEach { (kind, typed, expected) ->
            assertTrue("$kind $expected", ok(kind, expected))
            assertEquals("$kind $typed again", expected, canon(kind, expected))
        }
    }

    @Test
    fun aBadValueIsLeftAsTypedForTheCheckToRefuse() {
        listOf("number" to "1,5", "number" to "-", "number" to "1.2.3", "number" to "abc", "number" to ".", "number" to "5.", "number" to ".5", "date" to "30.04.2031").forEach { (kind, v) ->
            assertEquals(v, canon(kind, v))
            assertTrue(DraftProblem.VALUE_INVALID in ItemChecks.valueProblems(kind, FieldValue.Text(canon(kind, v))))
        }
        // A secret that is not base32 of 16-256 characters is not wrapped, so the check refuses it.
        assertEquals("SHORT", canon("otp", "SHORT"))
        assertEquals("ABCD EFG1 ABCD EFGH", canon("otp", "ABCD EFG1 ABCD EFGH"))
        assertTrue(DraftProblem.VALUE_INVALID in ItemChecks.valueProblems("otp", FieldValue.Text(canon("otp", "SHORT"))))
        assertEquals("email-without-at", canon("email", "email-without-at"))
        assertTrue(DraftProblem.VALUE_INVALID in ItemChecks.valueProblems("email", FieldValue.Text(canon("email", "email-without-at"))))
    }

    @Test
    fun aSecondLeadingPlusIsKeptAndRefused() {
        assertEquals("+5", canon("number", "++5"))
        assertTrue(DraftProblem.VALUE_INVALID in ItemChecks.valueProblems("number", FieldValue.Text("+5")))
    }

    @Test
    fun aDateIsKeptAsTypedAndAFullDateInAMonthFieldIsRefusedNotCut() {
        assertEquals("2031-04", canon("date", "2031-04", month = true))
        assertEquals("2031-04-30", canon("date", "2031-04-30", month = false))
        assertEquals("2031-04", canon("date", "2031-04", month = false))
        assertEquals("2031-04-30", canon("date", "2031-04-30", month = true))
        val month = DraftField(label = "Expires", kind = "date", text = "2031-04-30", monthYear = true)
        assertTrue(DraftProblem.VALUE_INVALID in ItemChecks.check(ItemDraft(name = "C", fields = listOf(month))).fieldProblems[0].orEmpty())
        assertTrue(ItemChecks.check(ItemDraft(name = "C", fields = listOf(month.copy(text = "2031-04")))).ok)
        assertTrue(ItemChecks.check(ItemDraft(name = "C", fields = listOf(month.copy(monthYear = false)))).ok)
    }

    @Test
    fun anOtpSecretIsWrappedWithTheLabelPercentEncoded() {
        val secret = "JBSWY3DPEHPK3PXP"
        fun uri(label: String) = "otpauth://totp/$label?secret=$secret&algorithm=SHA1&digits=6&period=30"
        assertEquals(uri("Label"), canon("otp", "jbsw y3dp ehpk 3pxp"))
        assertEquals(uri("Label"), canon("otp", " JBSWY3DPEHPK3PXP "))
        assertEquals(uri("Label"), canon("otp", "JBSWY3DPEHPK3PXP===="))
        assertEquals(uri("GitHub%20work"), canon("otp", secret, "GitHub work"))
        assertEquals(uri("a-b.c_d~E9"), canon("otp", secret, "a-b.c_d~E9"))
        assertEquals(uri("%C3%89%2B%2F%3A%40%25%26%3D%3F%23%22"), canon("otp", secret, "É+/:@%&=?#\""))
        assertEquals(uri("%E6%97%A5%F0%9F%94%91"), canon("otp", secret, "日🔑"))
        assertEquals(uri("Mail"), canon("otp", secret, "  Mail  "))
        assertEquals(uri("Mail"), canon("otp", uri("Mail"), "Other"))
        assertTrue(ok("otp", canon("otp", secret, "x".repeat(64))))
        assertTrue(ok("otp", canon("otp", "a".repeat(256), "日".repeat(21))))
    }

    @Test
    fun anOtpUriKeepsItsParametersAndHasItsSecretCanonical() {
        assertEquals(
            "otpauth://totp/Acme:sam?secret=JBSWY3DPEHPK3PXP&issuer=Acme&digits=8&period=60&algorithm=SHA256",
            canon("otp", "otpauth://totp/Acme:sam?secret=jbswy3dpehpk3pxp&issuer=Acme&digits=8&period=60&algorithm=SHA256"),
        )
        assertEquals("otpauth://totp/x?issuer=A&secret=JBSWY3DPEHPK3PXP", canon("otp", "otpauth://totp/x?issuer=A&secret=jbswy3dpehpk3pxp===="))
        assertEquals("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP#f", canon("otp", "otpauth://totp/x?secret=jbswy3dp%20ehpk3pxp#f"))
        assertEquals("otpauth://totp/x?issuer=A", canon("otp", "otpauth://totp/x?issuer=A"))
        assertEquals("otpauth://totp/x?secret=not*base32", canon("otp", "otpauth://totp/x?secret=not*base32"))
    }

    @Test
    fun anAddressIsTrimmedCollapsedAndItsPostalCodeAndCountryUpperCase() {
        val a = CanonicalForms.address(
            AddressValue(
                street = "  12   High   Street ", street2 = "　", city = " St\tAlbans ", region = "Herts  ", postalCode = " al1  1aa ", country = " gb ",
            ),
        )
        assertEquals(AddressValue(street = "12 High Street", street2 = "", city = "St Albans", region = "Herts", postalCode = "AL1 1AA", country = "GB"), a)
        // Empty members are left out of what is sent.
        assertEquals("""{"street":"12 High Street","city":"St Albans","region":"Herts","postal_code":"AL1 1AA","country":"GB"}""", ItemChecks.valueJson(FieldValue.Address(a)))
        assertEquals(a, CanonicalForms.address(a))
        assertTrue(ItemChecks.valueProblems("address", FieldValue.Address(a)).isEmpty())
        // The postal code's case change is ASCII only.
        assertEquals("éa1", CanonicalForms.address(AddressValue(postalCode = "éa1")).postalCode.lowercase())
        assertEquals("éA1", CanonicalForms.address(AddressValue(postalCode = "éa1")).postalCode)
    }

    @Test
    fun caseMappingIsAsciiOnlyAndWhiteSpaceIsUnicodeWhiteSpace() {
        assertEquals("abcÉİ", CanonicalForms.lower("ABCÉİ"))
        assertEquals("ABCéı", CanonicalForms.upper("abcéı"))
        assertEquals("x", CanonicalForms.trim("\u0085        　x\u000B\u000C"))
        // U+200B, U+180E and U+FEFF are not White_Space; U+001C is not either (Kotlin's isWhitespace says it is).
        assertEquals("​x᠎", CanonicalForms.trim("​x᠎"))
        assertEquals("﻿x", CanonicalForms.trim("﻿x"))
        assertEquals("\u001Cx", CanonicalForms.trim("\u001Cx"))
        assertEquals("a b c", CanonicalForms.collapse(" a    b\t\tc "))
    }

    @Test
    fun passwordMultilineAndUnknownKindsAreNotTouched() {
        listOf("password", "multiline", "phone", "file", "future").forEach { kind ->
            assertEquals(null, CanonicalForms.text(kind, "  A b \n ", "L"))
        }
        val p = DraftField(label = "L", kind = "password", text = "  Pa ss  ")
        assertEquals(p, CanonicalForms.field(p))
        val kept = DraftField(fieldId = "f1", label = "L", kind = "text", text = "", kept = true)
        assertEquals(kept, CanonicalForms.field(kept))
    }

    @Test
    fun aFieldTakesItsCanonicalLabelForTheOtpUri() {
        val f = CanonicalForms.field(DraftField(label = " My Key ", kind = "otp", text = "JBSWY3DPEHPK3PXP"))
        assertEquals("otpauth://totp/My%20Key?secret=JBSWY3DPEHPK3PXP&algorithm=SHA1&digits=6&period=30", f.text)
        assertEquals(f, CanonicalForms.field(f))
    }
}
