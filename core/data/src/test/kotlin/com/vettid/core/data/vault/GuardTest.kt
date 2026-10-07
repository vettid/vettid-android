package com.vettid.core.data.vault

import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** [vaultGuard] turns a vault error into a [VaultFailure]; a `backoff` keeps its `retry_after` (VAULT-MESSAGING 0.17.0 §10.1). */
class GuardTest {
    private fun body(v: JsonPrimitive) = JsonObject(mapOf("retry_after" to v))

    private suspend fun failureOf(e: VaultOpException): VaultFailure = try {
        vaultGuard { throw e }
        fail("no failure")
        error("unreachable")
    } catch (f: VaultFailure) {
        f
    }

    @Test
    fun aBackoffCarriesRetryAfterIntoTheFailure() = runTest {
        val f = failureOf(VaultOpException("vault.owner-check", "backoff", body = body(JsonPrimitive(42))))
        assertEquals("backoff", f.code)
        assertEquals(42L, f.retryAfterSeconds)
    }

    @Test
    fun withoutAUsableRetryAfterTheWaitIsUnknown() = runTest {
        // A vault release before 0.17.0 sends no body; anything but a positive integer is ignored.
        assertEquals(0L, failureOf(VaultOpException("device.transfer.approve", "backoff")).retryAfterSeconds)
        assertEquals(0L, failureOf(VaultOpException("vault.owner-check", "backoff", body = body(JsonPrimitive("30")))).retryAfterSeconds)
        assertEquals(0L, failureOf(VaultOpException("vault.owner-check", "backoff", body = body(JsonPrimitive(-1)))).retryAfterSeconds)
    }
}
