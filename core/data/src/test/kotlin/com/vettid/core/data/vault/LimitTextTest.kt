package com.vettid.core.data.vault

import com.vettid.core.data.R
import com.vettid.core.vault.VaultOpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** VAULT-MESSAGING 0.21.0 §10.1: a `limit` error names its limit, `{limit, max, size?}`, and the app says which. */
class LimitTextTest {
    private fun op(code: String, body: String?) =
        VaultOpException("item.put", code, "", body?.let { Json.parseToJsonElement(it).jsonObject })

    @Test
    fun theBodyIsReadOnlyForALimit() {
        assertEquals(VaultLimit("items", 2_000), limitOf(op("limit", """{"limit":"items","max":2000}""")))
        assertEquals(VaultLimit("item_size", 65_536, 70_000), limitOf(op("limit", """{"limit":"item_size","max":65536,"size":70000}""")))
        assertNull(limitOf(op("limit", null)))
        assertNull(limitOf(op("limit", """{"max":3}""")))
        assertNull(limitOf(op("conflict", """{"limit":"items","max":2000}""")))
        // Odd members are dropped, the name kept.
        assertEquals(VaultLimit("blocks"), limitOf(op("limit", """{"limit":"blocks","max":"many","size":-1}""")))
    }

    @Test
    fun everyNameOfTheTableHasItsOwnWords() {
        assertEquals(27, LIMIT_NAMES.size)
        val texts = LIMIT_NAMES.map { VaultLimit(it, 10).text().res }
        texts.forEach { assertNotEquals(R.string.data_failure_limit, it) }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun countsAndSizesAreFormatted() {
        assertEquals(LimitText(R.string.data_limit_items, listOf(2_000L)), VaultLimit("items", 2_000).text())
        // Sizes in kilobytes, rounded up; the refused size first when the vault gives it.
        assertEquals(LimitText(R.string.data_limit_item_size, listOf(64L)), VaultLimit("item_size", 65_536).text())
        assertEquals(LimitText(R.string.data_limit_item_size_of, listOf(69L, 64L)), VaultLimit("item_size", 65_536, 70_000).text())
        assertEquals(LimitText(R.string.data_limit_location_requests), VaultLimit("location_requests", 1).text())
    }

    @Test
    fun anUnknownNameOrNoBoundIsTheGenericText() {
        assertEquals(LimitText(R.string.data_failure_limit), VaultLimit("moons", 3).text())
        assertEquals(LimitText(R.string.data_failure_limit), VaultLimit("items").text())
    }
}
