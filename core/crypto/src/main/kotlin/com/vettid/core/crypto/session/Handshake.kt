package com.vettid.core.crypto.session

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Kid
import com.vettid.core.crypto.Labels
import com.vettid.core.crypto.Suite
import com.vettid.core.crypto.altchan.DeviceAttest
import com.vettid.core.crypto.envelope.Envelope
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.envelope.Sealer
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.hpke.KemPublicKey
import java.time.Instant

/** Configuration of an hs.init (§6.1, §6.2). */
class InitiatorConfig(
    val purpose: Purpose,
    /** Invite id, pairing id, vault_id (first app), or empty for rekey (set from [current]). */
    val ctx: String,
    val identity: Ed25519PrivateKey,
    /** from.kem: the initiator's static ek. */
    val staticKem: KemPublicKey,
    val relay: RelayAddr,
    val token: String? = null,
    val reconnectToken: String? = null,
    val suites: List<Int> = listOf(Suite.SUITE_2),
    val profile: ByteArray? = null,
    val rotations: List<Rotation> = emptyList(),
    val deviceAttest: DeviceAttest? = null,
    /** hs.init sender_kid all-zero instead of kid(staticKem). */
    val anonymousSender: Boolean = false,
    /** The pinned ik_R (or the stored ik for rekey/reconnect). */
    val responderIk: ByteArray,
    /** ek_R; not used for rekey. */
    val responderEk: KemPublicKey?,
    /** R's relay key on record: the expected collect sender of hs.resp. */
    val responderRelayKey: ByteArray,
    /** Rekey only: the epoch hs.init travels in; K_s = its rk. */
    val current: Epoch? = null,
    val pinnedSuite: Int = 0,
    val policy: Policy,
    /** Inner ids and time; generated when null. */
    val id: String? = null,
    val finId: String? = null,
    val now: Instant? = null,
)

/** The initiator's result: the active epoch and hs.fin to deposit. */
class InitiatorResult(
    val epoch: Epoch,
    val fin: ByteArray,
    val resp: HsResp,
    /** The key sig_R verified under (pinned, or the end of a reconnect's chain). Store it. */
    val responderIk: ByteArray,
    /** The KEM key the last rotation announced, if any. */
    val responderKem: KemPublicKey?,
    val inner: Inner,
)

/**
 * The initiator side of one handshake. [create] builds and seals hs.init with a
 * fresh ephemeral KEM key; [handleResp] verifies hs.resp (sig_R before any
 * epoch key is used) and returns the new epoch and hs.fin.
 */
