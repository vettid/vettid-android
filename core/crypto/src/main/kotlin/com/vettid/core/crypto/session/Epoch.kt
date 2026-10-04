package com.vettid.core.crypto.session

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Kid
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.envelope.Envelope
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import java.time.Duration
import java.time.Instant

/** This side's role in the handshake that created an epoch. */
enum class Role(val wire: Int) { INITIATOR(1), RESPONDER(2) }

/** Epoch limits (§6.5): rekey after [maxAge] or [maxMessages] in either direction (0: no bound). */
data class Policy(val maxAge: Duration, val maxMessages: Long = 0) {
    companion object {
        /** Vault ↔ vault: 24 h or 10,000 messages. */
        val VAULT_TO_VAULT = Policy(Duration.ofHours(24), 10_000)

        /** Vault ↔ device: 7 days (and on every unlock, which the runtime triggers). */
        val VAULT_TO_DEVICE = Policy(Duration.ofDays(7))
    }
}

/**
 * One session epoch (§6.3, §6.5): a send and a receive direction with their
 * keys and kids, plus the rekey secret `rk`. Direction i2r uses k_i2r with
 * recipient_kid = kid_i2r and sender_kid = kid_r2i; r2i mirrors it.
 * Thread-safe; secret.
 */
@Suppress("TooManyFunctions")
class Epoch private constructor(
    val id: ByteArray,
    val role: Role,
    val suite: Int,
    val policy: Policy,
    val created: Instant,
    private var sendKey: ByteArray?,
    private val sendSenderKid: Kid,
    private val sendRecipientKid: Kid,
    private var recvKey: ByteArray?,
    /** The recipient_kid that messages to this side carry. */
    val recvKid: Kid,
    private val recvSenderKid: Kid,
    private var rk: ByteArray?,
    private var lastSeq: Long,
    private var sent: Long,
    private var received: Long,
) {
    /** Standard base64 of the epoch id: hs.init `ctx` for rekey and reconnect. */
    val ctx: String get() = Base64s.encodeStd(id)

    @Synchronized
    fun counts(): Pair<Long, Long> = sent to received

    /** Whether the epoch reached its limit; the side that hits it initiates the rekey (§6.5). */
    @Synchronized
    fun needsRekey(now: Instant): Boolean {
        if (!policy.maxAge.isZero && Duration.between(created, now) >= policy.maxAge) return true
        val m = policy.maxMessages
        return m > 0 && (sent >= m || received >= m)
    }

    @Synchronized
    fun canSend(): Boolean = sendKey != null

    /** A copy of rk, the K_s of the next rekey. */
    @Synchronized
    internal fun rkCopy(): ByteArray = (rk ?: throw CryptoException.Protocol("epoch retired")).copyOf()

    /**
     * Seals [inner] in session mode, assigning `seq` (per epoch and direction,
     * from 1). The same id may be sealed again for a retransmission; every call
     * yields a new envelope with a fresh nonce.
     */
    @Synchronized
    fun seal(inner: Inner): ByteArray {
        val key = sendKey ?: throw CryptoException.Protocol("epoch retired")
        val padded = Inner.encode(inner.withSeq(lastSeq + 1), Mode.SESSION)
        try {
            val env = Envelope.sealSession(key, sendSenderKid, sendRecipientKid, padded)
            lastSeq++
            sent++
            return env
        } finally {
            Bytes.wipe(padded)
        }
    }

    /**
     * Opens a session-mode envelope addressed to this epoch: both kids must be
     * this epoch's receive kids, the AEAD must verify and the inner must parse.
     */
    @Synchronized
    fun open(env: Envelope): Inner {
        if (env.mode != Mode.SESSION) throw CryptoException.Protocol("wrong mode")
        val key = recvKey ?: throw CryptoException.Protocol("epoch retired")
        if (env.recipientKid != recvKid || env.senderKid != recvSenderKid) throw CryptoException.Protocol("kid")
        val padded = Envelope.openSession(env, key)
        try {
            val inner = Inner.decode(padded, Mode.SESSION)
            received++
            return inner
        } finally {
            Bytes.wipe(padded)
        }
    }

    /** Deletes the send key and rk when a newer epoch activates (§6.5). */
    @Synchronized
    internal fun retireSend() {
        Bytes.wipe(sendKey, rk)
        sendKey = null
        rk = null
    }

    @Synchronized
    fun destroy() {
        Bytes.wipe(sendKey, recvKey, rk)
        sendKey = null
        recvKey = null
        rk = null
    }

    /** The persistent form (device storage, encrypted under a Keystore key). Contains secrets. */
    @Synchronized
    fun export(): ByteArray {
        val b = JsonBuilder()
            .base64("id", id)
            .uint("role", role.wire.toLong())
            .uint("suite", suite.toLong())
            .uint("max_age_s", policy.maxAge.seconds)
            .uint("max_messages", policy.maxMessages)
            .uint("created_ms", created.toEpochMilli())
        sendKey?.let { b.base64("send_key", it) }
        b.base64("send_sender_kid", sendSenderKid.bytes())
            .base64("send_recipient_kid", sendRecipientKid.bytes())
            .base64("recv_key", recvKey ?: throw CryptoException.Protocol("epoch destroyed"))
            .base64("recv_kid", recvKid.bytes())
            .base64("recv_sender_kid", recvSenderKid.bytes())
        rk?.let { b.base64("rk", it) }
        return b.uint("last_seq", lastSeq).uint("sent", sent).uint("received", received).bytes()
    }

    override fun toString(): String = "Epoch[redacted]"

    companion object {
        /** How long receive keys of previous epochs are kept (§6.5). */
        val RECEIVE_KEY_RETENTION: Duration = Duration.ofDays(16)

        internal fun from(s: Schedule, role: Role, suite: Int, policy: Policy, now: Instant): Epoch {
            val i2r = s.kI2R.copyOf()
            val r2i = s.kR2I.copyOf()
            val ini = role == Role.INITIATOR
            // Initiator: sends i2r (recipient kid_i2r, sender kid_r2i), receives r2i; the responder mirrors it.
            return Epoch(
                id = s.epochId.copyOf(),
                role = role,
                suite = suite,
                policy = policy,
                created = now,
                sendKey = if (ini) i2r else r2i,
                sendSenderKid = if (ini) s.kidR2I else s.kidI2R,
                sendRecipientKid = if (ini) s.kidI2R else s.kidR2I,
                recvKey = if (ini) r2i else i2r,
                recvKid = if (ini) s.kidR2I else s.kidI2R,
                recvSenderKid = if (ini) s.kidI2R else s.kidR2I,
                rk = s.rk.copyOf(),
                lastSeq = 0,
                sent = 0,
                received = 0,
            )
        }

        /** Restores an epoch from [export]. */
        fun import(state: ByteArray): Epoch {
            val o = StrictJson.parseObject(state)
            val role = when (o.uint("role", 1, 2).toInt()) {
                1 -> Role.INITIATOR
                else -> Role.RESPONDER
            }
            val suite = o.uint("suite", 0, 255).toInt()
            Suite.check(suite, 0)
            val max = StrictJson.MAX_SAFE_INTEGER
            return Epoch(
                id = o.base64("id", Suite.EPOCH_ID_SIZE),
                role = role,
                suite = suite,
                policy = Policy(Duration.ofSeconds(o.uint("max_age_s", 0, max)), o.uint("max_messages", 0, max)),
                created = Instant.ofEpochMilli(o.uint("created_ms", 0, max)),
                sendKey = if (o.has("send_key")) o.base64("send_key", Suite.KEY_SIZE) else null,
                sendSenderKid = Kid(o.base64("send_sender_kid", Suite.KID_SIZE)),
                sendRecipientKid = Kid(o.base64("send_recipient_kid", Suite.KID_SIZE)),
                recvKey = o.base64("recv_key", Suite.KEY_SIZE),
                recvKid = Kid(o.base64("recv_kid", Suite.KID_SIZE)),
                recvSenderKid = Kid(o.base64("recv_sender_kid", Suite.KID_SIZE)),
                rk = if (o.has("rk")) o.base64("rk", Suite.KEY_SIZE) else null,
                lastSeq = o.uint("last_seq", 0, max),
                sent = o.uint("sent", 0, max),
                received = o.uint("received", 0, max),
            )
        }

        /** §6.5: of two crossing rekey hs.inits the one with the lower th1 (big-endian) wins. */
        fun ourRekeyWins(ourTh1: ByteArray, theirTh1: ByteArray): Boolean {
            for (i in 0 until minOf(ourTh1.size, theirTh1.size)) {
                val a = ourTh1[i].toInt() and 0xff
                val b = theirTh1[i].toInt() and 0xff
                if (a != b) return a < b
            }
            return ourTh1.size < theirTh1.size
        }
    }
}

