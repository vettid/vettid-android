package com.vettid.core.vault

import com.vettid.core.altchan.AltParty
import com.vettid.core.altchan.AltRequests
import com.vettid.core.altchan.AltResults
import com.vettid.core.altchan.AltState
import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.AppIdentity
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.BuiltUnlock
import com.vettid.core.altchan.EnrollResult
import com.vettid.core.altchan.PendingUnlock
import com.vettid.core.altchan.RecoveryCode
import com.vettid.core.altchan.RecoveryResult
import com.vettid.core.altchan.SealedRequest
import com.vettid.core.altchan.UnlockOptions
import com.vettid.core.altchan.UnlockResult
import com.vettid.core.attestation.AttestationException
import com.vettid.core.attestation.EnclaveVerifier
import com.vettid.core.attestation.VerifiedEnclave
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.attestation.nitro.Measurements
import com.vettid.core.attestation.nitro.NitroVerifier
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.altchan.DeviceAttest
import com.vettid.core.crypto.envelope.Envelope
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.hpke.KemPublicKey
import com.vettid.core.crypto.invite.InviteBundle
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.session.Epoch
import com.vettid.core.crypto.session.Initiator
import com.vettid.core.crypto.session.InitiatorConfig
import com.vettid.core.crypto.session.Keyring
import com.vettid.core.crypto.session.PendingInit
import com.vettid.core.crypto.session.Policy
import com.vettid.core.crypto.session.Principal
import com.vettid.core.crypto.session.Purpose
import com.vettid.core.crypto.session.RelayAddr
import com.vettid.core.crypto.session.Responder
import com.vettid.core.crypto.session.ResponderConfig
import com.vettid.core.crypto.session.Rotation
import com.vettid.core.relay.DepositTokens
import com.vettid.core.relay.MailboxCollector
import com.vettid.core.relay.RelayAuth
import com.vettid.core.relay.RelayClient
import com.vettid.core.relay.RelayException
import com.vettid.core.relay.RelayLimits
import com.vettid.core.relay.RelayMessage
import com.vettid.core.relay.TokenException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/** The device's own keys (§3.2), unwrapped from the Keystore (`:core:keystore` DeviceKeys) for the device's lifetime. */
class DeviceSecrets(val identity: Ed25519PrivateKey, val kem: KemPrivateKey, val relay: Ed25519PrivateKey) {
    fun destroy() {
        identity.destroy()
        kem.destroy()
        relay.destroy()
    }

    companion object {
        fun generate() = DeviceSecrets(Ed25519PrivateKey.generate(), KemPrivateKey.generate(), Ed25519PrivateKey.generate())
    }
}

/** How a device is set up. */
class DeviceConfig(
    /** `app` (desktops and agents use other clients). */
    val role: String = ROLE_APP,
    /** The name the vault shows for this device (`profile.name`). */
    val name: String,
    /** The device's own relay base URL, as tokens name it (`aud`). */
    val relayUrl: String,
    val http: OkHttpClient,
    val store: DeviceStateStore,
    /**
     * Pins the Nitro root for vault.enrolled's attestation (§11.3). Null only
     * in in-process tests; the app and the dev stack always pass one.
     */
    val trust: AltTrust? = null,
    val clock: Clock = Clock.systemUTC(),
    val collectMode: MailboxCollector.Mode = MailboxCollector.Mode.LONG_POLL,
    val pollWait: Duration = Duration.ofSeconds(RelayClient.DEFAULT_WAIT_S),
    /** Interactive requests time out after 30 s (§8.1); a request is re-sent with the same inner id at most twice (§8.6). */
    val requestTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_S),
) {
    companion object {
        const val ROLE_APP = "app"
        const val DEFAULT_TIMEOUT_S = 30L
    }
}

/** A message from the vault: an event (no `re`) or a response. */
class VaultMessage(val inner: Inner) {
    val type: String get() = inner.type
    val id: String get() = inner.id
    val ts: Instant get() = inner.ts

    /** The body (`{}` when empty). */
    val body: JsonObject by lazy { VaultJson.parseObject(inner.body) }

    override fun toString(): String = "VaultMessage($type)"
}

/**
 * A request answered with `status: error` (§10.1): [code] is the spec's code;
 * [body] is the error response's body, when the code carries one (`exists`
 * with `{connection_id}` for `connection.invite.accept`, 0.10.2).
 */
class VaultOpException(val type: String, val code: String, message: String = "", val body: JsonObject? = null) :
    IOException("vault: $type: $code${if (message.isEmpty()) "" else " ($message)"}")

/** The device is not (yet) paired with a vault, or the vault sent something it should not have. */
class VaultStateException(message: String) : IOException("vault client: $message")

/**
 * The owner rejected this device's pairing or transfer on their phone after its
 * `hs.fin` (`device.pair.rejected`, VAULT-MESSAGING §6.7, §6.7.1, 0.10.5). The
 * device has dropped the handshake's epoch and the request token; pairing again
 * needs a new code from the owner. An app or desktop says "Rejected on your phone".
 */
class PairingRejectedException : IOException("vault client: pairing rejected on the owner's phone")

/** A scanned or pasted code is not a direct-transfer code (§6.7.1: a pairing QR of kind `p`, bundle kind `app`). */
class NotATransferCodeException(val expired: Boolean = false) :
    IOException(if (expired) "vault client: the transfer code has expired" else "vault client: not a transfer code")