class Initiator private constructor(
    private val cfg: InitiatorConfig,
    val body: HsInit,
    private var eph: KemPrivateKey?,
    private val env: ByteArray,
    private var ks: ByteArray?,
) {
    val th1: ByteArray = Schedule.th1(env)

    /** The short authentication string; depends only on hs.init (§6.3). */
    val sas: String = Schedule.sas(ks!!, th1)

    private var done = false

    /** hs.init, ready to deposit. */
    fun envelope(): ByteArray = env.copyOf()

    /** The kid hs.resp carries as recipient_kid. */
    val ephKid: Kid get() = body.eph.kid

    @Synchronized
    fun abort() {
        done = true
        eph?.destroy()
        eph = null
        Bytes.wipe(ks)
        ks = null
    }

    /**
     * Processes hs.resp. [collectSender] is the relay `sender` of the deposit.
     * A message that does not decrypt under eph leaves the handshake pending;
     * once one has decrypted, any failure aborts it (§6.3 "MUST abort").
     */
    @Synchronized
    fun handleResp(raw: ByteArray, collectSender: ByteArray, now: Instant): InitiatorResult {
        if (done) throw CryptoException.Used("handshake")
        if (!Ed25519.equalPublic(collectSender, cfg.responderRelayKey)) throw CryptoException.Protocol("relay sender")
        val e = Envelope.parse(raw)
        if (e.mode != Mode.SEALED) throw CryptoException.Protocol("wrong mode")
        if (!e.senderKid.isAnonymous) throw CryptoException.Protocol("sender kid")
        val (padded, exp) = Envelope.openSealed(e, eph ?: throw CryptoException.Used("handshake"))
        try {
            return complete(e, padded, exp.export(Labels.HS_KE, Suite.KEY_SIZE), now)
        } finally {
            Bytes.wipe(padded)
            abort() // success or failure after decryption both end the state
        }
    }

    private fun complete(e: Envelope, padded: ByteArray, ke: ByteArray, now: Instant): InitiatorResult {
        try {
            val inner = Inner.decode(padded, Mode.SEALED)
            if (inner.type != HsTypes.RESP) throw CryptoException.Protocol("type")
            inner.checkTime(now, true)
            val resp = HsResp.parse(inner.body, cfg.purpose)
            Suite.checkChosen(resp.suite, cfg.suites, cfg.pinnedSuite)
            var verifyIk = cfg.responderIk
            var newKem: KemPublicKey? = null
            if (cfg.purpose == Purpose.RECONNECT) {
                val (ik, kem) = Rotation.resolveChain(cfg.responderIk, resp.rotations)
                verifyIk = ik
                newKem = kem
            }
            val th = Schedule.th(env, e.header())
            val sched = Schedule.derive(ks!!, ke, th1, th)
            try {
                // §6.3: I MUST verify sig_R before using any epoch key.
                if (!Schedule.verifyResp(verifyIk, th, resp.sig())) throw CryptoException.Signature("sig_R")
                val epoch = Epoch.from(sched, Role.INITIATOR, resp.suite, cfg.policy, now)
                val fin = epoch.seal(
                    Inner(
                        id = cfg.finId ?: Ulid.new(now),
                        type = HsTypes.FIN,
                        ts = now,
                        body = HsFin.marshal(Schedule.signFin(cfg.identity, th)),
                    ),
                )
                return InitiatorResult(epoch, fin, resp, verifyIk.copyOf(), newKem, inner)
            } finally {
                sched.destroy()
            }
        } finally {
            Bytes.wipe(ke)
        }
    }

    companion object {
        @Suppress("NestedBlockDepth")
        fun create(cfg: InitiatorConfig): Initiator {
            if (cfg.responderIk.size != Suite.ED25519_PUBLIC_SIZE || cfg.responderRelayKey.size != Suite.ED25519_PUBLIC_SIZE) {
                throw CryptoException.Protocol("config")
            }
            val rekey = cfg.purpose == Purpose.REKEY
            var ctx = cfg.ctx
            if (rekey) {
                val cur = cfg.current ?: throw CryptoException.Protocol("config")
                if (ctx.isEmpty()) ctx = cur.ctx else if (ctx != cur.ctx) throw CryptoException.Protocol("ctx")
            } else if (cfg.responderEk == null || cfg.current != null) {
                throw CryptoException.Protocol("config")
            }
            val now = cfg.now ?: Instant.now()
            val id = cfg.id ?: Ulid.new(now)
            val eph = KemPrivateKey.generate()
            try {
                val body = HsInit(
                    purpose = cfg.purpose,
                    ctx = ctx,
                    from = Principal(cfg.identity.publicKey, cfg.staticKem, cfg.relay),
                    eph = eph.publicKey,
                    token = cfg.token,
                    reconnectToken = cfg.reconnectToken,
                    suites = cfg.suites,
                    profile = cfg.profile,
                    rotations = cfg.rotations,
                    deviceAttest = cfg.deviceAttest,
                )
                val inner = Inner(id = id, type = HsTypes.INIT, ts = now, body = body.marshal())
                return if (rekey) {
                    // §6.2: a rekey hs.init travels in session mode under the
                    // current epoch, and K_s is that epoch's rk.
                    val cur = cfg.current!!
                    val ks = cur.rkCopy()
                    Initiator(cfg, body, eph, cur.seal(inner), ks)
                } else {
                    val padded = Inner.encode(inner, Mode.SEALED)
                    try {
                        val senderKid = if (cfg.anonymousSender) Kid.ANONYMOUS else cfg.staticKem.kid
                        val sealer = Sealer(cfg.responderEk!!, senderKid)
                        val env = sealer.seal(padded)
                        Initiator(cfg, body, eph, env, sealer.export(Labels.HS_KS, Suite.KEY_SIZE))
                    } finally {
                        Bytes.wipe(padded)
                    }
                }
            } catch (e: CryptoException) {
                eph.destroy()
                throw e
            }
        }
    }
}

