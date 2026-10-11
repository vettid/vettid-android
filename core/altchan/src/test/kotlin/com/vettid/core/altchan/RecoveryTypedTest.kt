@file:Suppress("MaxLineLength") // fixtures stay on one line

package com.vettid.core.altchan

import com.vettid.core.crypto.Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed recovery code (VAULT-MESSAGING 0.25.0 §11.11.10): normalization, the check characters against §16's
 * vector, and the [RecoveryCode] a typed entry gives, which must be the scanned one's. Every value is the spec's
 * public TEST ONLY vector or made up.
 */
class RecoveryTypedTest {
    private val production = "https://account.vettid.org"
    private val staging = "https://account.staging.vettid.org"

    // §16 (0.25.0), TEST ONLY.
    private val locator = "7ZK3M9QX2HTD"
    private val code = "CDEFGHJKMNPQRSTVWXYZ0123456789AB"
    private val typed = locator + code + "V3A1"
    private val grouped = "7ZK3 M9QX 2HTD CDEF GHJK MNPQ RSTV WXYZ 0123 4567 89AB V3A1"

    private fun ok(text: String, api: String = production): TypedRecoveryCode = (TypedRecoveryCode.parse(text, api) as TypedRecoveryCode.Parsed.Ok).code

    @Test
    fun theCheckCharactersMatchTheSpecsVectorForBothOrigins() {
        assertEquals("V3A1", RecoveryCodes.typedCheck(production, locator, code))
        assertEquals("J5JH", RecoveryCodes.typedCheck(staging, locator, code))
        // The hashed bytes: the 33-byte label, 0x00, the origin, 0x00, the 44 characters; the first 20 bits.
        val bytes = "vettid/vms/2/recovery-typed-check".toByteArray() + byteArrayOf(0) + production.toByteArray() + byteArrayOf(0) +
            (locator + code).toByteArray()
        assertEquals(33 + 1 + production.length + 1 + 44, bytes.size)
        assertEquals("d8d41816e4cf5ac83a89a6472d2ab0b63197207dc80e77e77bd95f50214ae1c4", Bytes.hex(Bytes.sha256(bytes)))
        // The origin in the portal's form: no trailing slash, lower case.
        assertEquals("V3A1", RecoveryCodes.typedCheck("$production/", locator, code))
        assertEquals("V3A1", RecoveryCodes.typedCheck("https://Account.VettID.org", locator, code))
        assertEquals(grouped, RecoveryCodes.grouped(typed))
        assertEquals(48, RecoveryCodes.TYPED_LENGTH)
        assertEquals(12, RecoveryCodes.TYPED_GROUPS)
        assertThrows(IllegalArgumentException::class.java) { RecoveryCodes.typedCheck(production, locator.dropLast(1), code) }
        assertThrows(IllegalArgumentException::class.java) { RecoveryCodes.typedCheck(production, locator, code.lowercase()) }
    }

