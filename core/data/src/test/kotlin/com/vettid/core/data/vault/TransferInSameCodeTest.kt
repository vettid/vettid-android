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
        assertTrue("the record's SAS first, then memory's", b.any { it.contains("d.transferInPending?.sas ?: d.pairingSas.value") })
        assertTrue("the session is opened, not only read", b.any { it.contains("session().device") })
    }

    /**
     * (The blind reviews of #334, item 2; the re-checks, Fable N3) Every forget of a device that carries an open transfer
     * forgets the device alone first, in [VaultManager.dropSession] itself, so every caller gets it ([forgetSetupCode]
     * too): a failed forget drops the session without forgetting and stops, before any key is deleted.
     */
    @Test
    fun everyForgetOfAnOpenTransferForgetsTheDeviceAloneFirst() {
        val drop = body("private suspend fun dropSession(forget: Boolean) = mutex.withLock {")
        // An open transfer, or a scan still under way (the review of c58c8fc3, L3).
        val guard = drop.indexOfFirst { it.contains("it.transferInPending != null || it.transferScanOpen") }
        val alone = drop.indexOfFirst { it == "open.forget()" }
        val keep = drop.indexOfFirst { it.contains("dropSessionLocked(forget = false)") }
        val stop = drop.indexOfFirst { it.contains("MoveRepository.CODE_TRANSFER_NOT_DROPPED") }
        val full = drop.indexOfFirst { it == "dropSessionLocked(forget)" }
        assertTrue("the open transfer's guard", guard >= 0)
        assertTrue("its forget alone, first", alone in guard + 1 until full)
        assertTrue("a failed forget keeps the file, then stops", keep in alone + 1 until stop && stop < full)
        // The body itself never forgets an open transfer unguarded: every key step is in dropSessionLocked, which only
        // dropSession calls.
        assertTrue(code.count { it.contains("dropSessionLocked(") } == 4)
        // A cancel is rethrown as itself, never turned into a storage failure (L2).
        val cancel = drop.indexOfFirst { it.startsWith("} catch (e: CancellationException)") }
        assertTrue("the cancel first", cancel in alone + 1 until stop)
        assertTrue("rethrown as itself", drop.subList(cancel + 1, stop).takeWhile { !it.startsWith("} catch") }.contains("throw e"))
        assertFalse(code.any { it.contains("forgetOpenTransferOrStop") })
        for (sig in listOf("private suspend fun freshDeviceIfUnpaired() {", "override suspend fun forgetSetupCode() {")) {
            assertTrue("$sig goes through the guard", body(sig).any { it.contains("dropSession(forget = true)") })
        }
        // (L1) After a forget that stopped, the file is loaded again first, so a second try is guarded too.
        val setup = body("override suspend fun forgetSetupCode() {")
        val load = setup.indexOfFirst { it == "guard { session() }" }
        assertTrue("loaded before it is judged", load in 0 until setup.indexOfFirst { it.startsWith("val paired") })
        // (The re-check of fd8f055d, Low-2) Only the known refusals to open a session go on; any other load failure stops.
        val stops = "if (!sessionRefused(e)) throw VaultFailure(FailureKind.LOCAL_STATE, MoveRepository.CODE_TRANSFER_NOT_DROPPED"
        assertTrue(setup.any { it.startsWith(stops) })
    }

    /** (The blind reviews of #334, item 3) The start-up sweep of an orphaned ("", 1) key only while the phone holds no vault. */
    @Test
    fun theStartUpSweepIsSkippedWhileThePhoneHoldsAVault() {
        val i = code.indexOfFirst { it.startsWith("device.settleTransferKeysAtStart(") }
        assertTrue(i >= 0)
        assertTrue(code[i + 1].contains("sweep = !local.setupComplete") && code[i + 1].contains("it.deviceId != null"))
    }

    /** (The re-checks of #334, item 1) Both same-code paths read the code whatever its exp, never through parseTransfer. */
    @Test
    fun theSameCodePathsIgnoreTheCodesExp() {
        for (sig in listOf(
            "private suspend fun sameTransferCode(code: String): String? = guard {",
            "override suspend fun isOpenTransferCode(code: String): Boolean = guard {",
        )) {
            val b = body(sig)
            assertTrue("$sig: whatever its exp", b.any { it.contains("InviteLinks.transferLinkAnyExp(code)") })
            assertFalse("$sig: no expiry refusal", b.any { it.contains("parseTransfer") })
        }
    }
}