/** Looks up the one static KEM key (current or retired) whose kid is [kid], or null (§4.4). */
fun interface KeyLookup {
    fun find(kid: Kid): KemPrivateKey?
}

/** Configuration of hs.resp. Calling [PendingInit.respond] is the approval (§6.4, §6.7). */
class ResponderConfig(
    val identity: Ed25519PrivateKey,
    val token: String? = null,
    val reconnectToken: String? = null,
    val rotations: List<Rotation> = emptyList(),
    val pinnedSuite: Int = 0,
    val policy: Policy,
    /** The relay `sender` of the hs.init deposit; MUST equal from.relay.pk. */
    val collectSender: ByteArray,
    /** Relay key on record (rekey, reconnect). */
    val recordRelayKey: ByteArray? = null,
    /** Stored ik of the peer (rekey, reconnect). */
    val knownInitiatorIk: ByteArray? = null,
    /** If set, must equal hs.init ctx (required for reconnect). */
    val expectedCtx: String? = null,
    val id: String? = null,
    val now: Instant? = null,
)

/** A decrypted, validated hs.init awaiting the responder's decision. */
class PendingInit private constructor(
    private val env: ByteArray,
    val inner: Inner,
    val body: HsInit,
    private var ks: ByteArray?,
) {
    val th1: ByteArray = Schedule.th1(env)
    val sas: String = Schedule.sas(ks!!, th1)
    private var used = false

    @Synchronized
    fun discard() {
        used = true
        Bytes.wipe(ks)
        ks = null
    }

    /** Builds hs.resp sealed to the initiator's ephemeral key, with sig_R over th. */
    @Synchronized
    fun respond(cfg: ResponderConfig): Pair<Responder, ByteArray> {
        if (used) throw CryptoException.Used("handshake")
        val verifyIk = checkInitiator(cfg)
        val chosen = Suite.negotiate(body.suites, cfg.pinnedSuite)
        val now = cfg.now ?: Instant.now()
        val sealer = Sealer(body.eph, Kid.ANONYMOUS)
        val ke = sealer.export(Labels.HS_KE, Suite.KEY_SIZE)
        val th = Schedule.th(env, sealer.header())
        val sched = try {
            Schedule.derive(ks!!, ke, th1, th)
        } finally {
            Bytes.wipe(ke)
        }
        try {
            val resp = HsResp(cfg.token, cfg.reconnectToken, chosen, cfg.rotations, Schedule.signResp(cfg.identity, th))
            val inner = Inner(id = cfg.id ?: Ulid.new(now), type = HsTypes.RESP, ts = now, body = resp.marshal(body.purpose))
            val padded = Inner.encode(inner, Mode.SEALED)
            val out = try {
                sealer.seal(padded)
            } finally {
                Bytes.wipe(padded)
            }
            discard()
            return Responder(sched, verifyIk, body.from.relay.pk(), chosen, cfg.policy) to out
        } catch (e: CryptoException) {
            sched.destroy()
            throw e
        }
    }

    // §6.3 and §6.6: returns the key sig_I must verify under. One branch per rule of the spec.
    @Suppress("CyclomaticComplexMethod")
    private fun checkInitiator(cfg: ResponderConfig): ByteArray {
        val from = body.from
        if (!Ed25519.equalPublic(cfg.collectSender, from.relay.pk())) throw CryptoException.Protocol("relay sender")
        if (cfg.expectedCtx != null && cfg.expectedCtx != body.ctx) throw CryptoException.Protocol("ctx")
        return when (body.purpose) {
            Purpose.REKEY -> {
                if (!Ed25519.equalPublic(cfg.collectSender, cfg.recordRelayKey)) throw CryptoException.Protocol("relay sender")
                if (!Ed25519.equalPublic(from.ik(), cfg.knownInitiatorIk)) throw CryptoException.Protocol("identity")
                from.ik()
            }
            Purpose.RECONNECT -> {
                if (cfg.expectedCtx == null) throw CryptoException.Protocol("config")
                if (!Ed25519.equalPublic(cfg.collectSender, cfg.recordRelayKey)) throw CryptoException.Protocol("relay sender")
                val known = cfg.knownInitiatorIk ?: throw CryptoException.Protocol("config")
                val (finalIk, kem) = Rotation.resolveChain(known, body.rotations)
                if (!Ed25519.equalPublic(finalIk, from.ik())) throw CryptoException.Protocol("identity")
                if (kem != null && kem != from.kem) throw CryptoException.Protocol("identity")
                from.ik()
            }
            // A new peer or device: trust on first use, under approval and SAS.
            else -> from.ik()
        }
    }

    companion object {
        /** Decrypts a sealed-mode hs.init (every purpose except rekey). */
        fun open(raw: ByteArray, lookup: KeyLookup, now: Instant): PendingInit {
            val e = Envelope.parse(raw)
            if (e.mode != Mode.SEALED) throw CryptoException.Protocol("wrong mode")
            val sk = lookup.find(e.recipientKid) ?: throw CryptoException.Protocol("no key for kid")
            val (padded, exp) = Envelope.openSealed(e, sk)
            try {
                val inner = Inner.decode(padded, Mode.SEALED)
                if (inner.type != HsTypes.INIT) throw CryptoException.Protocol("type")
                inner.checkTime(now, true)
                val body = HsInit.parse(inner.body)
                if (body.purpose == Purpose.REKEY) throw CryptoException.Protocol("purpose")
                if (!e.senderKid.isAnonymous && e.senderKid != body.from.kem.kid) throw CryptoException.Protocol("sender kid")
                return PendingInit(raw.copyOf(), inner, body, exp.export(Labels.HS_KS, Suite.KEY_SIZE))
            } finally {
                Bytes.wipe(padded)
            }
        }

        /** Decrypts a session-mode rekey hs.init under the current epoch; K_s = its rk (§6.2). */
        fun openRekey(raw: ByteArray, current: Epoch, now: Instant): PendingInit {
            val e = Envelope.parse(raw)
            if (e.mode != Mode.SESSION) throw CryptoException.Protocol("wrong mode")
            val inner = current.open(e)
            if (inner.type != HsTypes.INIT) throw CryptoException.Protocol("type")
            inner.checkTime(now, true)
            val body = HsInit.parse(inner.body)
            if (body.purpose != Purpose.REKEY) throw CryptoException.Protocol("purpose")
            if (body.ctx != current.ctx) throw CryptoException.Protocol("ctx")
            return PendingInit(raw.copyOf(), inner, body, current.rkCopy())
        }
    }
}

