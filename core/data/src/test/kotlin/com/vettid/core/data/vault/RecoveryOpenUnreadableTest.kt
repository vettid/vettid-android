package com.vettid.core.data.vault

import com.vettid.core.altchan.AltResults
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * `attempt_used` is REQUIRED in every `vault.recovery.open.result` (VAULT-MESSAGING §11.14.6): a result without it is an
 * answer the app cannot read (`unreadable_result`, "This attempt may have been used"), never one read as nothing used.
 */
class RecoveryOpenUnreadableTest {
    private val codes = listOf(
        "wrong", "wrong_again", "attempt_lost", "quiet", "wait", "busy", "exhausted", "closed", "not_armed", "stored_mismatch",
        "counter_unavailable", "no_recovery", "expired", "attestation", "manifest", "wrong_release", "retry", "bad_request",
        "state_unsupported", "state_unreadable", "device_secret", "vault_missing", "something_new",
    )

    private suspend fun codeOf(body: String): String? = try {
        vaultGuard { AltResults.recoveryOpen(body.toByteArray()) }
        fail("read: $body")
        null
    } catch (f: VaultFailure) {
        f.code
    }

    @Test
    fun aResultWithoutAttemptUsedIsUnreadable() = runTest {
        for (code in codes) {
            val base = """{"ok":false,"code":"$code","attempts":{"used":3,"of":10},"retry_at":"2026-10-17T12:00:00Z""""
            assertEquals(code, "unreadable_result", codeOf("$base}"))
            assertEquals(code, "unreadable_result", codeOf("""$base,"attempt_used":null}"""))
            // With it, the same result reads, as the vault said.
            assertEquals(code, false, vaultGuard { AltResults.recoveryOpen("""$base,"attempt_used":false}""".toByteArray()) }.attemptUsed)
            assertEquals(code, true, vaultGuard { AltResults.recoveryOpen("""$base,"attempt_used":true}""".toByteArray()) }.attemptUsed)
        }
        val ok = """{"ok":true,"state_seq":5,"header_seq":6,"token":"t","vault_bundle":"AAEC","release":"aa","release_number":13,""" +
            """"unlock_id":"00","attempts":{"used":1,"of":10}"""
        assertEquals("unreadable_result", codeOf("$ok}"))
        // Any other member that does not read is unreadable too.
        assertEquals("unreadable_result", codeOf("""{"ok":false,"attempt_used":false}"""))
        assertEquals("unreadable_result", codeOf("not json"))
    }
}
