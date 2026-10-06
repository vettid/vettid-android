package com.vettid.core.altchan

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.Randomness
import com.vettid.core.keystore.AppApiKey

/**
 * The app key (VAULT-MESSAGING §11.12.2, 0.15.0): a P-256 signing key held in hardware that signs every app
 * request to the member API. The app never signs in; this key is what the API knows the app by.
 */
interface AppKeySigner {
    /** The public key's SubjectPublicKeyInfo DER. */
    fun spki(): ByteArray

    /** ECDSA P-256 with SHA-256 over [message], DER-encoded. */
    fun sign(message: ByteArray): ByteArray
}

/** The production signer: the Android Keystore app key from `:core:keystore`. */
class KeystoreAppKeySigner(private val key: AppApiKey = AppApiKey()) : AppKeySigner {
    override fun spki(): ByteArray = key.spki()

    override fun sign(message: ByteArray): ByteArray = key.sign(message)
}

/**
 * The `X-VettID-App` header (§11.12.2):
 *
 * ```
 * X-VettID-App: v=1; vault=<vault_id or empty>; kid=<akid>; ts=<Unix s>; nonce=<b64url, 16 bytes>; sig=<b64url DER ECDSA>
 * ```
 *
 * `sig` signs (each `\n` a literal newline, no trailing newline)
 * `"vettid/member-api/app/1" \n METHOD \n path \n query \n vault_id \n akid \n ts \n nonce \n hex(SHA-256(body))`.
 * base64url is written without padding, as everywhere else in the spec's links and QR codes (§6.4).
 */
object AppRequestSigning {
    const val HEADER = "X-VettID-App"
    const val CONTEXT = "vettid/member-api/app/1"
    const val NONCE_SIZE = 16
    private const val AKID_BYTES = 16

    /** `akid`: the first 16 bytes of SHA-256(SPKI DER), 32 lowercase hex. */
    fun akid(spki: ByteArray): String = Bytes.hex(Bytes.sha256(spki).copyOf(AKID_BYTES))

    /** The string the signature covers. [query] is the raw query without `?` (or empty). */
    @Suppress("LongParameterList")
    fun signingString(
        method: String,
        path: String,
        query: String,
        vaultId: String,
        akid: String,
        ts: Long,
        nonce: String,
        body: ByteArray,
    ): String =
        listOf(CONTEXT, method, path, query, vaultId, akid, ts.toString(), nonce, Bytes.hex(Bytes.sha256(body))).joinToString("\n")

    /** The header's value for a request; [nonceBytes] and [ts] are injectable for tests. */
    @Suppress("LongParameterList")
    fun header(
        signer: AppKeySigner,
        method: String,
        path: String,
        query: String?,
        vaultId: String?,
        body: ByteArray,
        ts: Long = System.currentTimeMillis() / MS_PER_S,
        nonceBytes: ByteArray = Randomness.bytes(NONCE_SIZE),
    ): String {
        val kid = akid(signer.spki())
        val vault = vaultId ?: ""
        val nonce = Base64s.encodeRawUrl(nonceBytes)
        val sig = signer.sign(signingString(method, path, query ?: "", vault, kid, ts, nonce, body).toByteArray(Charsets.UTF_8))
        return "v=1; vault=$vault; kid=$kid; ts=$ts; nonce=$nonce; sig=${Base64s.encodeRawUrl(sig)}"
    }

    private const val MS_PER_S = 1000L
}
