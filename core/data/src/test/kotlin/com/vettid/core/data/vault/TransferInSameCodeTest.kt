package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
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
        // Decided and done under the device's lock (#335's blind reviews; tested in :core:vault, VaultDeviceTest).
        val alone = drop.indexOfFirst { it == "d.forgetIfTransferOpen()" }
        val keep = drop.indexOfFirst { it.contains("dropSessionLocked(forget = false)") }
        val stop = drop.indexOfFirst { it.contains("MoveRepository.CODE_TRANSFER_NOT_DROPPED") }
        val full = drop.indexOfFirst { it == "dropSessionLocked(forget)" }
        assertTrue("its forget alone, first", alone in 0 until full)
        assertTrue("a failed forget keeps the file, then stops", keep in alone + 1 until stop && stop < full)
        // A cancel is rethrown as itself, and keeps the session (L2; #335's Opus L1).
        val cancel = drop.indexOfFirst { it.startsWith("} catch (e: CancellationException)") }
        assertTrue("the cancel first", cancel in alone + 1 until keep)
        assertTrue("rethrown as itself", drop.subList(cancel + 1, keep).takeWhile { !it.startsWith("} catch") }.contains("throw e"))
        // Every key step is in dropSessionLocked, which only dropSession calls.
        assertTrue(code.count { it.contains("dropSessionLocked(") } == 3)
        assertFalse(code.any { it.contains("forgetOpenTransferOrStop") })
        // (#335's re-check) A device stopped by an unsaved drop is stale: a new scan drops it through the guard.
        val fresh = body("private suspend fun freshDeviceIfUnpaired() {")
        assertTrue(fresh.any { it.startsWith("val stale = ") && it.endsWith("|| d.stopped") })
        for (sig in listOf("private suspend fun freshDeviceIfUnpaired() {", "override suspend fun forgetSetupCode() {")) {
            assertTrue("$sig goes through the guard", body(sig).any { it.contains("dropSession(forget = true)") })
        }
        // (L1) After a forget that stopped, the file is loaded again first, so a second try is guarded too.
        val setup = body("override suspend fun forgetSetupCode() {")
        val load = setup.indexOfFirst { it == "guard { session() }" }
        assertTrue("loaded before it is judged", load in 0 until setup.indexOfFirst { it.startsWith("val paired") })
        // (The re-check of fd8f055d, Low-2) Only the known refusals to open a session go on; any other load failure stops.
        val stops = "if (!sessionRefused(e)) throw VaultFailure(FailureKind.LOCAL_STATE, CODE_DEVICE_NOT_LOADED, cause = e)"
        assertTrue(setup.any { it.startsWith(stops) })
    }

    /**
     * (#335's re-check, Opus L2) The 60 s ends "not answered" whatever the abandon's save did: its failure is only the
     * cause, and a cancel during it is rethrown.
     */
    @Test
    fun theSixtySecondsEndNotAnsweredWhateverTheDropDid() {
        val w = body("private suspend fun awaitTransferSas(d: com.vettid.core.vault.VaultDevice): String = try {")
        val abandon = w.indexOfFirst { it == "d.abandonTransfer()" }
        val cancel = w.indexOfFirst { it == "} catch (c: CancellationException) {" }
        val answer = w.indexOfFirst { it.contains("FailureKind.NO_RESPONSE, MoveRepository.CODE_HS_UNANSWERED, cause = dropped ?: e") }
        assertTrue("the abandon is caught", abandon in 0 until cancel)
        assertEquals("a cancel is rethrown", "throw c", w[cancel + 1])
        assertTrue("then not answered, always", answer > cancel)
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
