package com.vettid.core.data.vault

import android.content.Context
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.MemberApiException
import com.vettid.core.crypto.altchan.DeviceAssertion
import com.vettid.core.crypto.altchan.DeviceAttest
import com.vettid.core.keystore.AndroidKeys
import com.vettid.core.keystore.KeystoreException

/**
 * (ANDROID-PLAN §4 step 5, "Device attestation keys") The device attestation keys of each identity slot.
 *
 * A Keystore key's attestation challenge is fixed when the key is generated, and every request's challenge names that
 * request (§11.7), so each attestation (an enrollment, a transfer, every recovery register POST, a re-post included) is a
 * new key under a new alias of its slot ([AndroidKeys.deviceAttestationAlias]); nothing replaces a key that is listed.
 * The vault may hold the key of any register POST it may have recorded, so a key goes only when its POST is settled not
 * registered ([RegisterKeys]), with its slot ([clear]), or after the handshake, when the key that answered the `ok` is the
 * one kept ([keepOnly]).
 *
 * Each slot keeps its keys in order, newest last, with the `recovery_id` each was registered for (null: an enrollment, a
 * transfer, or the key of a build before slots). Opens and unlocks sign with the newest ([current]); an `ok` with another
 * key makes that one the newest ([promote]). The key of the builds before slots ([AndroidKeys.ALIAS_DEVICE_ATTESTATION]) is
 * taken over, not deleted, by the slot in use the first time the keys are read ([migrate]): an enrolled phone keeps
 * unlocking with it.
 */
internal class AttestationKeys(private val store: Store, private val keys: Keys, private val active: () -> Int) {
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

    data class Entry(val alias: String, val recoveryId: String?)

    private var migrated = false

    /**
     * The key of the builds before slots, when it is in the Keystore and no slot lists it, becomes the oldest key of the
     * slot in use. Nothing is generated, moved or deleted (a Keystore key cannot be renamed). Idempotent; runs before any
     * other use.
     */
    @Synchronized
    fun migrate() {
        if (migrated) return
        val legacy = AndroidKeys.ALIAS_DEVICE_ATTESTATION
        if (keys.exists(legacy) && SLOTS.none { s -> load(s).any { it.alias == legacy } }) {
            val slot = active()
            save(slot, listOf(Entry(legacy, null)) + load(slot))
        }
        migrated = true
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
    fun <T> attest(slot: Int, recoveryId: String?, generate: (alias: String) -> T): T {
        migrate()
        // A listed key that is not in the Keystore (deleted, or its generation failed) holds nothing: its alias is free.
        val list = load(slot).filter { keys.exists(it.alias) }
        val used = list.map { it.alias }.toSet()
        val alias = (0 until AndroidKeys.DEVICE_ATTESTATION_KEYS_PER_SLOT).map { AndroidKeys.deviceAttestationAlias(slot, it) }
            .firstOrNull { it !in used } ?: throw VaultFailure(FailureKind.LOCAL_STATE, CODE_KEYS_FULL)
        save(slot, list + Entry(alias, recoveryId))
        return generate(alias)
    }

    /** [alias] answered an `ok`: it is [slot]'s newest from now on. */
    @Synchronized
    fun promote(slot: Int, alias: String) {
        val list = load(slot)
        val e = list.firstOrNull { it.alias == alias } ?: return
        if (list.last() != e) save(slot, list.filter { it.alias != alias } + e)
    }

    /** Deletes [aliases] of [slot]; one the Keystore could not delete stays listed. */
    @Synchronized
    fun remove(slot: Int, aliases: Collection<String>) {
        if (aliases.isEmpty()) return
        migrate()
        val list = load(slot)
        val gone = aliases.filter { a -> list.any { it.alias == a } && deleted(a) }.toSet()
        if (gone.isNotEmpty()) save(slot, list.filter { it.alias !in gone })
    }

    /** After the handshake: [slot] keeps [alias] alone. */
    @Synchronized
    fun keepOnly(slot: Int, alias: String) {
        val list = load(slot)
        if (list.none { it.alias == alias }) return
        remove(slot, list.map { it.alias }.filter { it != alias })
    }

    /** Every key of [slot] goes, the listed ones and any of its aliases left unlisted. */
    @Synchronized
    fun clear(slot: Int) {
        migrate()
        val listed = load(slot).map { it.alias }
        val all = (listed + (0 until AndroidKeys.DEVICE_ATTESTATION_KEYS_PER_SLOT).map { AndroidKeys.deviceAttestationAlias(slot, it) })
            .distinct()
        val left = all.filterNot { deleted(it) }.filter { it in listed }
        save(slot, load(slot).filter { it.alias in left })
    }

    @Suppress("SwallowedException") // a key the Keystore could not delete stays listed and is deleted another time
    private fun deleted(alias: String): Boolean = try {
        keys.delete(alias)
        true
    } catch (e: java.security.KeyStoreException) {
        false
    }

    private fun load(slot: Int): List<Entry> = store.read(slot).orEmpty().lines().filter { it.isNotEmpty() }.map { line ->
        val parts = line.split(SEP, limit = 2)
        Entry(parts[0], parts.getOrNull(1)?.takeIf { it.isNotEmpty() })
    }

    private fun save(slot: Int, list: List<Entry>) {
        store.write(slot, if (list.isEmpty()) null else list.joinToString("\n") { it.alias + SEP + (it.recoveryId ?: "") })
    }

    /**
     * [attempt] with [slot]'s keys, newest first, each once, while the answer is a readable `attestation` (decided before
     * the PIN, never counted): the vault may hold an earlier register's key (VAULT-MESSAGING §11.14.6). The key that is
     * answered `ok` becomes the newest. With no key, one attempt with none ([SlotAttester] then signs with nothing).
     */
    suspend fun <T> tryKeys(slot: Int, attempt: suspend (alias: String?) -> T, code: (T) -> String?, ok: (T) -> Boolean): T {
        val aliases = candidates(slot).map { it.alias }
        if (aliases.isEmpty()) return attempt(null)
        var i = 0
        while (true) {
            val alias = aliases[i++]
            val r = attempt(alias)
            if (ok(r)) promote(slot, alias)
            if (ok(r) || code(r) != CODE_ATTESTATION || i == aliases.size) return r
        }
    }

    companion object {
        const val CODE_ATTESTATION = "attestation"

        /** Every alias of a slot is listed: no key is made (fail secure). */
        const val CODE_KEYS_FULL = "attestation_keys_full"
        private const val SEP = '\t'
        private val SLOTS = 0 until AndroidKeys.DEVICE_ATTESTATION_SLOTS
    }
}

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

