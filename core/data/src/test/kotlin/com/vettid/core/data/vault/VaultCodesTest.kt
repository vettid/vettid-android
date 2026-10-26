package com.vettid.core.data.vault

import com.vettid.core.altchan.MemberApiException
import com.vettid.core.relay.RelayException
import com.vettid.core.vault.VaultOpException
import com.vettid.core.vault.VaultStateException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * ANDROID-PLAN 0.1.57 §4 "Vault error codes the app does not map", 0.1.58 point 3: every code of the table keeps or gets
 * its handling; `credential_required` raises "This phone can't use your vault"; `forbidden` to a holder-only type is
 * "not the holder"; every other code is named by `error_vault_code`, checked against `[a-z0-9_]{1,40}`. Failures that are
 * not a vault answer keep their own texts.
 */
class VaultCodesTest {
    @Before
    @After
    fun noScreen() = CredentialRequired.clear()

    private suspend fun failureOf(e: Exception, resend: Boolean = true): VaultFailure = try {
        vaultGuard(resend) { throw e }
        error("no failure")
    } catch (f: VaultFailure) {
        f
    }

    private fun answer(code: String, type: String = "vault.owner-check", message: String = "", body: JsonObject? = null) =
        VaultOpException(type, code, message, body)

    @Test
    fun mappedCodesKeepTheirKinds() = runTest {
        val mapped = mapOf(
            "bad_pin" to FailureKind.BAD_PIN, "bad_password" to FailureKind.BAD_PASSWORD, "backoff" to FailureKind.BACKOFF,
            "credential_frozen" to FailureKind.CREDENTIAL_FROZEN, "rotation_required" to FailureKind.ROTATION_REQUIRED,
            "not_found" to FailureKind.NOT_FOUND, "unsupported_type" to FailureKind.NOT_SUPPORTED, "limit" to FailureKind.LIMIT,
            "stale_credential" to FailureKind.NO_RESPONSE, "utk_invalid" to FailureKind.NO_RESPONSE,
            "owner_check_required" to FailureKind.OWNER_CHECK_REQUIRED, "busy" to FailureKind.BUSY, "retry" to FailureKind.RETRY,
            "counter_unavailable" to FailureKind.COUNTER_UNAVAILABLE, "weak_password" to FailureKind.WEAK_PASSWORD,
        )
        for ((code, kind) in mapped) {
            val f = failureOf(answer(code))
            assertEquals(code, kind, f.kind)
            assertNull(code, f.shownCode)
        }
        assertNull(CredentialRequired.state.value)
    }

    /**
     * `credential_required`, with `{"reason": "no_holder"}` or without, on the daily check, a PIN change, a name change
     * and a credential unlock.
     */
    @Test
    fun credentialRequiredRaisesTheScreen() = runTest {
        val noHolder = JsonObject(mapOf("reason" to JsonPrimitive("no_holder")))
        for (type in listOf("vault.owner-check", "pin.change", "account.name.set", "credential.unlock")) {
            for (body in listOf(noHolder, null)) {
                CredentialRequired.clear()
                val f = failureOf(answer("credential_required", type, body = body))
                assertEquals(FailureKind.CREDENTIAL_REQUIRED, f.kind)
                assertNull(f.shownCode)
                assertNotNull(type, CredentialRequired.state.value)
            }
        }
    }

    @Test
    fun forbiddenOnAHolderOnlyTypeIsNotTheHolder() = runTest {
        for (type in VaultCodes.HOLDER_ONLY) {
            val f = failureOf(answer("forbidden", type))
            assertEquals(type, FailureKind.NOT_HOLDER, f.kind)
            assertNull(f.shownCode)
        }
        // Not holder-only (removing an app in Devices & agents): its screen's text, or the code.
        val other = failureOf(answer("forbidden", "device.remove"))
        assertEquals(FailureKind.OTHER, other.kind)
        assertEquals("forbidden", other.shownCode)
        assertNull(CredentialRequired.state.value)
    }

    @Test
    fun everyOtherCodeIsNamed() = runTest {
        val codes = listOf(
            "bad_request", "internal", "relay_error", "unavailable", "session_required", "denied", "approval_timeout",
            "too_soon", "exists", "signer_unconfirmed", "some_new_code", "utk_empty",
        )
        for (code in codes) {
            val f = failureOf(answer(code, message = "secret detail", body = JsonObject(mapOf("vault_id" to JsonPrimitive("v-1")))))
            assertEquals(code, FailureKind.OTHER, f.kind)
            assertEquals(code, f.shownCode)
        }
    }

