package com.vettid.core.vault

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.credential.CredentialSeal
import com.vettid.core.crypto.credential.Utk
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.hpke.KemPublicKey
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** A response that carried a value sealed to the request's one-time reply key, opened. */
class Revealed(val response: JsonObject, plaintext: ByteArray) {
    private val pt = plaintext.copyOf()

    /** The plaintext JSON (`{"fields": [...], "notes"?}` for an item); wipe with [wipe]. */
    fun plaintext(): ByteArray = pt.copyOf()

    fun json(): JsonObject = VaultJson.parseObject(pt)

    fun wipe() = Bytes.wipe(pt)
}

/**
 * The app's side of the Protean Credential (VAULT-MESSAGING §3.5, §10.6), as
 * vettid-vault's `client/credential.go`: the app keeps the latest blob and a
 * pool of UTKs; every critical operation seals its payload to a fresh UTK
 * (bound to the vault id, the UTK id, the type and the inner id), sends the
 * blob, stores the new blob the vault returns (the CEK rotated) and confirms
 * it with `credential.ack`; critical values come back sealed to a one-time
 * reply key. `stale_credential` fetches the latest blob once and retries.
 */
internal class CredentialOps(private val d: VaultDevice) {
    /** Stores the UTKs and the blob a response carries, and confirms the blob (`credential.ack`, §3.5.3). */
    suspend fun keep(o: JsonObject) {
        (o["utks"] as? JsonArray)?.let { arr ->
            val list = arr.map { e ->
                val u = Utk.parse(com.vettid.core.crypto.json.StrictJson.parseObject(e.toString()))
                UtkRecord(u.id, Base64s.encodeStd(u.ek.bytes()), u.expiresAt.toEpochMilli())
            }
            d.keepUtks(list)
        }
        val blob = VaultJson.str(o, "credential") ?: return
        // Item operations carry the item's version as `version` and the credential's as `credential_version` (§10.6).
        val v = VaultJson.long(o, "credential_version") ?: VaultJson.long(o, "version")
            ?: throw VaultStateException("credential without version")
        d.keepCredential(blob, v)
        d.op("credential.ack", buildJsonObject { put("version", v) })
    }

    /** An unexpired UTK, fetching a pool (`credential.utk.get`) when empty. */
    suspend fun takeUtk(): UtkRecord {
        repeat(2) {
            d.takeUtkOrNull()?.let { return it }
            keep(d.op("credential.utk.get"))
        }
        throw VaultStateException("no UTK")
    }

    /**
     * Sends [type] with [payload] sealed to [utk] (plus the blob when
     * [withBlob]) and the [extra] outer members; with [reply] a one-time reply
     * key goes into the payload and is returned for opening `*_sealed` values.
     */
    suspend fun sealedWith(
        type: String,
        utk: UtkRecord,
        withBlob: Boolean,
        payload: JsonObjectBuilder.() -> Unit,
        reply: Boolean,
        extra: JsonObjectBuilder.() -> Unit,
    ): Triple<JsonObject, KemPrivateKey?, String> {
        val rk = if (reply) KemPrivateKey.generate() else null
        val pt = VaultJson.bytes(
            buildJsonObject {
                payload()
                rk?.let { put("reply_key", Base64s.encodeStd(it.publicKey.bytes())) }
            },
        )
        val vid = d.vaultId ?: throw VaultStateException("no vault id")
        val id = d.newId()
        val sealed = try {
            val u = Utk(utk.id, KemPublicKey.parse(Base64s.decodeStd(utk.ek)), Instant.ofEpochMilli(utk.expiresAtMs))
            CredentialSeal.sealPayload(u, vid, type, id, pt)
        } finally {
            Bytes.wipe(pt)
        }
        val body = buildJsonObject {
            extra()
            put("utk_id", utk.id)
            put("sealed", Base64s.encodeStd(sealed))
            if (withBlob) put("credential", d.credentialBlob() ?: throw VaultStateException("no credential on this device"))
        }
        val r = d.requestWithId(id, type, body)
        if (r.inner.status != Inner.STATUS_OK) {
            rk?.destroy()
            // The error's body: `backoff` carries `retry_after` (§10.1, 0.17.0).
            throw VaultOpException(type, r.inner.error?.code ?: "error", r.inner.error?.message ?: "", r.body.takeIf { it.isNotEmpty() })
        }
        keep(r.body)
        return Triple(r.body, rk, id)
    }

    /** A credential operation (with the blob); on stale_credential fetches the latest blob once and retries (§3.5.3). */
    suspend fun credOp(
        type: String,
        payload: JsonObjectBuilder.() -> Unit,
        reply: Boolean = false,
        extra: JsonObjectBuilder.() -> Unit = {},
    ): Triple<JsonObject, KemPrivateKey?, String> = d.credentialLock.withLock {
        try {
            sealedWith(type, takeUtk(), true, payload, reply, extra)
        } catch (e: VaultOpException) {
            if (e.code != "stale_credential") throw e
            fetch()
            sealedWith(type, takeUtk(), true, payload, reply, extra)
        }
    }

    /** A sealed operation without the blob (create, recover, reset). */
    suspend fun sealedOp(type: String, payload: JsonObjectBuilder.() -> Unit, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        d.credentialLock.withLock { sealedWith(type, takeUtk(), false, payload, false, extra).first }

    /** Fetches the latest blob the vault holds (the holder only). */
    suspend fun fetch() = keep(d.op("credential.get"))

    /** Opens a value the vault sealed to [rk] for request [innerId] (§3.5.4). */
    fun open(rk: KemPrivateKey, innerId: String, sealedB64: String): ByteArray {
        val vid = d.vaultId ?: throw VaultStateException("no vault id")
        try {
            return CredentialSeal.openValue(rk, vid, innerId, Base64s.decodeStd(sealedB64))
        } finally {
            rk.destroy()
        }
    }
}