    override fun assert(message: ByteArray): DeviceAssertion =
        keyOf(signer() ?: throw KeystoreException("no attestation key")).assert(message)

    override fun chain(): List<ByteArray>? = signer()?.let { keyOf(it).chain() }

    private fun signer(): String? = signWith ?: ring.current(slot)?.alias
}

/**
 * The keys of one register flow's POSTs ([AttestationKeys]): which may go once the flow is over. A POST's key goes only when
 * that POST is settled not registered: the member API answered it without a `202` (an earlier POST of the flow that was
 * posted again without its `202` was answered `instance_moved`), or its own sealed answer was a readable refusal. A POST
 * answered `202`, or with no answer at all, may have been recorded: its key stays.
 */
internal class RegisterKeys {
    private class Post(val alias: String?) {
        var accepted = false
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

    enum class End {
        /** `ok`, maybe registered, no answer, or anything not settled: every key stays. */
        KEEP,

        /**
         * The member API answered without forwarding the POST ([endOf]: a rate limit, `503`, `401`, `409`
         * `recovery_not_available` or `instance_moved`): the last POST, if it had no `202`, was not recorded.
         */
        NOT_FORWARDED,

        /** The last POST's own sealed answer was a readable refusal. */
        REFUSED,
    }

    /** The keys that are settled not registered when the flow ends with [end]. */
    fun notRegistered(end: End): List<String> {
        val last = posts.lastOrNull() ?: return emptyList()
        val lastGoes = when (end) {
            End.KEEP -> false
            End.NOT_FORWARDED -> !last.accepted
            End.REFUSED -> true
        }
        val earlier = posts.dropLast(1).filter { !it.accepted }
        return (earlier + listOfNotNull(last.takeIf { lastGoes })).mapNotNull { it.alias }
    }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_UNAVAILABLE = 503

        /**
         * How a flow that failed with the member API's [status] and [code] (null: no answer) ends: only the answers that
         * never forward a POST settle it; any other (a `5xx` from on the way, `recovery_unavailable`) may follow a POST that
         * reached the vault.
         */
        fun endOf(status: Int?, code: String?): End = when {
            status == HTTP_TOO_MANY || status == HTTP_UNAVAILABLE || status == HTTP_UNAUTHORIZED -> End.NOT_FORWARDED
            code == MemberApiException.RECOVERY_NOT_AVAILABLE || code == MemberApiException.INSTANCE_MOVED -> End.NOT_FORWARDED
            else -> End.KEEP
        }
    }
}
