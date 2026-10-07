package com.vettid.core.data.vault

import com.vettid.core.vault.AccountSnapshot
import com.vettid.core.vault.VaultJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The snapshot's address (VAULT-MESSAGING 0.20.0 `email`, else the masked `email_hint` of an older vault). */
class AccountEmailTest {
    private fun snap(json: String): AccountSnapshot = VaultJson.json.decodeFromString(AccountSnapshot.serializer(), json)

    @Test
    fun theFullAddressWins() {
        val info = snap("""{"v":1,"state":"member","email":"ada@example.org","email_hint":"a***@example.org"}""").toInfo("")
        assertEquals("ada@example.org", info.email)
        assertEquals("ada@example.org", info.displayEmail)
    }

    @Test
    fun aSnapshotWithOnlyTheHintShowsTheHint() {
        val info = snap("""{"v":1,"state":"member","email_hint":"a***@example.org"}""").toInfo("")
        assertNull(info.email)
        assertEquals("a***@example.org", info.displayEmail)
    }

    @Test
    fun aSnapshotWithOnlyTheAddressReads() {
        // 0.20.0 replaces email_hint with email: the hint is absent, the stored redeem hint is the fallback.
        val info = snap("""{"v":1,"state":"member","email":"ada@example.org"}""").toInfo("a***@example.org")
        assertEquals("ada@example.org", info.displayEmail)
        assertEquals("a***@example.org", info.emailHint)
    }

    @Test
    fun anImplausibleAddressFallsBackToTheHint() {
        val info = snap("""{"v":1,"state":"member","email":"no-at-sign","email_hint":"a***@example.org"}""").toInfo("")
        assertNull(info.email)
        assertEquals("a***@example.org", info.displayEmail)
    }

    @Test
    fun nothingToShow() {
        assertNull(AccountInfo("").displayEmail)
        assertEquals("m***@example.com", AccountInfo("m***@example.com").displayEmail)
    }
}
