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
        assertEquals(listOf("LockTimeUse.LOCK", "LockTimeUse.CALL_END"), uses.sortedDescending())
        assertEquals("and nowhere else (the declaration is the third)", 3, count("""\blockTimeApi\("""))
        assertEquals(setOf(LockTimeUse.LOCK, LockTimeUse.CALL_END), LockTimeUse.entries.toSet())
        // The lock is the only thing sent with the first, the hang-up accessor the only user of the second.
        assertTrue(code.any { it.contains("lockTimeApi(LockTimeUse.LOCK).lock()") })
        assertTrue(code.any { it.contains("fun vaultApiForCallEnd(): VaultApi = session().lockTimeApi(LockTimeUse.CALL_END)") })
    }
}