    /** A code that fails the check shows as `unknown`; the response's message and body never show. */
    @Test
    fun aCodeThatFailsTheCheckIsUnknown() = runTest {
        for (code in listOf("Internal", "bad request", "a".repeat(41), "", "code-1", "é")) {
            assertEquals(code, VaultCodes.UNKNOWN, failureOf(answer(code, message = "Internal")).shownCode)
        }
        assertEquals("a".repeat(40), failureOf(answer("a".repeat(40))).shownCode)
        val f = failureOf(answer("weird code", message = "the message", body = JsonObject(mapOf("x" to JsonPrimitive("the body")))))
        assertEquals(VaultCodes.UNKNOWN, f.shownCode)
    }

    /** A transport error, a timeout, an HTTP 403, a `forbidden` from the relay: their own kinds, no code, no screen. */
    @Test
    fun failuresThatAreNotAVaultAnswerKeepTheirTexts() = runTest {
        val timeout = try {
            withTimeout(1) { delay(1_000) }
            error("no timeout")
        } catch (e: TimeoutCancellationException) {
            e
        }
        val failures = listOf(
            IOException("reset"), timeout, MemberApiException(403, "forbidden"), RelayException(403, "forbidden", ""),
            VaultStateException("vault.owner-check: no response"),
        )
        for (e in failures) {
            val f = failureOf(e)
            assertNull(e.toString(), f.shownCode)
            assertFalse(e.toString(), f.kind == FailureKind.NOT_HOLDER || f.kind == FailureKind.CREDENTIAL_REQUIRED)
        }
        assertNull(CredentialRequired.state.value)
    }

    /** **Try again** resends an idempotent read that failed: a vault that gained a holder in between answers `ok`. */
    @Test
    fun tryAgainResends() = runTest {
        var sends = 0
        var holder = false
        val first = runCatching {
            vaultGuard(resend = true) {
                sends++
                if (!holder) throw answer("credential_required", "account.get")
            }
        }
        assertEquals(FailureKind.CREDENTIAL_REQUIRED, (first.exceptionOrNull() as VaultFailure).kind)
        assertNotNull(CredentialRequired.state.value)
        // Still no holder: the screen comes back.
        CredentialRequired.tryAgain()
        assertEquals(2, sends)
        assertNotNull(CredentialRequired.state.value)
        holder = true
        CredentialRequired.tryAgain()
        assertEquals(3, sends)
        assertNull(CredentialRequired.state.value)
    }

    /**
     * By default nothing is kept (a request that carried a PIN or a password, or a write): Try again sends nothing, and
     * the screen that sent it asks for them again.
     */
    @Test
    fun tryAgainNeverResendsByDefault() = runTest {
        var sends = 0
        runCatching {
            vaultGuard {
                sends++
                throw answer("credential_required")
            }
        }
        assertEquals(false, CredentialRequired.state.value?.resends)
        CredentialRequired.tryAgain()
        assertEquals(1, sends)
        assertNull(CredentialRequired.state.value)
    }

    @Test
    fun clearTakesTheScreenAway() = runTest {
        failureOf(answer("credential_required"))
        assertTrue(CredentialRequired.state.value != null)
        CredentialRequired.clear()
        assertNull(CredentialRequired.state.value)
    }

    /**
     * The owner check an unlock sends before the app opens (unlockWithCheck, unlockCancellingRecovery) runs while the
     * phase is still Locked: its refusal stays and shows once the app opens. A lock, a sign-out or wipe, a setup or a
     * deletion takes it away.
     */
    @Test
    fun aRefusalDuringTheUnlocksCheckStaysUntilTheVaultIsNoLongerOpen() = runTest {
        val phase = PhaseHolder(AppPhase.Locked)
        failureOf(answer("credential_required"))
        phase.value = AppPhase.Locked // the unlock's own writes: still locked, no move
        assertNotNull(CredentialRequired.state.value)
        phase.value = AppPhase.Unlocked
        assertNotNull(CredentialRequired.state.value)
        for (end in listOf(AppPhase.Locked, AppPhase.SignedOut, AppPhase.Setup(SetupStage.NEW_VAULT), AppPhase.Deleting())) {
            phase.value = AppPhase.Unlocked
            failureOf(answer("credential_required"))
            if (end is AppPhase.Deleting) phase.deleting(end) else phase.value = end
            assertNull(end.toString(), CredentialRequired.state.value)
            if (end is AppPhase.Deleting) phase.endDeleting(AppPhase.Unlocked)
        }
    }
}