/**
 * A session's current epoch plus the receive keys of previous epochs, kept
 * for [Epoch.RECEIVE_KEY_RETENTION]. Thread-safe.
 */
class Keyring {
    private var current: Epoch? = null
    private val retired = ArrayList<Pair<Epoch, Instant>>()

    /** Makes [e] current; the previous epoch's send key and rk are deleted at once. */
    @Synchronized
    fun activate(e: Epoch, now: Instant) {
        current?.let {
            it.retireSend()
            retired.add(it to now.plus(Epoch.RECEIVE_KEY_RETENTION))
        }
        current = e
        pruneLocked(now)
    }

    @Synchronized
    fun current(): Epoch? = current

    @Synchronized
    fun prune(now: Instant) = pruneLocked(now)

    private fun pruneLocked(now: Instant) {
        val it = retired.iterator()
        while (it.hasNext()) {
            val (e, until) = it.next()
            if (now.isAfter(until)) {
                e.destroy()
                it.remove()
            }
        }
    }

    /**
     * Routes a session envelope by recipient_kid to the current or a retained
     * epoch and opens it there; no other epoch is tried (§4.4). Every candidate
     * is compared, so the lookup does not reveal which one matched.
     */
    fun open(env: Envelope, now: Instant): Pair<Inner, Epoch> {
        val e = synchronized(this) {
            var found: Epoch? = null
            current?.let { if (env.recipientKid == it.recvKid) found = it }
            for ((r, until) in retired) {
                if (!now.isAfter(until) && env.recipientKid == r.recvKid && found == null) found = r
            }
            found
        } ?: throw CryptoException.Protocol("kid")
        return e.open(env) to e
    }

