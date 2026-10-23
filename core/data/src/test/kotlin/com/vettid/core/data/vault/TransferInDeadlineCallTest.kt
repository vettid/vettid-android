package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * (#189 recheck, A6 audit N1) [VaultManager] has no unit test harness, so its call site is read from the source: the
 * transfer-in wait goes through [TransferInResume.awaitApproval] with the device's stored transfer (its start, taken
 * just before the hs.init), and nothing in that call changes the start or keeps another one.
 */
class TransferInDeadlineCallTest {
    private val code: List<String> = File("src/main/kotlin/com/vettid/core/data/vault/VaultManager.kt").readLines()
        .map { it.substringBefore("//").trim() }
        .filterNot { it.startsWith("*") || it.startsWith("/*") }

    private fun body(signature: String): List<String> {
        val i = code.indexOfFirst { it.startsWith(signature) }
        assertTrue("found: $signature", i >= 0)
        val end = (i + 1 until code.size).first { code[it].startsWith("override ") || code[it].startsWith("private ") }
        return code.subList(i, end)
    }

    @Test
    fun theWaitCountsFromTheStoredStartOfTheHsInit() {
        val b = body("override suspend fun awaitTransferIn()")
        assertEquals(1, b.count { it.contains("TransferInResume.awaitApproval(") })
        assertTrue(b.any { it == "pending = { s.device.transferInPending }," })
        assertTrue(b.any { it == "now = Instant::now," })
        assertTrue(b.any { it == "atDeadline = { atTransferDeadline(s) }," })
        assertFalse("no start of its own", b.any { Regex("""\b(minus|plus)\w*\(""").containsMatchIn(it) })
        assertFalse("no in-memory start", code.any { it.contains("transferStartedAt") })
    }

    @Test
    fun aStartPastTheDeadlineCollectsBeforeAnythingElse() {
        val b = body("private suspend fun evaluate()")
        assertTrue(b.any { it.contains("TransferInResume.decide(pendingIn, d.deviceId, Instant.now())") })
        assertTrue(b.any { it.contains("TransferInResume.Action.DEADLINE -> atTransferDeadline(s)") })
    }
}
