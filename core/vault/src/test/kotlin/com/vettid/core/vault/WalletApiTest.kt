package com.vettid.core.vault

import com.vettid.core.crypto.Base64s
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The wallet requests of W1d (VAULT-MESSAGING §10.18, 0.56.0): the `wallet.caps.raise` binding against the §16 vector, and the
 * shapes of `wallet.caps.set`, `wallet.caps.raise` and an importing `wallet.create`.
 */
class WalletApiTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // The §16 vector "§10.18 `wallet.caps.raise` binding": a vector that stops matching is a stop-and-report item.
    @Test
    fun theBindingIsTheSixteenVectorByteForByte() {
        val id = "01HXZ3N5Q8R2T4V6W9Y0B1C2D3"
        assertEquals(
            "9479e8f7e06134aab2e7fcfec8dc1ba7fd2ed5cde4be3553ccaf4e6cf9aa1259",
            hex(WalletCapsBinding.payloadSha256(id, 7, 500_000, 2_000_000)),
        )
        assertEquals(
            "7bcbbf3d027952bb1a7cbafba930b385732185b8f61d4b15218a8ee7a32d58ae",
            hex(WalletCapsBinding.payloadSha256(id, 7, null, 2_000_000)),
        )
    }

    @Test
    fun theBindingCoversTheWalletTheVersionAndBothValues() {
        val id = "01HXZ3N5Q8R2T4V6W9Y0B1C2D3"
        val base = hex(WalletCapsBinding.payloadSha256(id, 7, 500_000, 2_000_000))
        assertNotEquals(base, hex(WalletCapsBinding.payloadSha256("01HXZ3N5Q8R2T4V6W9Y0B1C2D4", 7, 500_000, 2_000_000)))
        assertNotEquals(base, hex(WalletCapsBinding.payloadSha256(id, 8, 500_000, 2_000_000)))
        assertNotEquals(base, hex(WalletCapsBinding.payloadSha256(id, 7, 500_001, 2_000_000)))
        assertNotEquals(base, hex(WalletCapsBinding.payloadSha256(id, 7, 500_000, null)))
        // `none` is its own value, not a number: both removed differs from either alone.
        assertNotEquals(
            hex(WalletCapsBinding.payloadSha256(id, 7, null, 2_000_000)),
            hex(WalletCapsBinding.payloadSha256(id, 7, null, null)),
        )
    }

    @Test
    fun capsSetNamesOnlyTheMembersGiven() {
        val both = capsSetBody("W", 3, 500, 2_000)
        assertEquals(setOf("wallet_id", "version", "per_tx_sats", "per_period_sats"), both.keys)
        assertEquals(500L, (both["per_tx_sats"] as JsonPrimitive).longOrNull)
        assertEquals(3L, (both["version"] as JsonPrimitive).longOrNull)
        val one = capsSetBody("W", 3, null, 2_000)
        assertEquals(setOf("wallet_id", "version", "per_period_sats"), one.keys)
        // A set never carries a null: a removal is `wallet.caps.raise`'s.
        assertFalse(capsSetBody("W", 3, 5, null).containsKey("per_period_sats"))
    }

    @Test
    fun capsSetRefusesAValueTheVaultWouldRefuse() {
        for (bad in listOf(0L, -1L, CAPS_MAX_SATS + 1)) {
            try {
                capsSetBody("W", 1, bad, null)
                fail("$bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(CAPS_MAX_SATS, (capsSetBody("W", 1, CAPS_MAX_SATS, null)["per_tx_sats"] as JsonPrimitive).longOrNull)
    }

    @Test
    fun capsRaiseSealsThePinThePasswordTheWalletAndTheHash() {
        val id = "01HXZ3N5Q8R2T4V6W9Y0B1C2D3"
        val sealed = capsRaiseSealed(id, 7, null, 2_000_000, "123456", "pw")
        assertEquals(setOf("pin", "password", "item_id", "payload_sha256"), sealed.keys)
        assertEquals("123456", str(sealed, "pin"))
        assertEquals("pw", str(sealed, "password"))
        assertEquals(id, str(sealed, "item_id"))
        assertEquals(
            "7bcbbf3d027952bb1a7cbafba930b385732185b8f61d4b15218a8ee7a32d58ae",
            hex(Base64s.decodeStd(str(sealed, "payload_sha256"))),
        )
    }

    @Test
    fun capsRaiseSendsBothValuesWithNullForNoLimit() {
        val o = capsRaiseOuter("W", 7, null, 2_000_000)
        assertEquals(setOf("wallet_id", "version", "per_tx_sats", "per_period_sats"), o.keys)
        assertEquals(JsonNull, o["per_tx_sats"])
        assertEquals(2_000_000L, (o["per_period_sats"] as JsonPrimitive).longOrNull)
        // The PIN and the password are in the sealed payload only.
        assertFalse(o.keys.any { it == "pin" || it == "password" })
    }

    @Test
    fun anImportCarriesTheWordsAndPassphraseInTheSealedPayload() {
        val item = WalletImport("abandon ability able".toCharArray(), "Correct Horse".toCharArray())
        val sealed = createSealed("pw", item)
        assertEquals("pw", str(sealed, "password"))
        val inner = sealed["item"] as JsonObject
        assertEquals("abandon ability able", str(inner, "mnemonic"))
        assertEquals("Correct Horse", str(inner, "passphrase"))
        assertEquals("WalletImport", item.toString())
    }

    @Test
    fun aNewWalletHasNoItemAndAnEmptyPassphraseIsLeftOut() {
        assertEquals(setOf("password"), createSealed("pw", null).keys)
        val inner = createSealed("pw", WalletImport("a b".toCharArray(), CharArray(0)))["item"] as JsonObject
        assertNull(inner["passphrase"])
        assertTrue(inner.containsKey("mnemonic"))
    }

    private fun str(o: JsonObject, k: String) = (o[k] as JsonPrimitive).content
}
