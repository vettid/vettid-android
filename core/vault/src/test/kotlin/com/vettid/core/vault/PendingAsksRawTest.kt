package com.vettid.core.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `pending.list`'s entries and each one's `body` source text (ANDROID-PLAN 0.1.138 AA8 "Fail closed"). */
class PendingAsksRawTest {
    private fun read(json: String) = pendingAsks(VaultJson.parseObject(json.toByteArray()), json.toByteArray())

    @Test
    fun eachBodyKeepsItsExactTextByIndexPastANonObjectEntry() {
        val (asks, raws) = read("""{"asks":[1,{"kind":"approval","body":{"a": 1,"b":"x"}},{"kind":"session","body":{"c":2}}]}""")
        assertEquals(2, asks.size)
        assertEquals(listOf("""{"a": 1,"b":"x"}""", """{"c":2}"""), raws)
    }

    @Test
    fun anAnswerThatDoesNotReadStrictlyKeepsNoText() {
        val (asks, raws) = read("""{"asks":[{"kind":"approval","body":{"a":1,"a":2}},{"kind":"session","body":{"c":2}}]}""")
        assertEquals(2, asks.size)
        raws.forEach { assertNull(it) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPageRefusesTextsThatDoNotMatchItsEntries() {
        PendingPage(emptyList(), null, java.time.Instant.EPOCH, listOf("x"))
    }
}
