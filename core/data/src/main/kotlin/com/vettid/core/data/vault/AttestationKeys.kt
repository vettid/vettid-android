package com.vettid.core.data.vault

import android.content.Context
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.MemberApiException
import com.vettid.core.crypto.altchan.DeviceAssertion
import com.vettid.core.crypto.altchan.DeviceAttest
import com.vettid.core.keystore.AndroidKeys
import com.vettid.core.keystore.KeystoreException

/**
 * (ANDROID-PLAN §4 step 5, "Device attestation keys (Android)") The device attestation keys of each identity slot.
 *
 * A Keystore key's attestation challenge is fixed when the key is generated, and every request's challenge names that
 * request (§11.7), so each attestation (an enrollment, a transfer, every recovery register POST, a re-post included) is a
 * new key under a new alias of its slot ([AndroidKeys.deviceAttestationAlias]); nothing replaces a key that is listed.
 * The vault may hold the key of any register POST it may have recorded, so a key goes only:
 * - when its POST is settled not registered ([RegisterKeys]), or, for a key made long enough ago, when status signed with K
 *   shows the recovery it was registered for active and not registered ([settleNotRegistered]);
 * - after the handshake: the keys of the registration whose `recovery_id` completed it stay, and so do the key an `ok`
 *   open was signed with and any key whose registration is not known ([keepRegistration]);
 * - outside a recovery, after an unlock answered `ok` with another key ([keepAfterUnlock]);
 * - with its slot ([clear]).
 * A key listed by both slots (the key of the builds before slots) is deleted from the Keystore only when no slot lists it.
 *
 * Each slot keeps its keys in the order they were made, newest last, each with the `recovery_id` it was registered for
 * (null: an enrollment, a transfer, or the key of a build before slots whose registration is not known) and when it was
 * made. Opens and unlocks sign with the newest ([current]) and, on `attestation`, with the others, newest first
 * ([tryKeys]). The key of the builds before slots ([AndroidKeys.ALIAS_DEVICE_ATTESTATION]) is taken over, not deleted, by
 * every identity the phone holds ([migrate]): an enrolled phone keeps unlocking with it.
 */
