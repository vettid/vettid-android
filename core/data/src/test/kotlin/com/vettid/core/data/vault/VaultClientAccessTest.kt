package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review L1 of #127: in [VaultManager] the vault's typed client is reachable through two accessors only, and both
 * ask the lock's rules. The compiler holds most of it (the client is a private member of the session); this reads
 * the source for the rest, so that a change which opens another way fails here and is looked at.
 */
class VaultClientAccessTest {
    /** The manager's source without comments. */
    private val code: List<String> = File("src/main/kotlin/com/vettid/core/data/vault/VaultManager.kt").readLines()
        .map { it.substringBefore("//").trim() }
        .filterNot { it.startsWith("*") || it.startsWith("/*") }

    private fun count(pattern: String): Int = code.sumOf { Regex(pattern).findAll(it).count() }

    @Test
    fun theClientIsAPrivateMemberOfTheSessionReadByItsTwoAccessorsOnly() {
        assertEquals("one client is made", 1, count("""\bVaultApi\("""))
        assertEquals(1, count("""private val client: VaultApi"""))
        // The declaration, `openApi` and `lockTimeApi`.
        assertEquals(3, count("""\bclient\b"""))
        assertEquals("both accessors ask the guard", 2, count("""if \(guard\(\)\.admits\((use)?\)\) client else throw"""))
        assertEquals("no other name for it", 0, count("""\.api\b"""))
    }

    @Test
    fun theLockTimeAccessorIsUsedForTheLockAndTheHangUpOnly() {
        val uses = code.flatMap { Regex("""lockTimeApi\((LockTimeUse\.\w+)\)""").findAll(it).map { m -> m.groupValues[1] } }
        // The lock, and (release L, "Lock it now") the status read that names the opening the lock is bound to.
        assertEquals(listOf("LockTimeUse.LOCK_STATUS", "LockTimeUse.LOCK", "LockTimeUse.CALL_END"), uses.sortedDescending())
        assertEquals("and nowhere else (the declaration is the fourth)", 4, count("""\blockTimeApi\("""))
        assertTrue(code.any { it.contains("s.lockTimeApi(LockTimeUse.LOCK_STATUS).status() }.unlockId") })
        assertEquals(setOf(LockTimeUse.LOCK, LockTimeUse.CALL_END, LockTimeUse.LOCK_STATUS), LockTimeUse.entries.toSet())
        // The lock (and its status read) is all that is sent with the first, the hang-up accessor the only user of the second.
        assertTrue(code.any { it.contains("lockTimeApi(LockTimeUse.LOCK).lock(unlockId = unlockId)") })
        // The second sends one `call.end` and hands nothing out.
        assertTrue(code.any { it.contains("= session().lockTimeApi(LockTimeUse.CALL_END).callEnd(callId, reason)") })
        // The only way a client leaves the manager is vaultApi(), which refuses while a lock is pending.
        assertEquals(1, count("""suspend fun vaultApi\(\): VaultApi = openApi\(\)"""))
        // Typed as the client: the two accessors of the session, and the manager's own openApi() over the first.
        assertEquals(3, count("""\): VaultApi\b"""))
    }