    @Test
    fun typedTextIsNormalizedToThe48CanonicalCharacters() {
        assertEquals(typed, RecoveryCodes.normalizeTyped(typed))
        assertEquals("case", typed, RecoveryCodes.normalizeTyped(typed.lowercase()))
        assertEquals("spaces", typed, RecoveryCodes.normalizeTyped(grouped))
        assertEquals("hyphens", typed, RecoveryCodes.normalizeTyped(grouped.replace(' ', '-')))
        assertEquals("line breaks, tabs, a no-break space", typed, RecoveryCodes.normalizeTyped(" " + grouped.replace(" 0123", "\r\n0123\t").replace(" V3A1", " V3A1") + "\n"))
        // Crockford's look-alikes, which the canonical form never contains: I and L read as 1, O as 0, in either case.
        val alike = grouped.replace('1', 'I').replace('0', 'O')
        assertTrue(alike.contains('I') && alike.contains('O'))
        assertEquals(typed, RecoveryCodes.normalizeTyped(alike))
        assertEquals(typed, RecoveryCodes.normalizeTyped(grouped.replace('1', 'l').replace('0', 'o')))
        assertEquals(typed, RecoveryCodes.normalizeTyped(grouped.replace('1', 'L')))
        assertEquals(typed, RecoveryCodes.normalizeTyped(grouped.replace('1', 'i')))
        // U is not in the alphabet and is never mapped; any other character is refused the same way.
        assertNull(RecoveryCodes.normalizeTyped(typed.replace('V', 'U')))
        assertNull(RecoveryCodes.normalizeTyped(typed.replace('V', 'u')))
        for (c in listOf('!', '.', '_', '/', 'é', 'Ω', '٣')) assertNull("$c", RecoveryCodes.normalizeTyped(typed.dropLast(1) + c))
        // Lengths.
        assertNull(RecoveryCodes.normalizeTyped(""))
        assertNull(RecoveryCodes.normalizeTyped(typed.dropLast(1)))
        assertNull(RecoveryCodes.normalizeTyped(typed + "0"))
        assertNull("the bare code", RecoveryCodes.normalizeTyped(code))
        // The bare code's own rule is unchanged.
        assertEquals(code, RecoveryCodes.normalize(code.lowercase().chunked(4).joinToString("-")))
        assertNull(RecoveryCodes.normalize(typed))
        assertTrue(RecoveryCodes.isLocator(locator))
        assertFalse(RecoveryCodes.isLocator(locator.lowercase()))
        assertFalse(RecoveryCodes.isLocator(locator + "0"))
        assertFalse(RecoveryCodes.isLocator("7ZK3M9QX2HTU"))
    }

    @Test
    fun theFieldTakesWhatIsValidAndNamesTheFirstRefusedCharactersGroup() {
        val empty = RecoveryCodes.typedInput("")
        assertEquals("", empty.text)
        assertNull(empty.refused)
        assertEquals(0, empty.groups)

        val part = RecoveryCodes.typedInput("7zk3-m9qx 2h")
        assertEquals("7ZK3M9QX2H", part.text)
        assertNull(part.refused)
        assertEquals(2, part.groups)
        assertFalse(part.complete)

        assertEquals("look-alikes are read as typed", "1100", RecoveryCodes.typedInput("iLoO").text)

        // A U is dropped, not guessed to be a V: the 13th character would have gone into group 4.
        val u = RecoveryCodes.typedInput(locator + "u")
        assertEquals(locator, u.text)
        assertEquals(RecoveryCodes.Refused.LETTER_U, u.refused)
        assertEquals(4, u.refusedGroup)
        assertEquals(1, RecoveryCodes.typedInput("U").refusedGroup)
        assertEquals(1, RecoveryCodes.typedInput("7ZKU").refusedGroup)
        assertEquals(2, RecoveryCodes.typedInput("7ZK3U").refusedGroup)

        val other = RecoveryCodes.typedInput("7ZK3 M9!X")
        assertEquals("7ZK3M9X", other.text)
        assertEquals(RecoveryCodes.Refused.OTHER, other.refused)
        assertEquals(2, other.refusedGroup)

        // The first refused character is the one named; the valid ones of a paste are kept.
        val both = RecoveryCodes.typedInput("7ZK3 ?9QU 2HTD")
        assertEquals("7ZK39Q2HTD", both.text)
        assertEquals(RecoveryCodes.Refused.OTHER, both.refused)
        assertEquals(2, both.refusedGroup)

        val full = RecoveryCodes.typedInput(grouped.lowercase())
        assertEquals(typed, full.text)
        assertTrue(full.complete)
        assertEquals(12, full.groups)
        // A forty-ninth character is ignored, whatever it is.
        for (extra in listOf("0", "U", "!", "ABCD")) {
            val more = RecoveryCodes.typedInput(typed + extra)
            assertEquals(typed, more.text)
            assertNull(extra, more.refused)
        }
        assertFalse("the characters are never printed", full.toString().contains("7ZK3"))
    }

