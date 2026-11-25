package com.vettid.core.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `suites` a start sends under the test fault: another list than the signed one, still one the vault parses. */
class CallShareFaultsTest {
    private val on = CallShareFaults(suitesAfterSigning = true)

    @Test
    fun theSentListDiffersFromTheSignedOneAndStaysValid() {
        assertEquals(listOf(1), sentSuites(listOf(1), CallShareFaults.NONE))
        for (offered in listOf(listOf(1), listOf(2, 1), (1..7).toList(), (1..8).toList())) {
            val sent = sentSuites(offered, on)
            assertTrue("$offered: $sent differs", sent != offered)
            assertTrue("$offered: $sent at most 8", sent.size in 1..8)
            assertEquals("$offered: $sent no repeats", sent.size, sent.distinct().size)
            assertTrue("$offered: $sent in range", sent.all { it in 1..65_535 })
        }
    }

    @Test
    fun aConfirmChangesOnlyUnderTheFault() {
        val c = ByteArray(32) { 3 }
        assertTrue(sentConfirm(c, CallShareFaults.NONE).contentEquals(c))
        val wrong = sentConfirm(c, CallShareFaults(wrongConfirm = true))
        assertEquals(32, wrong.size)
        assertTrue(!wrong.contentEquals(c))
        assertTrue("the original is not changed", c.all { it == 3.toByte() })
    }
}
