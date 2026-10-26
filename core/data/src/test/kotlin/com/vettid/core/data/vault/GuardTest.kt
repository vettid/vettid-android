// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.vault

import com.vettid.core.altchan.MemberApiException
import com.vettid.core.attestation.AttestationException
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private suspend fun kindOf(e: Exception): FailureKind = try {
        vaultGuard { throw e }
        error("no failure")
    } catch (f: VaultFailure) {
        f.kind
    }

    /**
     * Owner report 2026-10-11: a vault on a release this app's manifest does not list read "This phone could not be
     * verified" (the text of the vault refusing the PHONE's attestation). The app's own checks of the vault's
     * software have their own kinds and texts.
     */
    @Test
    fun aFailedCheckOfTheVaultsSoftwareIsNeverThePhonesAttestation() = runTest {
        // The enclave's document verified, its release is in no manifest entry.
        assertEquals(FailureKind.RELEASE_UNKNOWN, kindOf(AttestationException.Release("not listed", unknown = true)))
        // The manifest did not verify.
        val manifest = listOf(
            AttestationException.Signature("manifest"), AttestationException.Format("manifest order"), AttestationException.Format("served manifest"),
            AttestationException.ManifestKey(), AttestationException.ManifestSerial(), AttestationException.Hash(),
        )
        manifest.forEach { assertEquals(it.message, FailureKind.MANIFEST, kindOf(it)) }
        // The enclave's attestation document or descriptor did not verify.
        val enclave = listOf(
            AttestationException.Signature("attestation"), AttestationException.Format("COSE_Sign1"), AttestationException.Format("descriptor"),
            AttestationException.Chain(), AttestationException.Pcr("release"), AttestationException.Pcr("manifest"), AttestationException.Debug(),
            AttestationException.Stale(), AttestationException.UserData(), AttestationException.Nonce(),
            AttestationException.Release("not active"), AttestationException.Release("rollback"),
        )
        enclave.forEach { assertEquals(it.message, FailureKind.ENCLAVE, kindOf(it)) }
        (manifest + enclave + AttestationException.Release("not listed", unknown = true)).forEach {
            assertNotEquals(it.message, FailureKind.ATTESTATION, kindOf(it))
        }
    }

    @Test
    fun aManifestThatCouldNotBeLoadedIsNotAVerificationFailure() = runTest {
        // The server answered without the document (HTTP 404, 500, 503).
        for (status in listOf(404, 500, 503)) {
            assertEquals(FailureKind.MANIFEST_UNAVAILABLE, kindOf(MemberApiException(status, MemberApiException.MANIFEST_UNAVAILABLE)))
        }
        // No answer at all: the network.
        assertEquals(FailureKind.NETWORK, kindOf(java.net.UnknownHostException("vettid.org")))
        assertEquals(FailureKind.NETWORK, kindOf(java.io.IOException("timeout")))
    }

    @Test
    fun everyCheckOfTheVaultsSoftwareHasItsOwnText() {
        val texts = listOf(
            FailureKind.ATTESTATION, FailureKind.MANIFEST, FailureKind.MANIFEST_UNAVAILABLE, FailureKind.RELEASE_UNKNOWN, FailureKind.ENCLAVE,
            FailureKind.NETWORK, FailureKind.ROLLBACK, FailureKind.TIME_SCALE,
        ).map { it.messageRes() }
        assertEquals(texts.size, texts.toSet().size)
        assertEquals(com.vettid.core.data.R.string.data_failure_release_unknown, FailureKind.RELEASE_UNKNOWN.messageRes())
        assertEquals(com.vettid.core.data.R.string.data_failure_enclave, FailureKind.ENCLAVE.messageRes())
        assertEquals(com.vettid.core.data.R.string.data_failure_manifest_unavailable, FailureKind.MANIFEST_UNAVAILABLE.messageRes())
    }

    /**
     * STAGING-TIME-SCALE §4 item 4: a release with a time scale this build refuses is said as "This vault's software is
     * not for this app. Contact VettID.", never as a network or manifest failure.
     */
    @Test
    fun aRefusedTimeScaleHasItsOwnKindAndText() = runTest {
        val e = com.vettid.core.altchan.AltRefusedException(com.vettid.core.altchan.AltRefusedException.Reason.TIME_SCALE)
        assertEquals(FailureKind.TIME_SCALE, kindOf(e))
        assertEquals(com.vettid.core.data.R.string.data_failure_time_scale, FailureKind.TIME_SCALE.messageRes())
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
