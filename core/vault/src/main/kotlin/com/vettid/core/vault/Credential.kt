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
/**
 * (VAULT-MESSAGING 0.33.0 §11.14.7) For a vault of release R every password is normalized to NFC before it is used or
 * sent, as that vault does: a new phone whose keyboard writes the same letters in another form must not spend the
 * member's attempts. A vault before release R compares the bytes as they are, so its payloads are never normalized.
 */
internal fun nfcPasswords(o: JsonObject): JsonObject = JsonObject(
    o.mapValues { (k, v) ->
        val p = v as? kotlinx.serialization.json.JsonPrimitive
        if (k in PASSWORD_KEYS && p != null && p.isString) {
            kotlinx.serialization.json.JsonPrimitive(com.vettid.core.crypto.altchan.Passwords.nfc(p.content))
        } else {
            v
        }
    },
)

private val PASSWORD_KEYS = setOf("password", "new_password")

/**
 * [normalizePasswords]: whether the vault is of release R (§11.14.7), asked at each operation; false sends every
 * password exactly as typed, as before release R.
 */
internal class CredentialOps(private val d: VaultDevice, private val normalizePasswords: () -> Boolean = { false }) {
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
        val id = d.newId()
        val body = sealedBody(type, utk, withBlob, id, payload, rk, extra)
        val r = d.requestWithId(id, type, body)
        if (r.inner.status != Inner.STATUS_OK) {
            rk?.destroy()
            // §6.7.1 step 3 (0.27.0): refused before the UTK was spent; the retry uses the same one.
            if (r.inner.error?.code == CODE_SIGNER_UNCONFIRMED) d.returnUtk(utk)
            // The error's body: `backoff` carries `retry_after` (§10.1, 0.17.0).
            throw VaultOpException(type, r.inner.error?.code ?: "error", r.inner.error?.message ?: "", r.body.takeIf { it.isNotEmpty() })
        }
        keep(r.body)
        lastTs = r.ts
        return Triple(r.body, rk, id)
    }

    /** The outer body of [type] for inner id [id]: [payload] (with [rk]'s public key) sealed to [utk], the blob when [withBlob]. */
    @Suppress("LongParameterList")
    private fun sealedBody(
        type: String,
        utk: UtkRecord,
        withBlob: Boolean,
        id: String,
        payload: JsonObjectBuilder.() -> Unit,
        rk: KemPrivateKey?,
        extra: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val obj = buildJsonObject {
            payload()
            rk?.let { put("reply_key", Base64s.encodeStd(it.publicKey.bytes())) }
        }
        val pt = VaultJson.bytes(if (normalizePasswords()) nfcPasswords(obj) else obj)
        val vid = d.vaultId ?: throw VaultStateException("no vault id")
        val sealed = try {
            val u = Utk(utk.id, KemPublicKey.parse(Base64s.decodeStd(utk.ek)), Instant.ofEpochMilli(utk.expiresAtMs))
            CredentialSeal.sealPayload(u, vid, type, id, pt)
        } finally {
            Bytes.wipe(pt)
        }
        return buildJsonObject {
            extra()
            put("utk_id", utk.id)
            put("sealed", Base64s.encodeStd(sealed))
            if (withBlob) put("credential", d.credentialBlob() ?: throw VaultStateException("no credential on this device"))
        }
    }

    /** The `ts` of the last successful sealed answer (read under [VaultDevice.credentialLock]). */
    private var lastTs: Instant? = null

    /**
     * A sealed operation without the blob that answers with its body and its `ts` (History export, §10.9 0.22.0:
     * the PIN alone, sealed to a UTK as `vault.delete` carries it).
     */
    suspend fun sealedOpAt(
        type: String,
        payload: JsonObjectBuilder.() -> Unit,
        extra: JsonObjectBuilder.() -> Unit = {},
    ): Pair<JsonObject, Instant> =
        gated().credentialLock.withLock {
            val body = sealedWith(type, takeUtk(), false, payload, false, extra).first
            body to (lastTs ?: Instant.now())
        }

    /**
     * A credential operation (with the blob); on stale_credential fetches the latest blob once and retries (§3.5.3).
     * (0.44.0, §11.14.9) Never for a PIN or password change ([ChangeTypes]): those go through [change], and a
     * `stale_credential` to them is never retried with the typed secrets.
     */
    suspend fun credOp(
        type: String,
        payload: JsonObjectBuilder.() -> Unit,
        reply: Boolean = false,
        extra: JsonObjectBuilder.() -> Unit = {},
    ): Triple<JsonObject, KemPrivateKey?, String> = gated().credentialLock.withLock {
        try {
            sealedWith(type, takeUtk(), true, payload, reply, extra)
        } catch (e: VaultOpException) {
            if (e.code != CODE_STALE || type in ChangeTypes.ALL) throw e
            fetch()
            sealedWith(type, takeUtk(), true, payload, reply, extra)
        }
    }

    /** A sealed operation without the blob (create, recover, reset). */
    suspend fun sealedOp(type: String, payload: JsonObjectBuilder.() -> Unit, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        gated().credentialLock.withLock { sealedWith(type, takeUtk(), false, payload, false, extra).first }

    /**
     * (0.44.0, §11.14.9 step 2) Asked before every credential operation that carries a PIN, a password or the blob:
     * while a PIN or password change is unresolved it decides the change first and throws when it cannot, so that
     * nothing is sent with what may be the old PIN or password, or a stale blob. Null: nothing to wait for.
     */
    @Volatile
    var gate: (suspend () -> Unit)? = null

    private suspend fun gated(): VaultDevice {
        gate?.invoke()
        return d
    }

    /**
     * (0.44.0, §11.14.9) A PIN or password change, decided rather than repeated when its answer is lost. [onMarker]
     * stores the marker durably before anything is sent and throws to stop the operation. The request carries an
     * `exp` 10 minutes after [VaultDevice.vaultNow] and is sent as [KeptChange] (the automatic resends of §8.6 with
     * the same id and body). An answer decides it ([settle]); none throws [ChangeUnansweredException] with the kept
     * request. Not gated: the caller decides an earlier change first.
     */
    suspend fun change(type: String, payload: JsonObjectBuilder.() -> Unit, onMarker: suspend (ChangeMarker) -> Unit): JsonObject {
        require(type in ChangeTypes.ALL) { "not a change: $type" }
        val kept = d.credentialLock.withLock {
            val utk = takeUtk()
            var stored = false
            try {
                val version = d.credentialVersion ?: throw VaultStateException("no credential on this device")
                val id = d.newId()
                val body = sealedBody(type, utk, true, id, payload, null) {}
                val exp = d.vaultNow().plus(ChangeTypes.EXP).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                KeptChange(type, id, body, exp, version).also {
                    onMarker(it.marker)
                    stored = true
                }
            } finally {
                // Nothing was sent: the UTK goes back to the pool.
                if (!stored) d.returnUtk(utk)
            }
        }
        val r = d.sendKept(kept, CHANGE_SENDS) ?: throw ChangeUnansweredException(kept)
        return settle(kept, r)
    }

    /**
     * (§11.14.9 step 3) [kept] sent again, once, while its `exp` has not passed: the vault answers it from its cache
     * or handles it once. An answer that came meanwhile is returned at once. Null: no answer.
     */
    suspend fun resend(kept: KeptChange): VaultMessage? = d.sendKept(kept, 1)

    /**
     * (§11.14.9 step 4) An answer to a change decides it: the new blob and UTKs are kept (a failure to keep or ack them
     * does not make the change fail: it applied), or the refusal is thrown as it is. A `stale_credential` fetches the
     * latest blob and is thrown; it is never retried with the typed secrets.
     */
    suspend fun settle(kept: KeptChange, r: VaultMessage): JsonObject {
        d.dropKept(kept)
        if (r.inner.status != Inner.STATUS_OK) {
            val code = r.inner.error?.code ?: "error"
            if (code == CODE_STALE) {
                try {
                    fetch()
                } catch (_: java.io.IOException) {
                    // The blob is fetched by the next check of the change, or the next credential operation.
                }
            }
            throw VaultOpException(kept.type, code, r.inner.error?.message ?: "", r.body.takeIf { it.isNotEmpty() })
        }
        try {
            keep(r.body)
        } catch (_: java.io.IOException) {
            // The change applied; the blob is fetched again by the next operation that finds it stale.
        }
        return r.body
    }

    /** Fetches a pool of UTKs when none is left (after a change whose answer, which carried new ones, was lost). */
    suspend fun topUpUtks() {
        if (d.utkCount == 0) keep(d.op("credential.utk.get"))
    }

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

    private companion object {
        const val CODE_SIGNER_UNCONFIRMED = "signer_unconfirmed"
        const val CODE_STALE = "stale_credential"

        /** The first send and the two automatic resends of §8.6. */
        const val CHANGE_SENDS = 3
    }
}