@Suppress("TooManyFunctions")
internal class AttestationKeys(
    private val store: Store,
    private val keys: Keys,
    private val active: () -> Int,
    /** Whether a slot holds an identity (its device keys). */
    private val existing: (Int) -> Boolean = { false },
    /** The `recovery_id` a slot's device state names while it is recovering, else null. */
    private val recoveryIdOf: (Int) -> String? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Where each slot's list is kept; [write] is durable before it returns, and throws when it is not. */
    interface Store {
        fun read(slot: Int): String?

        fun write(slot: Int, value: String?)
    }

    /** The Keystore, by alias. */
    interface Keys {
        fun exists(alias: String): Boolean

        /** Removes the key; throws when the Keystore could not (the key then stays listed). */
        fun delete(alias: String)
    }

    /**
     * A key: its [alias], the [recoveryId] it was registered for (null: none or not known), when it was made ([madeMs], 0:
     * not known), and whether an open signed with it was answered `ok` ([opened]).
     */
    data class Entry(val alias: String, val recoveryId: String?, val madeMs: Long = 0, val opened: Boolean = false)

    private var migrated = false

    /**
     * The key of the builds before slots, when it is in the Keystore, is listed (oldest) by every slot that holds an
     * identity, or by the slot in use when none does: it may be either identity's key (a reuse in progress). Each slot
     * tags it with the `recovery_id` its device state names while recovering. Nothing is generated, moved or deleted (a
     * Keystore key cannot be renamed). Once, durably ([MIGRATED]); runs before any other use.
     */
    @Synchronized
    fun migrate() {
        if (migrated) return
        if (store.read(MIGRATED) == null) {
            if (keys.exists(AndroidKeys.ALIAS_DEVICE_ATTESTATION)) SLOTS.filter(existing).ifEmpty { listOf(active()) }.forEach(::listLegacy)
            store.write(MIGRATED, "1")
        }
        migrated = true
    }

    private fun listLegacy(slot: Int) {
        val legacy = AndroidKeys.ALIAS_DEVICE_ATTESTATION
        val list = load(slot)
        if (list.none { it.alias == legacy }) save(slot, listOf(Entry(legacy, recoveryIdOf(slot))) + list)
    }

    /** [slot]'s keys that are in the Keystore, newest first. */
    @Synchronized
    fun candidates(slot: Int): List<Entry> {
        migrate()
        return load(slot).filter { keys.exists(it.alias) }.asReversed()
    }

    /** The key [slot]'s opens and unlocks sign with: its newest, or null when it has none. */
    fun current(slot: Int): Entry? = candidates(slot).firstOrNull()

    /**
     * A new key for [slot], registered for [recoveryId] when it is a register's: listed (newest) before [generate] makes
     * it under the alias given. A listed key is never replaced: with every alias of the slot listed, nothing is made.
     */
    @Synchronized
    @Suppress("TooGenericExceptionCaught") // whatever the generation threw, it is thrown on unchanged
    fun <T> attest(slot: Int, recoveryId: String?, generate: (alias: String) -> T): T {
        migrate()
        // Only an alias no entry lists is free: the Keystore's `exists` can answer false on an internal error, so a listed key
        // is never taken as gone here (generating under an alias deletes what it holds).
        val list = load(slot)
        val used = list.map { it.alias }.toSet()
        val alias = (0 until AndroidKeys.DEVICE_ATTESTATION_KEYS_PER_SLOT).map { AndroidKeys.deviceAttestationAlias(slot, it) }
            .firstOrNull { it !in used } ?: throw VaultFailure(FailureKind.LOCAL_STATE, CODE_KEYS_FULL)
        save(slot, list + Entry(alias, recoveryId, nowMs()))
        return try {
            generate(alias)
        } catch (e: Exception) {
            // Nothing was made under it: only the new entry goes (fail secure for every other key).
            save(slot, load(slot).filter { it.alias != alias })
            throw e
        }
    }

    /**
     * The `recovery_id`s [slot] registered for, newest registration first, then [last] (the one its device state names) if
     * no key lists it: each is a `ctx` the identity's `hs.init` may try (VAULT-MESSAGING §11.14.6 "Pairing"). Never empty:
     * null stands for the device's own.
     */
    @Synchronized
    fun pairingContexts(slot: Int, last: String?): List<String?> {
        migrate()
        val ids = load(slot).asReversed().mapNotNull { it.recoveryId }.distinct()
        val all = if (last != null && last !in ids) ids + last else ids
        return all.ifEmpty { listOf(null) }
    }

    /** An open signed with [alias] was answered `ok`: the handshake never deletes that key ([keepRegistration]). */
    @Synchronized
    fun markOpened(slot: Int, alias: String) {
        val list = load(slot)
        if (list.none { it.alias == alias && !it.opened }) return
        save(slot, list.map { if (it.alias == alias) it.copy(opened = true) else it })
    }

    /**
     * The handshake completed with `ctx` = [recoveryId]: the vault's binding is that registration's attested key, so [slot]
     * keeps that registration's keys and deletes the keys of its other registrations. Kept as well: a key an `ok` open was
     * signed with, and a key whose registration is not known (null; the key of a build before slots). A `ctx` no key of
     * the slot was registered for deletes nothing (fail secure).
     */
    @Synchronized
    fun keepRegistration(slot: Int, recoveryId: String?) {
        if (recoveryId == null) return
        val list = load(slot)
        if (list.none { it.recoveryId == recoveryId }) return
        remove(slot, list.filter { it.recoveryId != null && it.recoveryId != recoveryId && !it.opened }.map { it.alias })
    }

    /**
     * Outside a recovery an unlock signed with [alias] was answered `ok`: that key is the vault's binding. [slot]'s keys that
     * served no registration (an enrollment's or a transfer's re-post) and those of [alias]'s own registration go; keys of
     * another registration stay.
     */
    @Synchronized
    fun keepAfterUnlock(slot: Int, alias: String) {
        val list = load(slot)
        val x = list.firstOrNull { it.alias == alias } ?: return
        remove(slot, list.filter { it.alias != alias && (it.recoveryId == null || it.recoveryId == x.recoveryId) }.map { it.alias })
    }

    /**
     * Status signed with K shows the recovery [recoveryId] active and not registered, and no open was ever sent for it: the
     * keys [slot] made for it at least [olderThanMs] ago (past any queue: a POST still queued may yet be recorded) were not
     * recorded and go. A key an `ok` open was signed with, or of unknown age, stays.
     */
    @Synchronized
    fun settleNotRegistered(slot: Int, recoveryId: String, olderThanMs: Long) {
        val now = nowMs()
        val list = load(slot)
        val gone = list.filter { it.recoveryId == recoveryId && !it.opened && it.madeMs > 0 && now - it.madeMs >= olderThanMs }
        remove(slot, gone.map { it.alias })
    }

    /** Deletes [aliases] of [slot]; one the Keystore could not delete stays listed, one another slot lists is unlisted only. */
    @Synchronized
    fun remove(slot: Int, aliases: Collection<String>) {
        if (aliases.isEmpty()) return
        migrate()
        val list = load(slot)
        val gone = aliases.filter { a -> list.any { it.alias == a } && (listedElsewhere(slot, a) || deleted(a)) }.toSet()
        if (gone.isNotEmpty()) save(slot, list.filter { it.alias !in gone })
    }

    /** Every key of [slot] goes, the listed ones and any of its aliases left unlisted; one another slot lists stays there. */
    @Synchronized
    fun clear(slot: Int) {
        migrate()
        val listed = load(slot).map { it.alias }
        val all = (listed + (0 until AndroidKeys.DEVICE_ATTESTATION_KEYS_PER_SLOT).map { AndroidKeys.deviceAttestationAlias(slot, it) })
            .distinct()
        val left = all.filterNot { listedElsewhere(slot, it) || deleted(it) }.filter { it in listed }
        save(slot, load(slot).filter { it.alias in left })
    }

    private fun listedElsewhere(slot: Int, alias: String): Boolean = SLOTS.any { s -> s != slot && load(s).any { it.alias == alias } }

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // a key not deleted stays listed and is deleted another time
    private fun deleted(alias: String): Boolean = try {
        keys.delete(alias)
        true
    } catch (e: Exception) {
        false
    }

    private fun load(slot: Int): List<Entry> = store.read(slot).orEmpty().lines().filter { it.isNotEmpty() }.map { line ->
        val p = line.split(SEP)
        Entry(
            alias = p[0],
            recoveryId = p.getOrNull(F_RECOVERY)?.takeIf { it.isNotEmpty() },
            madeMs = p.getOrNull(F_MADE)?.toLongOrNull() ?: 0,
            opened = p.getOrNull(F_FLAGS) == OPENED,
        )
    }

    private fun save(slot: Int, list: List<Entry>) {
        store.write(
            slot,
            if (list.isEmpty()) {
                null
            } else {
                list.joinToString("\n") {
                    listOf(it.alias, it.recoveryId ?: "", it.madeMs.toString(), if (it.opened) OPENED else "").joinToString(SEP.toString())
                }
            },
        )
    }

    /**
     * [attempt] with [slot]'s keys, newest first, each once, while the answer is a readable `attestation` (decided before
     * the PIN, never counted) or the key could not sign ([KeySignFailure], nothing was posted): the vault may hold an
     * earlier POST's key (VAULT-MESSAGING §11.14.6). [onOk] hears the key that was answered `ok`. With no key, one attempt
     * with none ([SlotAttester] then signs with nothing).
     */
    suspend fun <T> tryKeys(
        slot: Int,
        attempt: suspend (alias: String?) -> T,
        code: (T) -> String?,
        ok: (T) -> Boolean,
        onOk: suspend (alias: String) -> Unit = {},
    ): T {
        val aliases = candidates(slot).map { it.alias }
        if (aliases.isEmpty()) return attempt(null)
        var i = 0
        while (true) {
            val alias = aliases[i++]
            val r = try {
                attempt(alias)
            } catch (e: KeySignFailure) {
                if (i == aliases.size) throw e.cause
                continue
            }
            if (ok(r)) onOk(alias)
            if (ok(r) || code(r) != CODE_ATTESTATION || i == aliases.size) return r
        }
    }

    companion object {
        const val CODE_ATTESTATION = "attestation"

        /** Every alias of a slot is listed: no key is made (fail secure). */
        const val CODE_KEYS_FULL = "attestation_keys_full"
        private const val SEP = '\t'
        private const val OPENED = "o"
        private const val F_RECOVERY = 1
        private const val F_MADE = 2
        private const val F_FLAGS = 3

        /** The store's marker that [migrate] ran (not a slot). */
        private const val MIGRATED = -1
        private val SLOTS = 0 until AndroidKeys.DEVICE_ATTESTATION_SLOTS
    }
}