    @Synchronized
    fun destroy() {
        current?.destroy()
        current = null
        retired.forEach { it.first.destroy() }
        retired.clear()
    }

    /**
     * The persistent form: the current epoch and the retained receive keys of
     * previous epochs with their expiry (device storage, encrypted under a
     * Keystore key). Contains secrets.
     */
    @Synchronized
    fun export(): ByteArray {
        val b = JsonBuilder()
        current?.let { b.raw("current", it.export()) }
        val r = retired.map { (e, until) -> JsonBuilder().uint("until_ms", until.toEpochMilli()).raw("epoch", e.export()).build() }
        b.raw("retired", JsonBuilder.array(r))
        return b.bytes()
    }

    companion object {
        /** Restores a keyring from [export]. */
        fun import(state: ByteArray): Keyring {
            val o = StrictJson.parseObject(state)
            val k = Keyring()
            o.optObj("current")?.let { k.current = Epoch.import(it.rawBytes()) }
            for (r in o.array("retired")) {
                val ro = r.asObject()
                val e = Epoch.import(ro.obj("epoch").rawBytes())
                k.retired.add(e to Instant.ofEpochMilli(ro.uint("until_ms", 0, StrictJson.MAX_SAFE_INTEGER)))
            }
            return k
        }
    }
}
