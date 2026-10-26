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
        // The outbox is flushed in two places, both only while no lock is pending.
        assertEquals(2, count("""flushOutbox\(\)"""))
        val checks = listOf("lockGuard.admits()", "!lockKeeper.pending")
        val guarded = code.count { line -> line.contains("flushOutbox()") && checks.any { line.contains(it) } }
        assertEquals(2, guarded)
    }
}