    @Test
    fun onlyAStringWhoseCheckMatchesThisAppsOriginBecomesACode() {
        val c = ok(grouped.lowercase())
        assertEquals(locator, c.locator)
        assertEquals(code, c.code)
        assertEquals(c, ok(typed))
        assertEquals(c.hashCode(), ok(typed).hashCode())
        assertFalse("never printed", c.toString().contains(locator) || c.toString().contains(code))
        assertEquals(c, ok(typed, "$production/"))

        // A staging string is refused by a production build, and the other way round (§11.11.10).
        val stagingTyped = locator + code + "J5JH"
        assertSame(TypedRecoveryCode.Parsed.BadCheck, TypedRecoveryCode.parse(stagingTyped, production))
        assertSame(TypedRecoveryCode.Parsed.BadCheck, TypedRecoveryCode.parse(typed, staging))
        assertEquals(c, ok(stagingTyped, staging))

        // One mistyped character anywhere: in the locator, in the code, in the check characters.
        for (i in listOf(0, 11, 12, 43, 44, 47)) {
            val wrong = typed.substring(0, i) + (if (typed[i] == 'Z') 'Y' else 'Z') + typed.substring(i + 1)
            assertSame("position $i", TypedRecoveryCode.Parsed.BadCheck, TypedRecoveryCode.parse(wrong, production))
        }
        // Two groups swapped.
        assertSame(TypedRecoveryCode.Parsed.BadCheck, TypedRecoveryCode.parse(typed.substring(4, 8) + typed.substring(0, 4) + typed.substring(8), production))

        // Lengths and characters: not a typed code at all.
        assertSame(TypedRecoveryCode.Parsed.NotTyped, TypedRecoveryCode.parse("", production))
        assertSame(TypedRecoveryCode.Parsed.NotTyped, TypedRecoveryCode.parse(typed.dropLast(1), production))
        assertSame(TypedRecoveryCode.Parsed.NotTyped, TypedRecoveryCode.parse(typed + "0", production))
        assertSame(TypedRecoveryCode.Parsed.NotTyped, TypedRecoveryCode.parse(typed.replace('V', 'U'), production))
        // The bare 32 characters an account page showed before MEMBER-API 2.4.0.
        assertSame(TypedRecoveryCode.Parsed.BareCode, TypedRecoveryCode.parse(code, production))
        assertSame(TypedRecoveryCode.Parsed.BareCode, TypedRecoveryCode.parse(RecoveryCodes.grouped(code).lowercase(), production))
    }

    @Test
    fun aTypedCodeGivesTheSameRecoveryCodeAsTheScannedQrOfThatRecovery() {
        val vid = "0123456789abcdef0123456789abcdef"
        val rid = "01JA0RECVERY0000000000001X"
        val scanned = RecoveryCode.parseScanned("""{"v":1,"t":"r","api":"$production","vault_id":"$vid","recovery_id":"$rid","code":"$code"}""")!!
        val claimed = Claimed("guid-1", "s***@example.org", vid, rid)
        val fromTyped = ok(typed).recoveryCode(claimed, production)
        assertEquals(scanned, fromTyped)
        assertEquals(scanned.api, fromTyped.api)
        assertEquals(scanned, ok(typed).recoveryCode(claimed, "$production/"))

        // The sealed register is built from these fields alone (AltRequests.buildRecoveryRegister), so its plaintext is
        // the same but for the request's own id, time and attestation.
        assertEquals(listOf(scanned.vaultId, scanned.recoveryId, scanned.code), listOf(fromTyped.vaultId, fromTyped.recoveryId, fromTyped.code))

        // An answer without the identifiers (an API before 2.4.0), or with ones a QR could not carry, is not used.
        assertThrows(AltResultException::class.java) { ok(typed).recoveryCode(Claimed("guid-1", "", null, rid), production) }
        assertThrows(AltResultException::class.java) { ok(typed).recoveryCode(Claimed("guid-1", "", vid, null), production) }
        assertThrows(AltResultException::class.java) { ok(typed).recoveryCode(Claimed("guid-1", "", vid, "not-a-ulid"), production) }
        assertThrows(AltResultException::class.java) { ok(typed).recoveryCode(Claimed("guid-1", "", "", rid), production) }
    }
}
