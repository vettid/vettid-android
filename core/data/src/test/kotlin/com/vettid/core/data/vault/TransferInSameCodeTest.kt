package com.vettid.core.data.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * (VAULT-MESSAGING 0.88.3 §11.14.10 rule 5, owner decision a, GC #6474) [VaultManager] has no unit test harness, so the
 * same-code path is read from the source, as [TransferInDeadlineCallTest] does: the check runs before the settlement
 * window's refusal and before the enrolment's own path (its deadline refusal, fetch and drop), and it never drops the open
 * transfer: no abandon of any kind (the review of 1cfdc080, H1), the stored SAS first (also after a restart).
 */
class TransferInSameCodeTest {
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
    fun theSameCodeIsCheckedBeforeEveryRefusalAndEveryFetch() {
        val b = body("override suspend fun transferIn(code: String): String {")
        val same = b.indexOfFirst { it.contains("sameTransferCode(code)") }
        val settling = b.indexOfFirst { it.contains("settlements.active") }
        val enrol = b.indexOfFirst { it.contains("enrolments.transferIn(code)") }
        assertTrue("checked", same >= 0)
        assertTrue("before the window's refusal", same < settling)
        assertTrue("before the enrolment's path (deadline, fetch, drop)", same < enrol)
    }

    @Test
    fun theSameCodeNeverDropsAndReadsTheStoredSasFirst() {
        val b = body("private suspend fun sameTransferCode(code: String): String? = guard {")
        assertFalse("no abandon of any kind", b.any { it.contains("abandon") })
        assertFalse("no forget or fresh device", b.any { it.contains("freshDeviceIfUnpaired") || it.contains("dropSession") })
        val stored = b.indexOfFirst { it.contains("transferInPending?.sas") }
        val memory = b.indexOfFirst { it.contains("pairingSas.value") }
        assertTrue("the record's SAS first, then memory's", stored in 0 until memory)
        assertTrue("the session is opened, not only read", b.any { it.contains("session().device") })
    }
}