/**
 * One owner device of a vault (VAULT-MESSAGING §6.7, §9.1, §11.3), the
 * Kotlin counterpart of vettid-vault's reference `client.Device`.
 *
 * - [start] runs the collector on the device's own mailbox: every message is
 *   deduplicated by msg_id, classified only by sender and recipient kid
 *   (§13.6), decrypted, applied and persisted before the relay ack (§8.3).
 * - Responses complete the request that awaits them; events go to [events]
 *   and to an inbox that [awaitEvent] searches first.
 * - Outgoing messages are sealed in the current epoch and kept in a
 *   persisted outbox until the relay accepts them; a request that gets no
 *   answer is re-sent with the same inner id at most twice (§8.6).
 * - Tokens are refreshed in both directions (§7.2), vault-initiated rekeys
 *   answered (§6.5) and identity rotations followed (§3.4).
 * - As an [AltParty] it builds and opens the alternate channel's sealed
 *   requests for `:core:altchan`'s [com.vettid.core.altchan.AltChannelFlow].
 *
 * Not persisted: an in-flight first handshake (a restart before device.paired
 * repeats [completeEnrollment]).
 */
@Suppress("TooManyFunctions", "LargeClass")
class VaultDevice private constructor(
    private val cfg: DeviceConfig,
    private val secrets: DeviceSecrets,
    private val st: DeviceState,
) : AltParty {
    private val lock = Mutex()
    private val clock = cfg.clock
    private val relays = ConcurrentHashMap<String, RelayClient>()
    private val own: RelayClient = relayFor(cfg.relayUrl)
    private var keyring: Keyring =
        st.vault?.sessions?.takeIf { it.isNotEmpty() }?.let { Keyring.import(Base64s.decodeStd(it)) } ?: Keyring()
    private var ini: Initiator? = null
    private val awaiting = ArrayList<Responder>()
    private var pendingUnlock: PendingUnlock? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Inner>>()
    private val inbox = ArrayList<VaultMessage>()
    private val inboxVersion = MutableStateFlow(0L)
    private val eventFlow =
        MutableSharedFlow<VaultMessage>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pairedState = MutableStateFlow(st.deviceId != null)
    private val sasState = MutableStateFlow<String?>(null)
    private val refusalCount = MutableStateFlow(0)

    /**
     * Deposits to the vault's mailbox under the vault's current token that the relay refused with
     * `token_revoked` since the vault last spoke to this device (a message opened under the session, §5). A vault
     * that removed this device denylists its relay key (§7.4), so every deposit is refused; a token the vault
     * revoked by itself is replaced by a new one. Not authenticated (the relay says so): never proof of anything.
     */
    val vaultRefusals: StateFlow<Int> = refusalCount.asStateFlow()

    /** Starts [vaultRefusals] again (after a sealed unlock result: the enclave knows this device). */
    fun clearVaultRefusals() {
        refusalCount.value = 0
    }
    private var collector: Job? = null
    private val collectorEnd = MutableStateFlow<String?>(null)

    /** The relay error code that ended the last collection ([start]), or null while it runs or never ended. */
    val collectionEnded: StateFlow<String?> = collectorEnd.asStateFlow()

    /** Every event from the vault (no responses), as it arrives. */
    val events: SharedFlow<VaultMessage> = eventFlow.asSharedFlow()

    /** True once device.paired arrived: the session with the vault is up. */
    val paired: StateFlow<Boolean> = pairedState.asStateFlow()

    val vaultId: String? get() = st.vaultId

    /** True while this app recovers a vault (§11.11): registered, until `credential.recover` or `credential.reset`. */
    val recovering: Boolean get() = st.recovery != null

    /**
     * While recovering: the vault's `credential_backup` from this app's unlock result (0.10.6, §11.11.5 step 1);
     * null before that unlock, or when the vault did not say (an older vault).
     */
    val recoveryCredentialBackup: Boolean? get() = st.recovery?.credentialBackup

    /**
     * The SAS of the pairing handshake in progress (§6.3, 0.10.3): set once the
     * vault's `hs.resp` checked out and this device sent `hs.fin`; null before.
     */
    val pairingSas: StateFlow<String?> = sasState.asStateFlow()
    val deviceId: String? get() = st.deviceId
    val name: String get() = st.name

    /** This device's relay address (§6.2). */
    val relayAddr: RelayAddr get() = RelayAddr(st.relayUrl, RelayAuth.mailboxId(secrets.relay.publicKey), secrets.relay.publicKey)

    /** The device's identity public key. */
    val identityKey: ByteArray get() = secrets.identity.publicKey

    val kemKey: KemPublicKey get() = secrets.kem.publicKey

    /** A copy of the alternate-channel state. */
    val altState: AltState get() = alt()

    private fun relayFor(url: String): RelayClient = relays.getOrPut(url.trimEnd('/')) { RelayClient(url, secrets.relay, cfg.http, clock) }

    private fun now(): Instant = Instant.now(clock)

    // --- persistence ---

    private fun save() {
        st.vault?.let { it.sessions = Base64s.encodeStd(keyring.export()) }
        cfg.store.save(stateJson.encodeToString(DeviceState.serializer(), st).toByteArray())
    }

    private fun alt(): AltState = st.alt?.let { AltState.parse(StrictJson.parseObject(it)) } ?: AltState()

    private fun setAlt(a: AltState) {
        st.alt = a.marshal()
    }

    // --- lifecycle ---

    /** Starts collecting from the device's mailbox in [scope] and flushes the outbox. */
    fun start(scope: CoroutineScope): Job {
        collector?.let { if (it.isActive) return it }
        collectorEnd.value = null
        val job = scope.launch {
            launch { flushOutbox() }
            try {
                MailboxCollector(own, cfg.collectMode, cfg.pollWait, clock = clock).run { handle(it) }
            } catch (e: RelayException) {
                // A terminal relay error ends the collection (MailboxCollector); it must never end the process. The
                // next start() collects again.
                collectorEnd.value = e.code
            }
        }
        collector = job
        return job
    }

    /**
     * The limits this device's relay advertises (RELAY-PROTOCOL §6.1; register
     * is idempotent). Apps offer invite lifetimes within them (§6.4).
     */
    suspend fun relayLimits(): RelayLimits = own.register().limits

    /** Stops collecting. */
    fun stop() {
        collector?.cancel()
        collector = null
    }

    /** One long-poll round (tests and the foreground-service path). */
    suspend fun pollOnce(): Int = MailboxCollector(own, wait = cfg.pollWait, clock = clock).pollOnce { handle(it) }

    // --- tokens (§7) ---

    private fun vaultRelayPk(): ByteArray = Base64s.decodeStd(requireVault().relayPk)

    private fun requireVault(): VaultRecord = st.vault ?: throw VaultStateException("not paired with a vault")

    /** A standing token for the vault (sub = the vault's relay key), 30 days. */
    private fun mintForVault(): String {
        val ttl = DepositTokens.STANDING_TTL
        val tok = own.mintToken(RelayAuth.encodeKey(vaultRelayPk()), st.relayUrl, ttl)
        st.issuedExpMs = now().plus(ttl).minusSeconds(SECONDS_PER_MINUTE).toEpochMilli()
        return tok
    }

    /** Checks a token the vault issued to this device; returns its expiry. */
    private fun heldFromVault(tok: String): Instant? {
        val v = st.vault ?: return null
        return try {
            val c = DepositTokens.verify(tok, Base64s.decodeStd(v.relayPk))
            if (c.iss != v.mailbox || c.sub != RelayAuth.encodeKey(secrets.relay.publicKey)) null else c.exp
        } catch (_: TokenException) {
            null
        }
    }

    private fun setVault(pr: Principal) {
        st.vault = VaultRecord(
            ik = Base64s.encodeStd(pr.ik()), kem = Base64s.encodeStd(pr.kem.bytes()), relayUrl = pr.relay.url,
            mailbox = pr.relay.mailbox, relayPk = Base64s.encodeStd(pr.relay.pk()),
        )
        keyring = Keyring()
    }

    // --- alternate channel (AltParty) ---

    override fun manifestSerialSeen(): Long = alt().manifestSerial

    override fun vaultId(): String? = st.vaultId

    private fun appIdentity() = AppIdentity(secrets.identity.publicKey, secrets.kem.publicKey, relayAddr, st.name)

    override suspend fun prepareEnroll(
        userGuid: String,
        pin: String,
        enclave: VerifiedEnclave,
        manifest: ReleaseManifest,
        attester: Attester,
    ): SealedRequest =
        lock.withLock {
            // §7.1: the app's open token for the vault's first deposit, ≤ 10 min.
            val open = own.mintOpenToken(st.relayUrl, Duration.ofMinutes(OPEN_TOKEN_MINUTES))
            val b = AltRequests.buildEnroll(userGuid, pin, enclave, manifest, attester, appIdentity(), open, alt(), now())
            setAlt(b.state)
            save()
            b.request
        }

    override suspend fun openEnrollResult(raw: ByteArray, requestId: String): EnrollResult =
        EnrollResult.parse(AltResults.open(raw, secrets.kem, AltResults.TYPE_ENROLL_RESULT, requestId))

    override suspend fun prepareUnlock(
        userGuid: String,
        pin: String,
        enclave: VerifiedEnclave,
        manifest: ReleaseManifest,
        attester: Attester,
        options: UnlockOptions,
    ): BuiltUnlock = lock.withLock {
        val vid = st.vaultId ?: throw VaultStateException("no vault id")
        val recovering = st.vault == null && st.recovery != null
        if (st.vault == null && !recovering) throw VaultStateException("not paired with a vault")
        // A recovering app does not know the vault's relay key yet: an open token for its mailbox (§11.11.5).
        val tok = if (recovering) own.mintOpenToken(st.relayUrl, Duration.ofMinutes(OPEN_TOKEN_MINUTES)) else mintForVault()
        val b = AltRequests.buildUnlock(userGuid, vid, pin, enclave, manifest, attester, secrets.identity, tok, alt(), options, now())
        pendingUnlock = b.pending
        save()
        b
    }

    override suspend fun openUnlockResult(raw: ByteArray): UnlockResult = lock.withLock {
        val p = pendingUnlock ?: throw VaultStateException("no pending unlock")
        val r = UnlockResult.parse(AltResults.open(raw, secrets.kem, AltResults.TYPE_UNLOCK_RESULT, p.requestId))
        pendingUnlock = null
        run {
            setAlt(AltRequests.applyUnlock(alt(), p, r))
            if (r.ok) {
                val bundle = r.vaultBundle
                if (bundle != null && st.vault == null && st.recovery != null) adoptBundle(bundle)
                // §11.11.5 step 1 (0.10.6): whether the password can recover the credential; kept across a restart.
                val rec = st.recovery
                if (rec != null && r.credentialBackup != null) st.recovery = rec.copy(credentialBackup = r.credentialBackup)
                r.token?.let { t ->
                    val exp = heldFromVault(t)
                    val v = st.vault
                    if (exp != null && v != null && exp.toEpochMilli() > v.tokenExpMs) {
                        v.token = t
                        v.tokenExpMs = exp.toEpochMilli()
                    }
                }
            }
            save()
        }
        r
    }

    /** vault.recovery.register (§11.11.3): builds the request and records the recovery. */
    suspend fun prepareRecoveryRegister(userGuid: String, code: RecoveryCode, enclave: VerifiedEnclave, attester: Attester): SealedRequest =
        lock.withLock {
            if (st.vault != null) throw VaultStateException("already paired with a vault")
            val req = AltRequests.buildRecoveryRegister(userGuid, code, enclave, attester, appIdentity(), now())
            st.vaultId = code.vaultId
            st.recovery = RecoveryRecord(code.recoveryId, req.requestId)
            save()
            req
        }

    /** Forgets a registration the enclave refused (§11.11.3), so that a new code can be tried. */
    suspend fun dropRecoveryRegistration() = lock.withLock {
        if (st.deviceId != null || st.vault != null) return@withLock
        st.recovery = null
        st.vaultId = null
        save()
    }

    /** True when the device has pinned a vault (enrolled, recovering after its unlock, or pairing). */
    val hasVault: Boolean get() = st.vault != null

    fun openRecoveryResult(raw: ByteArray, requestId: String): RecoveryResult =
        RecoveryResult.parse(AltResults.open(raw, secrets.kem, AltResults.TYPE_RECOVERY_RESULT, requestId))

    private fun adoptBundle(bundle: ByteArray) {
        val bo = StrictJson.parseObject(bundle)
        if (bo.uint("v", 1, 1) != 1L || bo.uint("suite", 2, 2) != 2L) throw VaultStateException("vault bundle")
        setVault(Principal.parse(bo))
    }

    // --- enrollment and pairing (§11.3, §6.7) ---

    /** Waits for vault.enrolled (§11.3), which pins the vault and gives this device its first token. */
    suspend fun awaitEnrolled(timeout: Duration = Duration.ofSeconds(AWAIT_DEFAULT_S)) {
        awaitEvent("vault.enrolled", timeout)
    }

    /**
     * Runs the first app's handshake (purpose app, ctx = vault id; since 0.10.3 it
     * carries the SAS commitment, but the vault answers it without approval, so no
     * code is shown) and waits for device.paired.
     */
    suspend fun completeEnrollment(timeout: Duration = Duration.ofSeconds(AWAIT_DEFAULT_S)) {
        lock.withLock {
            val v = requireVault()
            val ctx = st.recovery?.recoveryId ?: st.vaultId ?: throw VaultStateException("no vault id")
            if (v.token.isEmpty()) throw VaultStateException("no token for the vault")
            startInit(Purpose.APP, ctx, v.token, null)
            save()
        }
        awaitPaired(timeout)
    }

    /**
     * Waits for `device.paired`, or (0.10.5) `device.pair.rejected`, which ends the
     * wait with [PairingRejectedException] (§6.7: the owner rejected the pairing or
     * transfer after this device's `hs.fin`; [process] has already dropped its state).
     */
    private suspend fun awaitPaired(timeout: Duration) {
        val ev = awaitEventOf(setOf("device.paired", TYPE_PAIR_REJECTED), timeout)
        if (ev.type == TYPE_PAIR_REJECTED) throw PairingRejectedException()
        lock.withLock {
            val o = ev.body
            st.deviceId = VaultJson.str(o, "device_id")
            VaultJson.str(o, "vault_id")?.let { st.vaultId = it }
            val rel = VaultJson.str(o, "release")
            val num = VaultJson.long(o, "release_number")
            if (rel != null && rel.length == PCR_HEX && num != null && num > 0) setAlt(alt().copy(release = rel, releaseNumber = num))
            save()
        }
        pairedState.value = true
    }

    @Suppress("LongParameterList")
    private suspend fun startInit(
        purpose: Purpose,
        ctx: String,
        depositToken: String,
        profile: ByteArray?,
        deviceAttest: DeviceAttest? = null,
        id: String? = null,
        at: Instant = now(),
    ) {
        val v = requireVault()
        val tok = mintForVault()
        val i = Initiator.create(
            InitiatorConfig(
                purpose = purpose, ctx = ctx, identity = secrets.identity, staticKem = secrets.kem.publicKey, relay = relayAddr,
                token = tok, profile = profile, deviceAttest = deviceAttest, responderIk = Base64s.decodeStd(v.ik),
                responderEk = KemPublicKey.parse(Base64s.decodeStd(v.kem)),
                responderRelayKey = Base64s.decodeStd(v.relayPk), policy = Policy.VAULT_TO_DEVICE, id = id, now = at,
            ),
        )
        relayFor(v.relayUrl).deposit(v.mailbox, depositToken, i.envelope())
        ini?.abort()
        ini = i
    }

    // --- direct transfer, the new app's side (§6.7.1) ---

    /**
     * Starts a direct transfer to this phone from the old app's QR or link
     * (§6.7.1), as vettid-vault's `PairAttested`: fetches the claim from the
     * link's relay, checks it against the QR, pins the vault from the bundle and
     * sends `hs.init` (purpose app, ctx = the bundle's invite id) with the device
     * attestation over the §11.7 challenge (the `hs.init` id, an empty vault id
     * and its `ts`). The vault answers with `hs.resp` at once; this device sends
     * `hs.fin`, and the SAS appears in [pairingSas]. Returns when `hs.init` is sent.
     */
    suspend fun startTransfer(link: String, attester: Attester) {
        val q = try {
            InviteQr.parseLink(link)
        } catch (_: CryptoException) {
            throw NotATransferCodeException()
        } catch (_: IllegalArgumentException) {
            throw NotATransferCodeException()
        }
        if (q.kind != InviteKind.APP) throw NotATransferCodeException()
        if (!now().isBefore(Instant.ofEpochSecond(q.exp))) throw NotATransferCodeException(expired = true)
        val blob = relayFor(q.relay).getClaim(q.claimId)
        lock.withLock {
            if (st.deviceId != null) throw VaultStateException("already paired with a vault")
            val t = now()
            val b = try {
                InviteBundle.open(blob, q, t)
            } catch (e: CryptoException.Time) {
                throw NotATransferCodeException(expired = true).also { it.initCause(e) }
            }
            if (b.kind != InviteKind.APP) throw NotATransferCodeException()
            setVault(b.vault)
            st.vaultId = null
            sasState.value = null
            val at = t.truncatedTo(ChronoUnit.MILLIS)
            val id = Ulid.new(at)
            val attest = attester.attest(AltChannel.devattChallenge(id, "", Timestamps.formatMillis(at)))
            val profile = JsonBuilder().string("name", st.name).bytes()
            startInit(Purpose.APP, b.inviteId, b.token, profile, attest, id, at)
            save()
        }
    }

    /**
     * Waits for the transfer's `device.paired` (the old app's approval completes
     * the transfer, §6.7.1) or `device.pair.rejected` ([PairingRejectedException]).
     */
    suspend fun awaitTransfer(timeout: Duration) = awaitPaired(timeout)

    /** Drops a transfer this device started and has not completed (left the screen, timed out). */
    suspend fun abandonTransfer() = lock.withLock {
        if (st.deviceId != null) return@withLock
        dropPairing()
        st.vault = null
        sasState.value = null
        save()
    }

    // --- sending (§8) ---

    /** Seals a message in the current epoch and deposits it (through the outbox). Returns the inner id. */
    suspend fun send(
        type: String,
        body: JsonObject = EMPTY,
        id: String? = null,
    ): String = lock.withLock { sendLocked(id, type, body, null, null, null) }

    private suspend fun sendLocked(id: String?, type: String, body: JsonObject, re: String?, status: String?, exp: Instant?): String {
        val v = requireVault()
        val ep = keyring.current() ?: throw VaultStateException("no session with the vault")
        if (v.token.isEmpty()) throw VaultStateException("no token for the vault")
        val t = now()
        val iid = id ?: Ulid.new(t)
        val inner = Inner(id = iid, type = type, ts = t, re = re, status = status, exp = exp, body = VaultJson.bytes(body))
        val env = ep.seal(inner)
        val entry = OutboxEntry(iid, type, v.relayUrl, v.mailbox, v.token, Base64s.encodeStd(env))
        st.outbox.add(entry)
        save()
        deliver(entry)
        return iid
    }

    /** Deposits an outbox entry; removes it once the relay accepted it, or on a terminal relay error (rethrown). */
    private suspend fun deliver(e: OutboxEntry) {
        try {
            relayFor(e.relayUrl).deposit(e.mailbox, e.token, Base64s.decodeStd(e.envelope))
            st.outbox.remove(e)
            save()
        } catch (x: RelayException) {
            if (x.retryable) {
                e.attempts++
                save()
                throw x
            }
            countRefusal(x, e.mailbox, e.token)
            st.outbox.remove(e)
            save()
            throw x
        }
    }

    /**
     * A deposit to this device's vault under the vault's current token that the relay refused with `token_revoked`
     * counts towards [vaultRefusals] (§7.4: a vault that removed this device denylists it). A refusal by any other
     * mailbox, or under another token, does not.
     */
    private fun countRefusal(x: RelayException, mailbox: String, token: String) {
        val v = st.vault
        if (x.code == RelayException.TOKEN_REVOKED && v != null && mailbox == v.mailbox && token == v.token) refusalCount.value++
    }

    /** A deposit straight to the vault (handshake messages, outside the outbox), its refusal counted as [deliver]'s. */
    private suspend fun depositToVault(v: VaultRecord, payload: ByteArray) {
        try {
            relayFor(v.relayUrl).deposit(v.mailbox, v.token, payload)
        } catch (x: RelayException) {
            countRefusal(x, v.mailbox, v.token)
            throw x
        }
    }

    /** Re-deposits what the outbox still holds (after a restart or a transport failure). */
    suspend fun flushOutbox() {
        val entries = lock.withLock { st.outbox.toList() }
        for (e in entries) {
            try {
                lock.withLock { if (st.outbox.contains(e)) deliver(e) }
            } catch (_: IOException) {
                // retried at the next flush
            }
        }
    }

    /**
     * Sends a request and waits for its response (§8.1). Without an answer
     * within the request timeout, the same envelope id is re-sent at most
     * twice (§8.6); the vault answers a duplicate from its response cache.
     */
    suspend fun request(type: String, body: JsonObject = EMPTY, timeout: Duration = cfg.requestTimeout): VaultMessage {
        val id = Ulid.new(now())
        val d = CompletableDeferred<Inner>()
        pending[id] = d
        try {
            lock.withLock { sendLocked(id, type, body, null, null, null) }
            repeat(APP_RETRIES + 1) { attempt ->
                withTimeoutOrNull(timeout.toMillis()) { d.await() }?.let { return VaultMessage(it) }
                if (attempt < APP_RETRIES) lock.withLock { sendLocked(id, type, body, null, null, null) }
            }
            throw VaultStateException("$type: no response")
        } finally {
            pending.remove(id)
        }
    }

    /** [request], returning the body of an ok response or throwing [VaultOpException]. */
    suspend fun op(type: String, body: JsonObject = EMPTY, timeout: Duration = cfg.requestTimeout): JsonObject {
        val r = request(type, body, timeout)
        if (r.inner.status != Inner.STATUS_OK) {
            throw VaultOpException(type, r.inner.error?.code ?: "error", r.inner.error?.message ?: "", r.body.takeIf { it.isNotEmpty() })
        }
        return r.body
    }

    // --- receiving ---

    /**
     * Waits for an event of [type] (matching [match]); events that arrived
     * earlier are searched first and the match is taken out of the inbox.
     */
    suspend fun awaitEvent(
        type: String,
        timeout: Duration = Duration.ofSeconds(AWAIT_DEFAULT_S),
        match: (JsonObject) -> Boolean = { true },
    ): VaultMessage = awaitEventOf(setOf(type), timeout, match)

    private suspend fun awaitEventOf(
        types: Set<String>,
        timeout: Duration,
        match: (JsonObject) -> Boolean = { true },
    ): VaultMessage =
        withTimeout(timeout.toMillis()) {
            var found: VaultMessage? = null
            while (found == null) {
                val v = inboxVersion.value
                found = synchronized(inbox) {
                    val i = inbox.indexOfFirst { it.type in types && match(it.body) }
                    if (i >= 0) inbox.removeAt(i) else null
                }
                if (found == null) inboxVersion.first { it != v }
            }
            found
        }

    private fun publish(m: VaultMessage) {
        synchronized(inbox) {
            inbox.add(m)
            while (inbox.size > INBOX_MAX) inbox.removeAt(0)
        }
        inboxVersion.value++
        eventFlow.tryEmit(m)
    }

    /** Processes one collected message. Classification is by sender and recipient kid only (§13.6). */
    internal suspend fun handle(m: RelayMessage) = lock.withLock {
        val t = now()
        if (st.seen.containsKey(m.msgId)) return@withLock
        st.seen[m.msgId] = t.toEpochMilli()
        prune(st.seen, t)
        try {
            process(m, t)
        } catch (_: CryptoException) {
            // dropped (§13.6: a message that does not verify changes nothing)
        } catch (_: AttestationException) {
            // dropped
        } catch (_: TokenException) {
            // dropped
        } catch (_: IOException) {
            // A reply to the vault that the relay refused or could not take (a token refresh, a rekey's answer, a
            // handshake's fin). The message itself was handled; it is acked and never handled again. A refusal with
            // `token_revoked` is counted ([vaultRefusals]); a retryable one of an outbox deposit stays there. Letting it out
            // would end the collector, and without the ack the same message would fail again at every start, so the
            // device.unlinked queued behind it would never be read.
        }
        save()
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun process(m: RelayMessage, t: Instant) {
        val sender = m.senderKey() ?: return
        val raw = m.payload()
        val env = Envelope.parse(raw)
        if (env.mode == Mode.SEALED) {
            when {
                env.recipientKid == secrets.kem.publicKey.kid -> handleEnrolled(env, sender, t)
                ini != null && env.recipientKid == ini!!.ephKid -> handleResp(raw, sender, t)
            }
            return
        }
        val v = st.vault ?: return
        if (!Ed25519.equalPublic(sender, Base64s.decodeStd(v.relayPk))) return // §6.3: only the vault's relay key sends to a device
        val opened = try {
            keyring.open(env, t)
        } catch (_: CryptoException) {
            null
        }
        if (opened == null) {
            val r = awaiting.firstOrNull { it.kids.first == env.recipientKid && it.kids.second == env.senderKid } ?: return
            val fr = r.handleFin(raw, sender, t)
            keyring.activate(fr.epoch, t)
            awaiting.remove(r)
            return
        }
        val (inner, ep) = opened
        refusalCount.value = 0 // the vault spoke to this device under its session
        if (inner.type == TYPE_HS_INIT) {
            handleRekey(raw, ep, sender, t)
            return
        }
        inner.checkTime(t, inner.exp == null)
        if (st.seenInner.containsKey(inner.id)) return
        st.seenInner[inner.id] = t.toEpochMilli()
        prune(st.seenInner, t)
        when (inner.type) {
            // §10.3 (0.10.3, 0.10.4): every device.paired carries the device's standing token (after enrollment
            // or recovery a fresh one); it replaces the token of hs.resp before maintain() looks at it.
            "device.paired" -> storeVaultToken(VaultJson.parseObject(inner.body))
            // §6.7 (0.10.5): only a device still waiting for device.paired acts on it; a paired one drops it.
            TYPE_PAIR_REJECTED -> if (pairedState.value) return else dropPairing()
            "relay.token.issued" -> storeVaultToken(VaultJson.parseObject(inner.body))
            "relay.token.refresh" -> if (inner.re == null) {
                val tok = mintForVault()
                sendLocked(null, inner.type, standing(tok), inner.id, Inner.STATUS_OK, null)
            } else {
                storeVaultToken(VaultJson.parseObject(inner.body))
            }
            "identity.rotate" -> followRotation(inner.body)
        }
        val re = inner.re
        if (re != null) {
            pending[re]?.complete(inner)
        } else {
            publish(VaultMessage(inner))
        }
        maintain(t)
    }

    /**
     * `device.pair.rejected` (§6.7, 0.10.5): stop waiting, drop the handshake's epoch
     * and the request token of its `hs.resp`. Nothing is sent back (the vault has
     * denylisted that token); a redelivery finds no epoch to open it and is dropped.
     */
    private fun dropPairing() {
        sasState.value = null
        ini?.abort()
        ini = null
        awaiting.forEach { it.abort() }
        awaiting.clear()
        keyring.destroy()
        keyring = Keyring()
        st.vault?.let {
            it.token = ""
            it.tokenExpMs = 0
        }
    }

    private fun prune(m: MutableMap<String, Long>, t: Instant) {
        val cutoff = t.minus(SEEN_RETENTION).toEpochMilli()
        m.entries.removeAll { it.value < cutoff }
    }

    private fun handleEnrolled(env: Envelope, sender: ByteArray, t: Instant) {
        val (padded, _) = Envelope.openSealed(env, secrets.kem)
        val inner = Inner.decode(padded, Mode.SEALED)
        if (inner.type != "vault.enrolled") return
        val o = StrictJson.parseObject(inner.body)
        val bundle = o.base64("vault_bundle")
        val bo = StrictJson.parseObject(bundle)
        if (bo.uint("v", 1, 1) != 1L || bo.uint("suite", 2, 2) != 2L) return
        val pr = Principal.parse(bo)
        if (!Ed25519.equalPublic(sender, pr.relay.pk())) return
        if (!checkEnrolledAttestation(o.optString("attestation"), bundle, t)) return
        val vid = o.string("vault_id")
        val tok = o.string("token")
        setVault(pr)
        val exp = heldFromVault(tok)
        if (exp == null) {
            st.vault = null
            return
        }
        st.vault!!.token = tok
        st.vault!!.tokenExpMs = exp.toEpochMilli()
        st.vaultId = vid
        val a = alt()
        if (a.enrollNonce != null) setAlt(AltRequests.applyEnrolled(a, o.optUint("state_seq", 0, StrictJson.MAX_SAFE_INTEGER)))
        publish(VaultMessage(inner))
    }

    /** §11.3: nonce = the app's, user_data over the vault bundle, the PCRs the request was sealed to, fresh. */
    private fun checkEnrolledAttestation(attestationB64: String?, bundle: ByteArray, t: Instant): Boolean {
        val trust = cfg.trust ?: return true
        val a = alt()
        val nonce = a.enrollNonce ?: return false
        if (attestationB64 == null || a.enrollPcrs.length != PCR_HEX * 3) return false
        val doc = Base64s.decodeStd(attestationB64)
        val p = a.enrollPcrs
        val ms = Measurements(p.substring(0, PCR_HEX), p.substring(PCR_HEX, 2 * PCR_HEX), p.substring(2 * PCR_HEX))
        return try {
            EnclaveVerifier(NitroVerifier(trust.nitroRoots)).verifyEnrolled(doc, bundle, nonce, ms, t)
            true
        } catch (_: AttestationException) {
            false
        }
    }

    private suspend fun handleResp(raw: ByteArray, sender: ByteArray, t: Instant) {
        val i = ini ?: return
        val res = try {
            i.handleResp(raw, sender, t)
        } catch (e: CryptoException.Used) {
            ini = null
            throw e
        }
        ini = null
        val v = requireVault()
        res.resp.token?.let { tok ->
            heldFromVault(tok)?.let { exp ->
                v.token = tok
                v.tokenExpMs = exp.toEpochMilli()
            }
        }
        if (res.epoch.suite > v.suite) v.suite = res.epoch.suite
        keyring.activate(res.epoch, t)
        save()
        depositToVault(v, res.fin)
        res.sas?.let { sasState.value = it }
    }

    /** Answers a vault-initiated rekey (§6.5). */
    private suspend fun handleRekey(raw: ByteArray, ep: Epoch, sender: ByteArray, t: Instant) {
        if (keyring.current() !== ep) return
        val pi = PendingInit.openRekey(raw, ep, t)
        val v = requireVault()
        val (resp, env) = pi.respond(
            ResponderConfig(
                identity = secrets.identity, policy = Policy.VAULT_TO_DEVICE, pinnedSuite = v.suite, collectSender = sender,
                recordRelayKey = Base64s.decodeStd(v.relayPk), knownInitiatorIk = Base64s.decodeStd(v.ik), now = t,
            ),
        )
        try {
            depositToVault(v, env)
        } catch (e: IOException) {
            resp.abort()
            throw e
        }
        awaiting.add(resp)
    }

    private fun storeVaultToken(o: JsonObject) {
        val tok = VaultJson.str(o, "token") ?: return
        val kind = VaultJson.str(o, "kind")
        if (kind != null && kind != "standing") return
        val v = st.vault ?: return
        val exp = heldFromVault(tok) ?: return
        if (exp.toEpochMilli() > v.tokenExpMs) {
            v.token = tok
            v.tokenExpMs = exp.toEpochMilli()
        }
    }

    private fun followRotation(body: ByteArray) {
        val o = StrictJson.parseObject(body)
        val r = Rotation.parse(o.obj("rotation"))
        val v = st.vault ?: return
        val (finalIk, kem) = Rotation.resolveChain(Base64s.decodeStd(v.ik), listOf(r))
        v.ik = Base64s.encodeStd(finalIk)
        kem?.let { v.kem = Base64s.encodeStd(it.bytes()) }
    }

    private fun standing(tok: String) = buildJsonObject {
        put("kind", "standing")
        put("token", tok)
    }

    /** Refreshes tokens in both directions (§7.2). */
    private suspend fun maintain(t: Instant) {
        val v = st.vault ?: return
        keyring.current() ?: return
        try {
            if (st.issuedExpMs != 0L && Duration.between(t, Instant.ofEpochMilli(st.issuedExpMs)) < ISSUE_BEFORE) {
                val tok = mintForVault()
                sendLocked(null, "relay.token.issued", standing(tok), null, null, null)
            }
            val exp = Instant.ofEpochMilli(v.tokenExpMs)
            if (Duration.between(t, exp) < REFRESH_BEFORE && t.isBefore(exp)) {
                sendLocked(null, "relay.token.refresh", EMPTY, null, null, null)
            }
        } catch (_: IOException) {
            // retried at the next message
        }
    }

    // --- the Protean Credential (§3.5) ---

    internal val credentialLock = Mutex()

    internal fun credentialBlob(): String? = st.credential?.blob

    internal fun hasCredential(): Boolean = st.credential != null

    internal suspend fun keepUtks(utks: List<UtkRecord>) = lock.withLock {
        st.utks.addAll(utks)
        save()
    }

    internal suspend fun keepCredential(blob: String, version: Long) = lock.withLock {
        st.credential = CredentialCopy(blob, version)
        save()
    }

    internal suspend fun dropCredential() = lock.withLock {
        st.credential = null
        st.utks.clear()
        save()
    }

    internal suspend fun endRecovery() = lock.withLock {
        st.recovery = null
        save()
    }

    /** Takes an unexpired UTK from the pool, or null when it is empty. */
    internal suspend fun takeUtkOrNull(): UtkRecord? = lock.withLock {
        val t = now().toEpochMilli()
        while (st.utks.isNotEmpty()) {
            val u = st.utks.removeAt(0)
            if (u.expiresAtMs > t) {
                save()
                return@withLock u
            }
        }
        save()
        null
    }

    /** UTKs held (tests, diagnostics). */
    val utkCount: Int get() = st.utks.size

    /** The credential version this app holds, or null. */
    val credentialVersion: Long? get() = st.credential?.version

    /** Sends [type] with a caller-chosen inner id and awaits the response (sealed credential payloads bind the id). */
    internal suspend fun requestWithId(id: String, type: String, body: JsonObject, timeout: Duration = cfg.requestTimeout): VaultMessage {
        val d = CompletableDeferred<Inner>()
        pending[id] = d
        try {
            lock.withLock { sendLocked(id, type, body, null, null, null) }
            repeat(APP_RETRIES + 1) { attempt ->
                withTimeoutOrNull(timeout.toMillis()) { d.await() }?.let { return VaultMessage(it) }
                if (attempt < APP_RETRIES) lock.withLock { sendLocked(id, type, body, null, null, null) }
            }
            throw VaultStateException("$type: no response")
        } finally {
            pending.remove(id)
        }
    }

    internal fun newId(): String = Ulid.new(now())

    /** Forgets the vault and every key's state on this device (sign-out, transfer away). */
    suspend fun forget() = lock.withLock {
        stop()
        keyring.destroy()
        cfg.store.clear()
    }

    /**
     * Stops collecting and deletes the device's own mailbox at its relay with everything in it (RELAY-PROTOCOL
     * §6.10; idempotent): a replaced app erasing itself (owner decision, 2026-10-05).
     */
    suspend fun deleteMailbox() {
        stop()
        own.deleteMailbox()
    }

    companion object {
        private const val TYPE_HS_INIT = "hs.init"
        private const val TYPE_PAIR_REJECTED = "device.pair.rejected"
        private const val EVENT_BUFFER = 256
        private const val INBOX_MAX = 500
        private const val PCR_HEX = 96
        private const val APP_RETRIES = 2
        private const val OPEN_TOKEN_MINUTES = 10L
        private const val AWAIT_DEFAULT_S = 90L
        private const val SECONDS_PER_MINUTE = 60L
        private val SEEN_RETENTION: Duration = Duration.ofDays(16)
        private val ISSUE_BEFORE: Duration = Duration.ofDays(10)
        private val REFRESH_BEFORE: Duration = Duration.ofDays(3)
        internal val EMPTY = JsonObject(emptyMap())

        /** A new device with fresh state; registers its mailbox on its relay. */
        suspend fun create(cfg: DeviceConfig, secrets: DeviceSecrets): VaultDevice {
            val d = VaultDevice(cfg, secrets, DeviceState(cfg.role, cfg.name, cfg.relayUrl.trimEnd('/')))
            d.own.register()
            d.save()
            return d
        }

        /** Restores a device from its store; null if the store is empty. */
        fun load(cfg: DeviceConfig, secrets: DeviceSecrets): VaultDevice? {
            val b = cfg.store.load() ?: return null
            val st = stateJson.decodeFromString(DeviceState.serializer(), String(b))
            return VaultDevice(cfg, secrets, st)
        }
    }
}