/** A key could not sign; nothing was posted with it. [cause] is what the Keystore threw. */
internal class KeySignFailure(override val cause: Exception) : Exception("attestation key could not sign", cause)

/** The slots' lists in their own SharedPreferences file, written with `commit()`. */
internal class PrefsAttestationStore(context: Context) : AttestationKeys.Store {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(slot: Int): String? = prefs.getString(key(slot), null)

    override fun write(slot: Int, value: String?) {
        val e = prefs.edit()
        if (value == null) e.remove(key(slot)) else e.putString(key(slot), value)
        check(e.commit()) { "attestation keys not written" }
    }

    private fun key(slot: Int) = "slot$slot"

    companion object {
        const val PREFS = "vettid_attestation_keys"
    }
}

/** The Android Keystore. */
internal object KeystoreAliases : AttestationKeys.Keys {
    override fun exists(alias: String): Boolean = AndroidKeys.exists(alias)

    override fun delete(alias: String) = AndroidKeys.delete(alias)
}

/**
 * The attester of identity [slot]: each attestation is a new key of the slot ([AttestationKeys.attest], registered for
 * [recoveryId]), and signatures use [signWith] or else the slot's current key. [made] is the key the last attestation made.
 * A key that cannot sign throws [KeySignFailure], so that [AttestationKeys.tryKeys] goes on with the next.
 */
