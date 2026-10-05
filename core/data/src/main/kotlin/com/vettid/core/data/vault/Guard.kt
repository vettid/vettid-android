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
import java.io.IOException

/** Runs [block] on the IO dispatcher and turns every failure into a [VaultFailure]. */
@Suppress("CyclomaticComplexMethod", "ThrowsCount")
internal suspend fun <T> vaultGuard(block: suspend () -> T): T = try {
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
    throw VaultFailure(VaultManager.opFailure(e.code), e.code, cause = e)
} catch (e: AltRefusedException) {
    val kind = if (e.reason == AltRefusedException.Reason.ROLLBACK_RELEASE) FailureKind.ROLLBACK else FailureKind.MANIFEST
    throw VaultFailure(kind, e.reason.name.lowercase(), cause = e)
} catch (e: AttestationException) {
    throw VaultFailure(FailureKind.ATTESTATION, "enclave", cause = e)
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
} catch (e: IOException) {
    throw VaultFailure(FailureKind.NETWORK, cause = e)
} catch (e: KeystoreException) {
    throw VaultFailure(FailureKind.OTHER, "keystore", cause = e)
} catch (e: CryptoException) {
    throw VaultFailure(FailureKind.OTHER, "crypto", cause = e)
}
