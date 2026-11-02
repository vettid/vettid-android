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
    /**
     * Stores the UTKs and the blob a response carries, and confirms the blob (`credential.ack`, §3.5.3). The blob's
     * credential key is the answer's `key` when it names one; otherwise the held one when [sameKey] (an operation that
     * does not replace the credential key), or not known.
     */
    suspend fun keep(o: JsonObject, sameKey: Boolean = true) {
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
        d.keepCredential(blob, v, VaultJson.str(o, "key") ?: d.credentialKey.takeIf { sameKey })
        d.op("credential.ack", buildJsonObject { put("version", v) })
    }

    /**
     * An unexpired UTK, fetching a pool (`credential.utk.get`) when empty. It leaves the pool here and never goes back
     * once the request that presents it is sent, whatever the answer (VAULT-MESSAGING 0.52.0 §3.5.4, ANDROID-PLAN
     * 0.1.58 point 1: the vault spends a presented UTK before any check). (0.1.58 point 2) When `credential.utk.get`
     * returns no UTK while this pool is empty, the vault's pool for this phone holds UTKs the phone no longer has: one
     * `credential.utk.get{"resync": true}`, never repeated for this request. Its refusal (`too_soon` within 10 minutes of
     * the last resync) is thrown as it is; an empty list again throws [CODE_UTK_EMPTY].
     */
    suspend fun takeUtk(): UtkRecord {
        d.takeUtkOrNull()?.let { return it }
        val fetched = d.op(TYPE_UTK_GET)
        keep(fetched)
        d.takeUtkOrNull()?.let { return it }
        if (utkCountIn(fetched) > 0) throw VaultStateException("no UTK")
        val resynced = d.op(TYPE_UTK_GET, buildJsonObject { put("resync", true) })
        keep(resynced)
        d.takeUtkOrNull()?.let { return it }
        if (utkCountIn(resynced) > 0) throw VaultStateException("no UTK")
        throw VaultOpException(TYPE_UTK_GET, CODE_UTK_EMPTY)
    }

    private fun utkCountIn(o: JsonObject): Int = (o["utks"] as? JsonArray)?.size ?: 0

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
        onSending: (suspend (SentRequest) -> Unit)? = null,
        onAnswered: (suspend (innerId: String) -> Unit)? = null,
        onRefused: (suspend (innerId: String, code: String) -> Unit)? = null,
    ): Triple<JsonObject, KemPrivateKey?, String> {
        val rk = if (reply) KemPrivateKey.generate() else null
        val id = d.newId()
        val body = sealedBody(type, utk, withBlob, id, payload, rk, extra)
        // (0.44.0, §11.14.9) Every credential operation carries an exp, and is unsettled until answered or past it: a
        // PIN or password change waits for it, so that "version + 1" can only be the change's ([change]).
        val exp = d.vaultNow().plus(ChangeTypes.EXP).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        d.trackCredential(id, exp)
        // Stored by the caller before anything is sent (a renew, §11.14.9 step 5); a throw stops it, nothing sent.
        onSending?.let { f ->
            var stored = false
            try {
                f(SentRequest(type, id, d.credentialVersion, exp))
                stored = true
            } finally {
                if (!stored) {
                    d.settleCredential(id)
                    d.returnUtk(utk)
                    rk?.destroy()
                }
            }
        }
        val r = d.requestWithId(id, type, body, exp = exp)
        d.settleCredential(id)
        if (r.inner.status != Inner.STATUS_OK) {
            rk?.destroy()
            // (VAULT-MESSAGING 0.52.0, ANDROID-PLAN 0.1.58) The UTK is spent whatever the answer, `signer_unconfirmed`
            // included: the next attempt takes a fresh one.
            // (R1) Refused under this id (`stale_credential` included): the vault caches that answer, so no copy of this
            // request can apply. Said before anything follows (a fetch and a retry, which is a new request).
            onRefused?.invoke(id, r.inner.error?.code ?: "error")
            // The error's body: `backoff` carries `retry_after` (§10.1, 0.17.0).
            throw VaultOpException(type, r.inner.error?.code ?: "error", r.inner.error?.message ?: "", r.body.takeIf { it.isNotEmpty() })
        }
        // (Q1) The operation's own answer was `ok`: said before anything that follows it (keeping the blob, its ack).
        onAnswered?.invoke(id)
        keep(r.body, sameKey = type !in KEY_REPLACING)
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
        serialized(payload, null) {
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
        onSending: (suspend (SentRequest) -> Unit)? = null,
        onAnswered: (suspend (innerId: String) -> Unit)? = null,
        onRefused: (suspend (innerId: String, code: String) -> Unit)? = null,
    ): Triple<JsonObject, KemPrivateKey?, String> = serialized(payload, null) {
        try {
            sentCounted(type, onSending) { sending ->
                sealedWith(type, takeUtk(), true, payload, reply, extra, sending, onAnswered, onRefused)
            }
        } catch (e: VaultOpException) {
            if (e.code != CODE_STALE || type in ChangeTypes.ALL) throw e
            fetch()
            sentCounted(type, onSending) { sending ->
                sealedWith(type, takeUtk(), true, payload, reply, extra, sending, onAnswered, onRefused)
            }
        }
    }

    /**
     * A credential operation whose lost answer is **retried as the same request** (ANDROID-PLAN §4 "Sending", step 5; VAULT-MESSAGING
     * §3.5.4 "Using a UTK", §10.18 "Enforcement at signing": a retry with the same inner id is answered from the vault's cache and
     * counts once against a spending limit; a new inner id counts again). For `wallet.sign`: the sealed body is built once, with the
     * inner id and an `exp` ([ChangeTypes.EXP] after the vault's clock) and kept in memory only as a [KeptChange]; it is sent as
     * [sendKept] sends it (the same id and body, three times, never through the outbox, never written to storage). No answer is
     * [ChangeUnansweredException] carrying the kept request, for [resendKept]; nothing is built again. A `stale_credential` fetches
     * the latest blob and is tried once more as a **new** request (the refused one was cached as refused: nothing was signed).
     * Gated like [credOp]; the sealed answer is kept (blob and UTKs, acknowledged) as any credential response.
     */
    suspend fun credOpKept(
        type: String,
        payload: JsonObjectBuilder.() -> Unit,
        extra: JsonObjectBuilder.() -> Unit = {},
        /** The wallet a `wallet.sign` is for: the request is then durably marked outstanding until it is decided ([SignMark]). */
        walletId: String? = null,
        /** Called right before the request is first sent: an exception from before it means nothing reached the vault. */
        onSending: (() -> Unit)? = null,
    ): JsonObject = serialized(payload, null) {
        try {
            sendKeptOnce(type, payload, extra, walletId, onSending)
        } catch (e: VaultOpException) {
            // [settle] already fetched the latest blob.
            if (e.code != CODE_STALE) throw e
            sendKeptOnce(type, payload, extra, walletId, onSending)
        }
    }

    private suspend fun sendKeptOnce(
        type: String,
        payload: JsonObjectBuilder.() -> Unit,
        extra: JsonObjectBuilder.() -> Unit,
        walletId: String?,
        onSending: (() -> Unit)?,
    ): JsonObject {
        val utk = takeUtk()
        var stored = false
        val kept = try {
            val version = d.credentialVersion ?: throw VaultStateException("no credential on this device")
            val id = d.newId()
            val body = sealedBody(type, utk, true, id, payload, null, extra)
            val exp = d.vaultNow().plus(ChangeTypes.EXP).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            d.trackCredential(id, exp, walletId?.let { SignMark(it, version) })
            KeptChange(type, id, body, exp, version).also { stored = true }
        } finally {
            // Nothing was sent: the UTK goes back to the pool.
            if (!stored) d.returnUtk(utk)
        }
        onSending?.invoke()
        @Suppress("TooGenericExceptionCaught")
        val r = try {
            d.sendKept(kept, CHANGE_SENDS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ChangeUnansweredException(kept, e)
        } ?: throw ChangeUnansweredException(kept)
        return settleKept(kept, r)
    }

    /**
     * [kept] sent once more, as the same request (same inner id, body and `exp`), while its `exp` has not passed by the vault's clock:
     * its answer settled as [credOpKept] settles one, or null when there is still none ([ChangeUnansweredException] is not thrown
     * again: the caller holds [kept]). An answer that came late to an earlier send is taken without sending.
     */
    suspend fun resendKept(kept: KeptChange): JsonObject? = d.credentialLock.withLock {
        val r = d.sendKept(kept, 1) ?: return@withLock null
        settleKept(kept, r)
    }

    /** The answer to a kept request: the request is decided ([settle]); the credential operation is no longer unsettled. */
    private suspend fun settleKept(kept: KeptChange, r: VaultMessage): JsonObject {
        d.settleCredential(kept.innerId)
        if (r.inner.status != Inner.STATUS_OK) return settle(kept, r)
        d.dropKept(kept)
        try {
            keepIfNewer(r.body, kept.sentVersion)
        } catch (_: java.io.IOException) {
            // The operation applied; the blob is fetched by the next operation that finds it stale.
        }
        return r.body
    }

    /**
     * Keeps what a (possibly cached, possibly late) answer carries: the UTKs always, the blob and its acknowledgement only when the
     * credential held is **still the one the request was sent at** ([sentVersion]) and the answer's `credential_version` is above
     * it. Another credential operation (or a reset) may have moved the credential on while this answer was awaited; storing the
     * blob then would present a version the vault has passed, which it freezes as a clone.
     */
    private suspend fun keepIfNewer(o: JsonObject, sentVersion: Long) {
        val v = VaultJson.long(o, "credential_version") ?: VaultJson.long(o, "version")
        if (d.credentialVersion == sentVersion && (v == null || v > sentVersion)) {
            keep(o)
        } else {
            keep(JsonObject(o.filterKeys { it != "credential" }))
        }
    }

    /**
     * (VAULT-MESSAGING 0.48.0 §11.14.9 step 2) A `credential.reset` replaces the password and the credential key, so it
     * counts as a secret sent ([secretGeneration]): once as it goes out (after [onSending] stored it) and again once it
     * ended (answered, refused or unanswered), both under [VaultDevice.credentialLock]. An operation that read the count
     * before the reset went out, or while it was out, is then refused with nothing sent when it gets the lock. A reset
     * stopped before it went out counts nothing. Any other [type] runs [send] with [onSending] as it is.
     */
    private suspend fun <T> sentCounted(
        type: String,
        onSending: (suspend (SentRequest) -> Unit)?,
        send: suspend (onSending: (suspend (SentRequest) -> Unit)?) -> T,
    ): T {
        if (type != TYPE_RESET) return send(onSending)
        var sent = false
        try {
            return send { r ->
                onSending?.invoke(r)
                secretGeneration++
                sent = true
            }
        } finally {
            if (sent) secretGeneration++
        }
    }

    /**
     * (VAULT-MESSAGING 0.45.0 / 0.47.0 §11.14.9 step 1, ANDROID-PLAN 0.1.48) `vault.secret.renew`, under
     * [VaultDevice.credentialLock] as a change: sent only once every earlier credential request is settled
     * ([CredentialUnsettledException] otherwise) and with the vault's current blob ([currentBlob]); [onSending] stores the
     * renew only after both. Its own answer decides it: `ok` ([onAnswered]) applied; any refusal ([onRefused]),
     * `stale_credential` included, not applied. A `stale_credential` fetches the latest blob and is thrown; it is never
     * retried with the typed secrets (a second renew would not be preceded by its own check). [since]: the
     * [secretGeneration] its caller read before it read the phone's epoch ([serialized]).
     */
    @Suppress("LongParameterList")
    suspend fun renew(
        type: String,
        payload: JsonObjectBuilder.() -> Unit,
        onSending: suspend (SentRequest) -> Unit,
        onAnswered: (suspend (innerId: String) -> Unit)? = null,
        onRefused: (suspend (innerId: String, code: String) -> Unit)? = null,
        since: Long? = null,
    ): JsonObject = serialized(payload, since) {
        if (!d.awaitCredentialSettled()) throw CredentialUnsettledException()
        currentBlob()
        // Stored by the caller, then sent: from here a later operation's typed secrets may be stale.
        val sending: suspend (SentRequest) -> Unit = { r ->
            onSending(r)
            secretGeneration++
        }
        try {
            sealedWith(type, takeUtk(), true, payload, false, {}, sending, onAnswered, onRefused).first
        } catch (e: VaultOpException) {
            if (e.code == CODE_STALE && e.type == type) fetchQuietly()
            throw e
        }
    }

    /**
     * (VAULT-MESSAGING 0.47.0 §11.14.9 step 1, "Sent with the vault's current blob") Asked under
     * [VaultDevice.credentialLock], once every earlier credential request is settled, right before a change or a renew
     * is built: `credential.version`. Equal to the held blob's version: the request goes with it. Otherwise an earlier
     * operation applied without its answer reaching the phone: the latest blob is fetched and kept, and the request goes
     * with it only when its version equals the vault's. A fetch that fails, or that gives another version, throws
     * [CredentialUnsettledException] (the "earlier request" text); no answer to `credential.version` throws its
     * [java.io.IOException] as it is. Either way nothing of the request was built or sent.
     */
    private suspend fun currentBlob() {
        // No credential on this phone: nothing to send with, nothing asked (0.47.0, as before the key was compared).
        if (d.credentialVersion == null) throw VaultStateException("no credential on this device")
        val v = vaultCredential()
        if (v.matchesHeld()) return
        @Suppress("TooGenericExceptionCaught")
        try {
            fetch()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CredentialUnsettledException(e)
        }
        if (!v.adoptFetched()) throw CredentialUnsettledException()
    }

    /** `credential.version` as asked under the lock: its [version] and [key] (null when absent or not `ok`). */
    private inner class VaultCredential(val version: Long?, val key: String?) {
        /**
         * The held blob is the vault's: the same version and, when both are known, the same credential key (a reset
         * starts again at version 1 under a new key, so a version alone can match a superseded blob).
         */
        fun matchesHeld(): Boolean {
            val heldKey = d.credentialKey
            return version != null && version == d.credentialVersion && (heldKey == null || key == null || heldKey == key)
        }

        /** After a fetch: the fetched blob is the vault's version; its key is then the one the vault named. */
        suspend fun adoptFetched(): Boolean {
            if (version == null || d.credentialVersion != version) return false
            val blob = d.credentialBlob() ?: return false
            if (key != null && d.credentialKey != key) d.keepCredential(blob, version, key)
            return true
        }
    }

    private suspend fun vaultCredential(): VaultCredential {
        val r = d.request("credential.version")
        // An answer that is not `ok` carries no version: it is not the held one.
        if (r.inner.status != Inner.STATUS_OK) return VaultCredential(null, null)
        return VaultCredential(VaultJson.long(r.body, "version"), VaultJson.str(r.body, "key"))
    }

    /**
     * (vettid.org #358, §3.5.9) `credential.rotate` while a clone alarm may be open. The vault answers the holder's own
     * superseded blob with `rotation_required` or `credential_frozen` there, never `stale_credential`, so a rotation that
     * only fetched on `stale_credential` would fail again at every try, each spending a UTK. So: with [alarmOpen], before
     * the rotation, and after a rotation answered `rotation_required` or `credential_frozen`, `credential.version`
     * decides whether the held blob is the vault's (version and key); when it is not, the latest blob is fetched and kept
     * (`credential.get`, `credential.ack`), the UTKs topped up, and the rotation retried **once** with it. Otherwise the
     * refusal is thrown as it is. A fetch is made only once nothing of this phone can still reach the vault. A check or a
     * fetch that fails is never what the member is told: the rotation's own answer is (the check before then sends the
     * rotation with the held blob; the check after leaves its refusal). `stale_credential` keeps the generic fetch and
     * single retry.
     */
    suspend fun rotate(payload: JsonObjectBuilder.() -> Unit, alarmOpen: Boolean): JsonObject = serialized(payload, null) {
        // The check before is only a shortcut: one that fails (no answer, a refused or lost fetch) sends the rotation with
        // the held blob all the same, and the vault's answer to the rotation is what the member is told.
        if (alarmOpen) refreshQuietly()
        try {
            sealedWith(TYPE_ROTATE, takeUtk(), true, payload, false, {}).first
        } catch (e: VaultOpException) {
            val retry = when (e.code) {
                CODE_STALE -> {
                    fetch()
                    true
                }
                // A check or fetch that fails leaves the rotation's refusal as it was.
                CODE_ROTATION_REQUIRED, CODE_FROZEN -> refreshQuietly()
                else -> false
            }
            if (!retry) throw e
            sealedWith(TYPE_ROTATE, takeUtk(), true, payload, false, {}).first
        }
    }

    /**
     * The held blob compared with `credential.version` (version and key); when it is not the vault's, the latest blob is
     * fetched and kept and the UTKs topped up. True when a fetched blob now matches the vault's version; false when the
     * held blob was already the vault's, or the fetch did not give the version the vault named (nothing to retry with).
     */
    private suspend fun refreshIfSuperseded(): Boolean {
        val v = vaultCredential()
        if (v.version == null || v.matchesHeld()) return false
        // Nothing of this phone may still reach the vault (a reset in flight would pair another key with the blob).
        if (!d.awaitCredentialSettled()) return false
        fetch()
        if (!v.adoptFetched()) return false
        topUpUtks()
        return true
    }

    /** [refreshIfSuperseded], with a check or a fetch that fails taken as "nothing to retry with". */
    private suspend fun refreshQuietly(): Boolean = try {
        refreshIfSuperseded()
    } catch (_: java.io.IOException) {
        // VaultOpException included (a refused or `not_found` fetch, `credential_frozen`).
        false
    }

    /** The latest blob, for the next operation; a failure leaves it to [currentBlob] or the next stale answer. */
    private suspend fun fetchQuietly() {
        try {
            fetch()
        } catch (_: java.io.IOException) {
            // VaultOpException included: the next change or renew checks the version again before it is sent.
        }
    }

    /** A sealed operation without the blob (create, recover, reset). */
    suspend fun sealedOp(type: String, payload: JsonObjectBuilder.() -> Unit, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        serialized(payload, null) { sealedWith(type, takeUtk(), false, payload, false, extra).first }

    /**
     * (0.44.0, §11.14.9 step 2) Asked before every credential operation that carries a PIN, a password or the blob:
     * while a PIN or password change is unresolved it decides the change first and throws when it cannot, so that
     * nothing is sent with what may be the old PIN or password, or a stale blob. Its argument: the operation carries the
     * PIN (0.45.0: refused while the PIN is uncertain after a check that ended without a decision). Null: nothing to ask.
     */
    @Volatile
    var gate: (suspend (carriesPin: Boolean) -> Unit)? = null

    private suspend fun gated(payload: JsonObjectBuilder.() -> Unit): VaultDevice {
        gate?.let { g -> g(buildJsonObject(payload).containsKey("pin")) }
        return d
    }

    /**
     * (§11.14.9 step 2) How many PIN or password changes and renews this client has sent (counted once each is stored
     * and about to go out, under [VaultDevice.credentialLock]); a `credential.reset` counts as it goes out and again
     * once it ended ([sentCounted], 0.48.0). An operation whose PIN or password was typed, or whose
     * phone epoch was read, before one of them went out may carry what is no longer current: it is refused with nothing
     * sent ([serialized]), so that it never spends a failed owner check or a password attempt.
     */
    @Volatile
    var secretGeneration: Long = 0L
        private set

    /**
     * Runs [block] under [VaultDevice.credentialLock] with the change gate asked twice: before the lock (a refusal at
     * once, without waiting for it) and again inside it, since a change may have stored its marker and gone out while
     * this operation waited (the change sends outside the lock). Inside the lock it is also refused when a change or a
     * renew went out since [since] (or since this call began): [CredentialUnsettledException], nothing sent.
     */
    private suspend fun <T> serialized(payload: JsonObjectBuilder.() -> Unit, since: Long?, block: suspend () -> T): T {
        val from = since ?: secretGeneration
        return gated(payload).credentialLock.withLock {
            gated(payload)
            if (secretGeneration != from) throw CredentialUnsettledException()
            block()
        }
    }

    /**
     * (0.44.0, §11.14.9) A PIN or password change, decided rather than repeated when its answer is lost. [onMarker]
     * stores the marker durably before anything is sent and throws to stop the operation. The request carries an
     * `exp` 10 minutes after [VaultDevice.vaultNow] and is sent as [KeptChange] (the automatic resends of §8.6 with
     * the same id and body). An answer decides it ([settle]); none throws [ChangeUnansweredException] with the kept
     * request. Not gated: the caller decides an earlier change first. Refused with nothing sent when another change or
     * a renew went out since [since] (the [secretGeneration] the caller read before its epoch and secrets; null: since
     * this call began).
     */
    suspend fun change(
        type: String,
        payload: JsonObjectBuilder.() -> Unit,
        onMarker: suspend (ChangeMarker) -> Unit,
        since: Long? = null,
    ): JsonObject {
        require(type in ChangeTypes.ALL) { "not a change: $type" }
        val from = since ?: secretGeneration
        val kept = d.credentialLock.withLock {
            if (secretGeneration != from) throw CredentialUnsettledException()
            // (0.45.0 step 1) Nothing else in flight: the outbox flushed and every earlier credential operation answered
            // (waited for at most 30 s), so that one above the version sent can only be this change.
            if (!d.awaitCredentialSettled()) throw CredentialUnsettledException()
            // (0.47.0 step 1) Sent with the vault's current blob; checked before the UTK, the marker and the candidate.
            currentBlob()
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
                    secretGeneration++
                }
            } finally {
                // Nothing was sent: the UTK goes back to the pool.
                if (!stored) d.returnUtk(utk)
            }
        }
        // After the marker, whatever goes wrong before an answer is read is "no answer" (§11.14.9 step 2), never a failure.
        @Suppress("TooGenericExceptionCaught")
        val r = try {
            d.sendKept(kept, CHANGE_SENDS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ChangeUnansweredException(kept, e)
        } ?: throw ChangeUnansweredException(kept)
        return settle(kept, r)
    }

    /**
     * (§11.14.9 step 3) [kept] sent again, once, while its `exp` has not passed: the vault answers it from its cache
     * or handles it once. An answer that came meanwhile is returned at once. Null: no answer.
     */
    suspend fun resend(kept: KeptChange): VaultMessage? = d.sendKept(kept, 1)

    /**
     * (§11.14.9 step 4) An answer to a change decides it: the new UTKs are kept, and the new blob while the credential held is still
     * the one the change was sent at (a failure to keep or ack them
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
            // A late or cached answer (§11.14.9 step 3, or [LostAnswer] after the change was settled elsewhere) must not roll the
            // credential back: the blob only while the credential held is still the one the change was sent at ([keepIfNewer]).
            keepIfNewer(r.body, kept.sentVersion)
        } catch (_: java.io.IOException) {
            // The change applied; the blob is fetched again by the next operation that finds it stale.
        }
        return r.body
    }

    /** Fetches a pool of UTKs when none is left (after a change whose answer, which carried new ones, was lost). */
    suspend fun topUpUtks() {
        if (d.utkCount == 0) keep(d.op(TYPE_UTK_GET))
    }

    /** Fetches the latest blob the vault holds (the holder only). */
    suspend fun fetch() = keep(d.op("credential.get"), sameKey = false)

    /** Opens a value the vault sealed to [rk] for request [innerId] (§3.5.4). */
    fun open(rk: KemPrivateKey, innerId: String, sealedB64: String): ByteArray {
        val vid = d.vaultId ?: throw VaultStateException("no vault id")
        try {
            return CredentialSeal.openValue(rk, vid, innerId, Base64s.decodeStd(sealedB64))
        } finally {
            rk.destroy()
        }
    }

    companion object {
        const val TYPE_UTK_GET = "credential.utk.get"

        /**
         * (ANDROID-PLAN 0.1.58 point 2) The app's own code when a resync also returned no UTK: shown with
         * `error_vault_code`, as a vault code is.
         */
        const val CODE_UTK_EMPTY = "utk_empty"
        private const val CODE_STALE = "stale_credential"
        private const val CODE_ROTATION_REQUIRED = "rotation_required"
        private const val CODE_FROZEN = "credential_frozen"
        private const val TYPE_ROTATE = "credential.rotate"

        /** Operations whose answer carries a credential under a new key (or one this phone has not held before). */
        private const val TYPE_RESET = "credential.reset"
        private val KEY_REPLACING = setOf(TYPE_ROTATE, TYPE_RESET, "credential.create", "credential.recover")

        /** The first send and the two automatic resends of §8.6. */
        private const val CHANGE_SENDS = 3
    }
}