internal class SlotAttester(
    private val ring: AttestationKeys,
    private val slot: Int,
    private val recoveryId: String? = null,
    private val signWith: String? = null,
    private val keyOf: (alias: String) -> Attester,
) : Attester {
    @Volatile
    var made: String? = null
        private set

    override fun attest(challenge: ByteArray): DeviceAttest = ring.attest(slot, recoveryId) { alias ->
        made = alias
        keyOf(alias).attest(challenge)
    }

    @Suppress("TooGenericExceptionCaught") // any Keystore failure of one key: the next key is tried
    override fun assert(message: ByteArray): DeviceAssertion {
        val alias = signer() ?: throw KeystoreException("no attestation key")
        return try {
            keyOf(alias).assert(message)
        } catch (e: Exception) {
            throw KeySignFailure(e)
        }
    }

    /** The key's chain; one that cannot be read throws [KeySignFailure], so that an unlock goes on with the next key. */
    @Suppress("TooGenericExceptionCaught") // any Keystore failure of one key: the next key is tried
    override fun chain(): List<ByteArray>? = signer()?.let { alias ->
        try {
            keyOf(alias).chain()
        } catch (e: Exception) {
            throw KeySignFailure(e)
        }
    }

    private fun signer(): String? = signWith ?: ring.current(slot)?.alias
}

/**
 * The keys of one register flow's POSTs ([AttestationKeys]): which may go once the flow is over. A POST's key goes only when
 * that POST is settled not registered: the member API answered it with one of the answers that never forward a POST
 * ([answered], [endOf]), or its own sealed answer was a readable refusal. A POST answered `202`, with no answer at all, or
 * with anything else (`instance_moved`, a `5xx` on the way) may have been recorded: its key stays.
 */
internal class RegisterKeys {
    private class Post(val alias: String?) {
        var accepted = false
        var notForwarded = false
    }

    private val posts = mutableListOf<Post>()

    /** A POST is built with the key [alias] (null: no key was made). */
    fun built(alias: String?) {
        posts += Post(alias)
    }

    /** The last POST got its `202`. */
    fun accepted() {
        posts.lastOrNull()?.accepted = true
    }

    /** The last POST never left the phone (a connection that was never made, [neverSent]): nothing was forwarded. */
    fun unsent() {
        val last = posts.lastOrNull() ?: return
        if (!last.accepted) last.notForwarded = true
    }

    /** The member API answered the last POST with [status] and [code] (an error, no `202`). */
    fun answered(status: Int?, code: String?) {
        val last = posts.lastOrNull() ?: return
        if (!last.accepted && endOf(status, code) == End.NOT_FORWARDED) last.notForwarded = true
    }

    enum class End {
        /** Anything that may follow a POST that reached the vault: the key stays. */
        KEEP,

        /** An answer that never forwards a POST ([endOf]). */
        NOT_FORWARDED,
    }

    /** The keys that are settled not registered: the POSTs the member API did not forward, and the last when [lastRefused]. */
    fun notRegistered(lastRefused: Boolean): List<String> =
        posts.filterIndexed { i, p -> (p.notForwarded && !p.accepted) || (lastRefused && i == posts.lastIndex) }.mapNotNull { it.alias }

    companion object {
        /**
         * A failure that proves the POST never left: no address, a refused or unroutable connection, with no earlier attempt
         * behind it (OkHttp records the routes it tried before as suppressed exceptions).
         */
        fun neverSent(e: java.io.IOException): Boolean =
            (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException) &&
                e.suppressed.isEmpty()

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_UNAVAILABLE = 503

        /**
         * Whether the member API's answer [status] / [code] never forwards a POST: `429`, `503 release_starting` or
         * `vault_unavailable`, `401 unauthorized` (not `clock_skew`), `recovery_not_available`. Anything else
         * (`instance_moved`, another `503`, `recovery_unavailable`, no answer) may follow a POST that reached the vault.
         */
        fun endOf(status: Int?, code: String?): End = when {
            status == HTTP_TOO_MANY -> End.NOT_FORWARDED
            status == HTTP_UNAVAILABLE && (code == MemberApiException.RELEASE_STARTING || code == MemberApiException.VAULT_UNAVAILABLE) ->
                End.NOT_FORWARDED
            status == HTTP_UNAUTHORIZED && code == MemberApiException.UNAUTHORIZED -> End.NOT_FORWARDED
            code == MemberApiException.RECOVERY_NOT_AVAILABLE -> End.NOT_FORWARDED
            else -> End.KEEP
        }
    }
}