    /**
     * The device under the client sends too. The manager does not send operations through it directly: every one
     * would pass by the lock's rules. (What it does call on the device is not an operation on the vault: the outbox,
     * the collector, the device's own state.)
     */
    @Test
    fun nothingIsSentThroughTheDeviceDirectly() {
        assertEquals(0, count("""\bdevice\??\.(op|opOnce|send|sendOnce|request|requestOnce)\("""))
        assertEquals(0, count("""\bd\.(op|opOnce|send|sendOnce|request|requestOnce)\("""))
        // (ANDROID-PLAN §4 "While pending") The device asks the lock gate before each deposit of an outbox entry, for every
        // flush, its own collection start's included; the manager installs it on every session's device.
        val gate = Regex("""device\.sendGate\s*=.*UNGATED_TYPES.*lockGuard\.admits\(\)""")
        assertTrue(code.any { gate.containsMatchIn(it) })
        // The outbox is flushed in two places, each with the stop count taken where the flush is asked for (before its
        // launch), so that a stop after the ask wins. The unlock's flush waits for no lock pending; the foreground's is left to
        // the device's gate, which decides by type, so that a pending `call.end` goes on the return to the foreground.
        assertEquals(2, count("""flushOutbox\("""))
        assertEquals(2, count("""flushOutbox\((seq|flushSeq)\)"""))
        val foreground = "d.stopCount.let { seq -> scope.launch { runCatching { d.flushOutbox(seq) } } }"
        assertTrue(code.any { it == foreground })
        assertTrue(code.any { it.contains("val flushSeq = s.device.stopCount") })
        val checks = listOf("lockGuard.admits()", "!lockKeeper.pending")
        val guarded = code.count { line -> line.contains("flushOutbox(") && checks.any { line.contains(it) } }
        assertEquals("the unlock's only", 1, guarded)
    }

    /** A send the device's gate held (`lock_pending`) is not a lost answer: a recovered phone does not unlock after it. */
    @Test
    fun aHeldSendIsNotTakenForNoAnswer() {
        assertTrue(code.any { it.contains("if (!unlockFirst || e.code == VaultDevice.CODE_LOCK_PENDING) throw e") })
    }

    /**
     * (ANDROID-PLAN §4 "While pending") The device holds what it would send again of its own accord while a lock is pending,
     * except for a lock-time call: the lock and its status read run marked [com.vettid.core.vault.VaultDevice.LockTimeSend], so
     * the status read's re-sends pass as its first send did. Nothing else is marked.
     */
    @Test
    fun theLockTimeUsesAreMarkedForTheDevice() {
        assertTrue(code.any { it.contains("lockTimeIo { s.lockTimeApi(LockTimeUse.LOCK_STATUS).status() }") })
        assertTrue(code.any { it.contains("lockTimeIo { s.lockTimeApi(LockTimeUse.LOCK).lock(unlockId = unlockId) }") })
        assertTrue(code.any { it.contains("withContext(Dispatchers.IO + VaultDevice.LockTimeSend)") })
        assertEquals("the declaration and the two uses", 3, count("""\blockTimeIo\b"""))
        assertEquals("only there", 1, count("""\bLockTimeSend\b"""))
    }

    /**
     * (ANDROID-PLAN §4 "While pending") The unlock runs while this phone is held locked (a lock pending, or "opened again"): the
     * block it runs under [LockKeeper.unlocking], and the unlock it calls, send nothing over the relay (no request, no client):
     * the unlock goes over the member API, and the gate opens once it opened the vault.
     */
    @Test
    fun theUnlockSendsNothingOverTheRelay() {
        val start = code.indexOfFirst { it.startsWith("lockKeeper.unlocking(") }
        val end = (start until code.size).first { code[it].startsWith("} catch (e: VaultFailure) {") }
        assertTrue(start in 0 until end)
        val sends = """\b(request|requestOnce|op|opOnce|send|sendOnce|requestWithId|sendKept)\("""
        val forbidden = Regex("""$sends|\bopenApi\b|\blockTimeApi\(|\bvaultApi\(""")
        val block = code.subList(start, end)
        assertTrue(block.filter { forbidden.containsMatchIn(it) }.toString(), block.none { forbidden.containsMatchIn(it) })
        // The unlock it calls (one attempt with this phone's secret), whole.
        val once = code.indexOfFirst { it.startsWith("private suspend fun unlockWithSecretOnce(") }
        val starts = listOf("private ", "internal ", "override ", "fun ", "suspend fun ")
        val onceEnd = (once + 1 until code.size).first { i -> starts.any { code[i].startsWith(it) } }
        val body = code.subList(once, onceEnd)
        assertTrue(body.filter { forbidden.containsMatchIn(it) }.toString(), body.none { forbidden.containsMatchIn(it) })
        assertTrue(block.any { it.contains("unlockWithSecret(") })
    }
}
