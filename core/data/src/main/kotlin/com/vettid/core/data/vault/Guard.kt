package com.vettid.core.data.vault

import com.vettid.core.altchan.AltChannelException
import com.vettid.core.altchan.AltRefusedException
import com.vettid.core.altchan.AltResultException
import com.vettid.core.altchan.MemberApiException
import com.vettid.core.attestation.AttestationException
import com.vettid.core.crypto.CryptoException
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.relay.RelayException
import com.vettid.core.vault.NotATransferCodeException
import com.vettid.core.vault.PairingRejectedException
import com.vettid.core.vault.VaultOpException
import com.vettid.core.vault.VaultStateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/**
 * Runs [block] on the IO dispatcher and turns every failure into a [VaultFailure]. (ANDROID-PLAN 0.1.57 (1)) A vault
 * answer `credential_required` also raises "This phone can't use your vault" ([CredentialRequired]). [resend]: only for
 * an idempotent read that carries no PIN or password, whose **Try again** runs [block] again. By default nothing is
 * kept: Try again closes the screen, and the screen that sent the request (its secret fields empty) sends it again, so
 * that no secret is ever held for a resend and no write is repeated unseen.
 */
@Suppress("CyclomaticComplexMethod", "ThrowsCount")
internal suspend fun <T> vaultGuard(resend: Boolean = false, block: suspend () -> T): T = try {
    withContext(Dispatchers.IO) { block() }
} catch (e: VaultFailure) {
    throw e
} catch (e: TimeoutCancellationException) {
    throw VaultFailure(FailureKind.NO_RESPONSE, cause = e)
} catch (e: CancellationException) {
    throw e
} catch (e: MemberApiException) {
    throw VaultFailure(VaultManager.memberFailure(e), e.code, e.retryAfterSeconds.toLong(), e)
} catch (e: VaultOpException) {
    val kind = VaultManager.opFailure(e.code, e.type)
    if (kind == FailureKind.CREDENTIAL_REQUIRED) CredentialRequired.raise(if (resend) suspend { vaultGuard(true, block) } else null)
    throw VaultFailure(kind, e.code, retryAfterOf(e), e, limitOf(e))
} catch (e: AltRefusedException) {
    val kind = when (e.reason) {
        AltRefusedException.Reason.ROLLBACK_RELEASE -> FailureKind.ROLLBACK
        AltRefusedException.Reason.TIME_SCALE -> FailureKind.TIME_SCALE
        AltRefusedException.Reason.ROUTE_PENDING -> FailureKind.ROUTE_PENDING
        else -> FailureKind.MANIFEST
    }
    throw VaultFailure(kind, e.reason.name.lowercase(), cause = e)
} catch (e: AttestationException) {
    throw VaultFailure(enclaveFailure(e), "enclave", cause = e)
} catch (e: AltResultException) {
    throw VaultFailure(FailureKind.OTHER, "unreadable_result", cause = e)
} catch (e: AltChannelException) {
    throw VaultFailure(FailureKind.NO_RESPONSE, cause = e)
} catch (e: VaultStateException) {
    throw VaultFailure(if (e.message?.contains("no response") == true) FailureKind.NO_RESPONSE else FailureKind.OTHER, cause = e)
} catch (e: PairingRejectedException) {
    throw VaultFailure(FailureKind.REJECTED, "rejected", cause = e)
} catch (e: NotATransferCodeException) {
    throw VaultFailure(if (e.expired) FailureKind.INVITE_EXPIRED else FailureKind.INVITE_INVALID, "not_transfer", cause = e)
} catch (e: RelayException) {
    throw VaultFailure(FailureKind.NETWORK, cause = e)
} catch (e: com.vettid.core.vault.CredentialUnsettledException) {
    // (§11.14.9) Refused before anything was sent: an earlier credential request may still apply, a change or a renew
    // went out while this operation waited, or the vault's blob could not be made current.
    throw VaultFailure(FailureKind.CREDENTIAL_UNSETTLED, ChangeSender.CODE_UNSETTLED, cause = e)
} catch (e: IOException) {
    throw VaultFailure(FailureKind.NETWORK, cause = e)
} catch (e: KeystoreException) {
    throw VaultFailure(FailureKind.OTHER, "keystore", cause = e)
} catch (e: CryptoException) {
    throw VaultFailure(FailureKind.OTHER, "crypto", cause = e)
}

/**
 * What a failed check of the vault's software means for the member (VAULT-MESSAGING §11.2, §11.10.1). None of these
 * is [FailureKind.ATTESTATION], which is the vault refusing this PHONE's attestation: here the app refused the
 * vault's side, before anything was sealed to it.
 */
internal fun enclaveFailure(e: AttestationException): FailureKind = when {
    // The enclave's document verified; its release is in no entry of the manifest this app trusts.
    e is AttestationException.Release && e.unknown -> FailureKind.RELEASE_UNKNOWN
    // The manifest itself did not verify (signature, key, format, serial, hash).
    e.manifest -> FailureKind.MANIFEST
    // The enclave's attestation document or descriptor did not verify.
    else -> FailureKind.ENCLAVE
}

/**
 * A vault error's `retry_after` (VAULT-MESSAGING 0.17.0 §10.1: a `backoff` error's body is `{retry_after}`, the
 * seconds until the backoff ends); 0 when absent (a vault release before 0.17.0) or not a positive integer.
 */
internal fun retryAfterOf(e: VaultOpException): Long =
    (e.body?.get("retry_after") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 } ?: 0

/**
 * A `limit` error's limit (VAULT-MESSAGING 0.21.0 §10.1: the body `{limit: <name>, max, size?}`); null for another
 * error or a body without a name (a vault release before 0.21.0).
 */
@Suppress("ReturnCount")
internal fun limitOf(e: VaultOpException): VaultLimit? {
    if (e.code != "limit") return null
    val b = e.body ?: return null
    val name = (b["limit"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
    fun n(k: String) = (b[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
    return VaultLimit(name, n("max"), n("size"))
}
