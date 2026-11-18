package com.vettid.core.data.vault

import com.vettid.core.vault.AccountSnapshot
import com.vettid.core.vault.VaultJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun withoutASnapshotNothingIsShown() {
        assertNull(AccountInfo("m***@example.com").shownInShell())
        assertNull(AccountInfo("m***@example.com").email)
    }
}
