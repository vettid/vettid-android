package com.vettid.core.vault

import com.vettid.core.crypto.envelope.Inner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Duration
import java.time.Instant

// VAULT-MESSAGING 0.44.0 §11.14.9 "A lost answer": a `pin.change` or `credential.password.change` whose answer is lost
// is decided, not repeated. The request is kept in memory only (the same inner id and body, resent while its `exp` has
// not passed), and a marker without any secret is stored before it is sent.

/** The two credential operations a lost answer is decided for (§11.14.9). */
object ChangeTypes {
    const val PIN_CHANGE = "pin.change"
    const val PASSWORD_CHANGE = "credential.password.change"

    /** Never retried with the typed secrets after `stale_credential` ([CredentialOps.credOp]). */
    val ALL: Set<String> = setOf(PIN_CHANGE, PASSWORD_CHANGE)

    /** A change request's `exp`: 10 minutes after the vault's clock as last seen (§11.14.9 step 1). */
    val EXP: Duration = Duration.ofMinutes(10)
}

/**
 * A credential operation about to be sent (VAULT-MESSAGING 0.45.0 §11.14.9 step 5): its type, inner id, the blob version
 * it carries and its `exp`. A renew's is stored before the send, so that a lost answer can be decided.
 */
data class SentRequest(val type: String, val innerId: String, val sentVersion: Long?, val exp: Instant)

/**
 * What the app stores durably before it sends a change (§11.14.9 step 1): the type, the inner id, the blob version the
 * request carries and the request's `exp`. No PIN, password or secret.
 */
data class ChangeMarker(val type: String, val innerId: String, val sentVersion: Long, val exp: Instant)

/**
 * A change request as it was sent: the same inner id and body (the sealed payload and the blob) go out again in a new
 * envelope until the change is decided. Held in memory only, never written to the phone's storage.
 */
class KeptChange(
    val type: String,
    val innerId: String,
    /** The outer body as sent (the sealed payload and the blob); resent unchanged. */
    val body: JsonObject,
    val exp: Instant,
    val sentVersion: Long,
) {
    /** The vault's answer to any of the sends, whenever it comes ([VaultDevice.sendKept]). */
    internal val answer = CompletableDeferred<Inner>()

    /** Stops the device awaiting [answer] (set by [VaultDevice.sendKept]). */
    @Volatile
    internal var release: (() -> Unit)? = null

    /** The change is decided (by `credential.version` or an unlock): its answer is no longer awaited. */
    fun forget() {
        release?.invoke()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun answered(): VaultMessage? = if (answer.isCompleted) VaultMessage(answer.getCompleted()) else null

    val marker: ChangeMarker get() = ChangeMarker(type, innerId, sentVersion, exp)

    override fun toString(): String = "KeptChange($type, $innerId)"
}

/**
 * No answer to a change after its automatic resends (§11.14.9 step 2): not a failure. [kept] is what the app resends
 * while it decides the change.
 */
class ChangeUnansweredException(val kept: KeptChange, cause: Throwable? = null) :
    IOException("vault client: ${kept.type}: no answer yet", cause)

/**
 * (§11.14.9, M1) A credential operation of this phone may still reach the vault (unanswered, before its `exp`): no PIN or
 * password change is sent until it is settled, so that "version + 1" can only mean that the change applied. Nothing
 * was sent. (0.47.0) Also: the vault's credential version differs from the held blob's and the latest blob could not be
 * fetched, or came with another version (an earlier operation applied without its answer).
 */
class CredentialUnsettledException(cause: Throwable? = null) :
    IOException("vault client: an earlier credential operation is not settled", cause)

// --- the client's side (VaultApi extensions, as ReleaseRApi.kt) ---

/** Before every credential operation: decides an unresolved change first, or throws ([CredentialOps.gate]). */
fun VaultApi.setCredentialGate(gate: (suspend (carriesPin: Boolean) -> Unit)?) {
    credentialOps.gate = gate
}

/** [kept] sent again with the same id and body, once, while its `exp` has not passed; its answer, or null. */
suspend fun VaultApi.changeResend(kept: KeptChange): VaultMessage? = credentialOps.resend(kept)

/** An answer to [kept] decides it: the new blob kept, or the refusal thrown ([CredentialOps.settle]). */
suspend fun VaultApi.changeSettle(kept: KeptChange, answer: VaultMessage) {
    credentialOps.settle(kept, answer)
}

/** `credential.version` (§10.6) with the answer's `ts`, the vault's clock. */
suspend fun VaultApi.credentialVersionAt(): Pair<CredentialInfo, Instant> {
    val r = device.request("credential.version")
    if (r.inner.status != Inner.STATUS_OK) {
        val e = r.inner.error
        throw VaultOpException("credential.version", e?.code ?: "error", e?.message ?: "", r.body.takeIf { it.isNotEmpty() })
    }
    return VaultJson.decode(CredentialInfo.serializer(), r.body) to r.ts
}

/**
 * (§11.14.9 step 2) How many PIN or password changes, renews and resets this client has sent
 * ([CredentialOps.secretGeneration]; a reset counts as it goes out and again once it ended).
 * Read before the phone's epoch and the typed secrets are used, and passed as `since`: an operation is refused, with
 * nothing sent, when one went out in between.
 */
val VaultApi.credentialGeneration: Long get() = credentialOps.secretGeneration

/** Fetches a pool of UTKs when none is left. */
suspend fun VaultApi.credentialTopUpUtks() = credentialOps.topUpUtks()

/**
 * (0.45.0 §11.14.9 step 1) Waits, at most 30 s, until no credential operation of this phone can still reach the vault
 * (the outbox flushed, every one answered or past its `exp`). False: some did not settle in time.
 */
suspend fun VaultApi.credentialAwaitSettled(): Boolean = device.awaitCredentialSettled()
