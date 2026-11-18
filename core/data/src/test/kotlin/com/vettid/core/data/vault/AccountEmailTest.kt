package com.vettid.core.data.vault

import com.vettid.core.vault.AccountSnapshot
import com.vettid.core.vault.VaultJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot's address (VAULT-MESSAGING 0.20.0 §11.13: `email`, required; the snapshot carries no masked `email_hint`).
 * The masked hint of the code-redeem or recovery-claim answer is never shown in its place.
 */
class AccountEmailTest {
    private fun snap(json: String): AccountSnapshot = VaultJson.json.decodeFromString(AccountSnapshot.serializer(), json)

    @Test
    fun theFullAddressIsShown() {
        val info = snap("""{"v":1,"state":"member","email":"ada@example.org"}""").toInfo("a***@example.org")
        assertEquals("ada@example.org", info.shownInShell()?.email)
        // The stored redeem hint is kept for the unlock screen only.
        assertEquals("a***@example.org", info.emailHint)
    }

    @Test
    fun aSnapshotWithoutAnAddressShowsNone() {
        val info = snap("""{"v":1,"state":"member"}""").toInfo("a***@example.org")
        assertNull(info.shownInShell()?.email)
    }

    @Test
    fun anEmailHintInTheSnapshotIsIgnored() {
        val info = snap("""{"v":1,"state":"member","email_hint":"b***@example.org"}""").toInfo("a***@example.org")
        assertNull(info.email)
        assertEquals("a***@example.org", info.emailHint)
    }

    @Test
    fun anImplausibleAddressIsNotShown() {
        for (bad in listOf("no-at-sign", "@", "a\u0007@example.org")) {
            val info = snap("""{"v":1,"state":"member","email":"${bad.replace("\u0007", "\\u0007")}"}""").toInfo("a***@example.org")
            assertNull(bad, info.email)
        }
    }

    /** The shell's name and the line under it (top bar, avatar sheet, Settings card): never the masked hint. */
    @Test
    fun theShellsNameAndDetail() {
        val hint = "a***@example.org"
        val both = snap("""{"v":1,"state":"member","first_name":"Ada","last_name":"Lovelace","email":"ada@example.org"}""").toInfo(hint)
        assertEquals("Ada Lovelace", both.shellName())
        assertEquals("ada@example.org", both.shellDetail())
        val emailOnly = snap("""{"v":1,"state":"member","email":"ada@example.org"}""").toInfo(hint)
        assertEquals("ada@example.org", emailOnly.shellName())
        assertNull(emailOnly.shellDetail())
        val neither = snap("""{"v":1,"state":"member"}""").toInfo(hint)
        assertNull(neither.shellName())
        assertNull(neither.shellDetail())
        val invalid = snap("""{"v":1,"state":"member","email":"no-at-sign"}""").toInfo(hint)
        assertNull(invalid.shellName())
        assertNull(invalid.shellDetail())
        val namesInvalidEmail =
            snap("""{"v":1,"state":"member","first_name":"Ada","last_name":"Lovelace","email":"no-at-sign"}""").toInfo(hint)
        assertEquals("Ada Lovelace", namesInvalidEmail.shellName())
        assertNull(namesInvalidEmail.shellDetail())
        for (info in listOf(both, emailOnly, neither, invalid, namesInvalidEmail)) assertEquals(hint, info.emailHint)
    }

    /** §11.13 (0.21.0): the email's control characters are C0, DEL, C1, U+2028 and U+2029; each refuses it. */
    @Test
    fun theEmailsControlCharacters() {
        assertTrue(validEmail("ada@example.org"))
        assertTrue(validEmail("a@b"))
        // Not controls: a no-break space, a zero-width joiner and U+2027 (just below the separators) are allowed.
        for (c in listOf('\u00A0', '\u200D', '\u2027', '\u202A', '\u00A1')) {
            assertTrue("U+%04X".format(c.code), validEmail("ada${c}@example.org"))
        }
        for (c in listOf('\u0000', '\u0007', '\u001F', '\u007F', '\u0080', '\u009F', '\u2028', '\u2029')) {
            assertFalse("U+%04X".format(c.code), validEmail("ada${c}@example.org"))
        }
        assertFalse(validEmail("@a"))
        assertFalse(validEmail("ada.example.org"))
        // In a snapshot, an address with a separator is no address.
        assertNull(snap("{\"v\":1,\"state\":\"member\",\"email\":\"ada\\u2028@example.org\"}").toInfo("").email)
    }

    @Test
    fun withoutASnapshotNothingIsShown() {
        assertNull(AccountInfo("m***@example.com").shownInShell())
        assertNull(AccountInfo("m***@example.com").email)
    }
}