/** The responder after hs.resp, awaiting hs.fin. */
class Responder internal constructor(
    private val sched: Schedule,
    private val verifyIk: ByteArray,
    private val sender: ByteArray,
    private val suite: Int,
    private val policy: Policy,
) {
    private var done = false

    /** (recipient_kid, sender_kid) that hs.fin carries, for routing. */
    val kids: Pair<Kid, Kid> get() = sched.kidI2R to sched.kidR2I

    @Synchronized
    fun abort() {
        done = true
        sched.destroy()
    }

    /**
     * Opens hs.fin with the pending epoch's i2r key, verifies sig_I and only
     * then activates the epoch (§6.3). On failure the pending state is kept, so
     * a forged message cannot cancel a genuine handshake.
     */
    @Synchronized
    fun handleFin(raw: ByteArray, collectSender: ByteArray, now: Instant): Pair<Epoch, Inner> {
        if (done) throw CryptoException.Used("handshake")
        if (!Ed25519.equalPublic(collectSender, sender)) throw CryptoException.Protocol("relay sender")
        val e = Envelope.parse(raw)
        if (e.mode != Mode.SESSION) throw CryptoException.Protocol("wrong mode")
        val pending = Epoch.from(sched, Role.RESPONDER, suite, policy, now)
        try {
            val inner = pending.open(e)
            if (inner.type != HsTypes.FIN) throw CryptoException.Protocol("type")
            inner.checkTime(now, true)
            val sig = HsFin.parse(inner.body)
            if (!Schedule.verifyFin(verifyIk, sched.th, sig)) throw CryptoException.Signature("sig_I")
            done = true
            sched.destroy()
            return pending to inner
        } catch (ex: CryptoException) {
            pending.destroy()
            throw ex
        }
    }
}
