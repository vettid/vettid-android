package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * (A6 audit N3, review m1) [VaultManager] has no unit-test harness, so its source is read: the SHA-256 of the posted PIN
 * is zeroed and dropped before a new one is kept, through [EnrolmentResume.Ops.forgetPin] (the vault pinned, the request
 * dropped), and when the session goes; and the digest of a typed PIN is zeroed after the comparison.
 */
class EnrolmentPinMemoryTest {
    private val code: List<String> = File("src/main/kotlin/com/vettid/core/data/vault/VaultManager.kt").readLines()
        .map { it.substringBefore("//").trim() }
        .filterNot { it.startsWith("*") || it.startsWith("/*") }

    @Test
    fun thePostedPinsDigestIsZeroedAndDropped() {
        val forget = code.indexOfFirst { it == "private fun forgetPostedPin() {" }
        assertTrue(forget >= 0)
        assertEquals("postedEnrol?.second?.fill(0)", code[forget + 1])
        assertEquals("postedEnrol = null", code[forget + 2])
        assertTrue(code.any { it == "override fun forgetPin() = forgetPostedPin()" })
        // Before a new digest is kept.
        val set = code.indexOfFirst { it.startsWith("postedEnrol = d.pendingEnroll?.let") }
        assertEquals("forgetPostedPin()", code[set - 1])
        // When the session goes (a forget, a wipe, another code).
        val drop = code.indexOfFirst { it.startsWith("private suspend fun dropSession(") }
        val end = (drop + 1 until code.size).first { code[it].startsWith("private ") || code[it].startsWith("override ") }
        assertTrue(code.subList(drop, end).any { it == "forgetPostedPin()" })
        // The typed PIN's digest of a comparison is zeroed.
        assertTrue(code.any { it == "typed.fill(0)" })
        assertEquals("only these keep a digest", 1, code.count { it.startsWith("postedEnrol = d.pendingEnroll") })
    }
}
