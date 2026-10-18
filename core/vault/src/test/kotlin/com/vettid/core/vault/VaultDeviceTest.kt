// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each. One class:
// every test shares the in-process fake vault and relay below.
@file:Suppress("MaxLineLength", "LargeClass")

package com.vettid.core.vault

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Kid
import com.vettid.core.crypto.call.CallMedia
import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.crypto.envelope.Envelope
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.invite.InviteBundle
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.session.Initiator
import com.vettid.core.crypto.session.InitiatorConfig
import com.vettid.core.crypto.session.Keyring
import com.vettid.core.crypto.session.Mailbox
import com.vettid.core.crypto.session.PendingInit
import com.vettid.core.crypto.session.Policy
import com.vettid.core.crypto.session.Principal
import com.vettid.core.crypto.session.Purpose
import com.vettid.core.crypto.session.RelayAddr
import com.vettid.core.crypto.session.Responder
import com.vettid.core.crypto.session.ResponderConfig
import com.vettid.core.relay.DepositTokens
import com.vettid.core.relay.LeaveUnacked
import com.vettid.core.relay.RelayAuth
import com.vettid.core.relay.RelayClient
import com.vettid.core.relay.RelayException
import com.vettid.core.relay.RelayMessage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The vault client against an in-process fake vault built from
 * `:core:crypto` (the responder side of the handshake, epochs, tokens) and a
 * local relay that only records deposits. Messages reach the device through
 * [VaultDevice.handle], as the collector would deliver them.
 */
class VaultDeviceTest {
    private lateinit var relay: MockWebServer
    private val deposits = LinkedBlockingQueue<Pair<String, ByteArray>>() // mailbox, payload
    private val claims = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** When set, the relay refuses every deposit with this code ([refuseStatus], 403 by default). */
    @Volatile
    private var refuseDeposits: String? = null

    @Volatile
    private var refuseStatus = 403

    /** Deposits the relay refused (any code). */
    private val refused = java.util.concurrent.atomic.AtomicInteger()

    /** When true, GET /v1/mailbox serves [queued] (empty after a short wait); otherwise it answers 404. */
    @Volatile
    private var serveMailbox = false

    /** When set, GET /v1/mailbox is refused with this code (403). */
    @Volatile
    private var refuseCollect: String? = null
    private val queued = LinkedBlockingQueue<RelayMessage>()
    private val acked = LinkedBlockingQueue<String>()

    /** The fake vault's keys and session with the device. */
    private inner class FakeVault {
        val ik = Ed25519PrivateKey.generate()
        val kem = KemPrivateKey.generate()
        val relayKey = Ed25519PrivateKey.generate()
        val relayUrl = relay.url("/").toString().trimEnd('/')
        val mailbox = Mailbox.id(relayKey.publicKey)
        val vaultId = "0123456789abcdef0123456789abcdef"
        val keyring = Keyring()
        var responder: Responder? = null
        val sender = RelayAuth.encodeKey(relayKey.publicKey)

        fun principal() = Principal(ik.publicKey, kem.publicKey, RelayAddr(relayUrl, mailbox, relayKey.publicKey))

        fun tokenFor(
            d: VaultDevice,
        ) = DepositTokens.mintStanding(relayKey, RelayAuth.encodeKey(d.relayAddr.pk()), d.relayAddr.url, Instant.now())

        fun msg(payload: ByteArray, from: String = sender) = RelayMessage(Ulid.new(), "", from, null, payload)

        /** vault.enrolled (§11.3), sealed to the device's KEM key. */
        fun enrolled(d: VaultDevice): RelayMessage {
            val bundle = JsonBuilder().uint("v", 1).uint("suite", 2).raw("ik", JsonBuilder.quote(Base64s.encodeStd(ik.publicKey)))
                .raw("kem", JsonBuilder.quote(Base64s.encodeStd(kem.publicKey.bytes())))
                .raw("relay", JsonBuilder().string("url", relayUrl).string("mailbox", mailbox).base64("pk", relayKey.publicKey).build()).bytes()
            val body = JsonBuilder().string("vault_id", vaultId).base64("vault_bundle", bundle).string("token", tokenFor(d)).uint("state_seq", 2).bytes()
            val inner = Inner(id = Ulid.new(), type = "vault.enrolled", ts = Instant.now(), body = body)
            return msg(Envelope.sealSealed(d.kemKey, Kid.ANONYMOUS, Inner.encode(inner, Mode.SEALED)).first)
        }

        /** Answers the device's hs.init (purpose app). */
        fun respond(raw: ByteArray, d: VaultDevice): RelayMessage {
            val p = PendingInit.open(raw, { k -> if (k == kem.publicKey.kid) kem else null }, Instant.now())
            assertEquals(Purpose.APP, p.body.purpose)
            assertEquals(vaultId, p.body.ctx)
            val (r, env) = p.respond(
                ResponderConfig(identity = ik, token = tokenFor(d), policy = Policy.VAULT_TO_DEVICE, collectSender = d.relayAddr.pk()),
            )
            responder = r
            return msg(env)
        }

        fun fin(raw: ByteArray, d: VaultDevice) {
            val fr = responder!!.handleFin(raw, d.relayAddr.pk(), Instant.now())
            // The enrollment handshake carries the commitment (0.10.3); no code is shown, but both sides have it.
            assertEquals(6, fr.sas!!.length)
            keyring.activate(fr.epoch, Instant.now())
        }

        fun seal(type: String, body: String, re: String? = null, status: String? = null, id: String = Ulid.new()): RelayMessage =
            msg(keyring.current()!!.seal(Inner(id = id, type = type, ts = Instant.now(), re = re, status = status, body = body.toByteArray())))

        fun open(raw: ByteArray): Inner = keyring.open(Envelope.parse(raw), Instant.now()).first
    }

    private fun nextDeposit(): Pair<String, ByteArray> = deposits.poll(10, TimeUnit.SECONDS) ?: error("no deposit")

    @Before
    fun start() {
        relay = MockWebServer()
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/v1/register" -> MockResponse.Builder().code(201).body(
                        """{"mailbox_id":"x","limits":{"max_payload_bytes":262144,"message_ttl_seconds":1,"visibility_timeout_seconds":60,""" +
                            """"max_token_lifetime_seconds":1,"open_token_max_lifetime_seconds":1,"max_claim_bytes":1,"claim_ttl_seconds":1}}""",
                    ).build()
                    path.startsWith("/v1/mailbox/") && request.method == "POST" && refuseDeposits != null -> {
                        refused.incrementAndGet()
                        MockResponse.Builder().code(refuseStatus).body("""{"code":"$refuseDeposits","message":""}""").build()
                    }
                    path == "/v1/mailbox" && request.method == "GET" && refuseCollect != null ->
                        MockResponse.Builder().code(403).body("""{"code":"$refuseCollect","message":""}""").build()
                    path == "/v1/mailbox" && request.method == "GET" && serveMailbox -> {
                        val m = queued.poll(200, TimeUnit.MILLISECONDS)
                        val msgs = if (m == null) "" else JsonBuilder().string("msg_id", m.msgId).string("sender", m.sender)
                            .base64("payload", m.payload()).build()
                        MockResponse.Builder().code(200).body("""{"messages":[$msgs]}""").build()
                    }
                    path.startsWith("/v1/mailbox/") && request.method == "DELETE" -> {
                        acked.add(path.removePrefix("/v1/mailbox/"))
                        MockResponse.Builder().code(204).build()
                    }
                    path.startsWith("/v1/mailbox/") && request.method == "POST" -> {
                        val o = StrictJson.parseObject(request.body!!.toByteArray())
                        deposits.add(path.removePrefix("/v1/mailbox/") to o.base64("payload"))
                        MockResponse.Builder().code(201).body("""{"msg_id":"${Ulid.new()}"}""").build()
                    }
                    path.startsWith("/v1/claim/") && request.method == "GET" -> claims.remove(path.removePrefix("/v1/claim/"))?.let {
                        MockResponse.Builder().code(200).addHeader("Content-Type", "application/octet-stream").body(okio.Buffer().write(it)).build()
                    } ?: MockResponse.Builder().code(404).body("""{"code":"claim_unknown","message":""}""").build()
                    else -> MockResponse.Builder().code(404).body("""{"code":"not_found","message":""}""").build()
                }
            }
        }
        relay.start()
    }

    @After
    fun stop() = relay.close()

    /** The phone's monotonic clock as [DeviceConfig.elapsedMs] reads it (milliseconds), for the vault-clock test. */
    @Volatile
    private var elapsed = 0L

    private fun config(store: DeviceStateStore, timeout: java.time.Duration = java.time.Duration.ofSeconds(5)) =
        DeviceConfig(
            name = "phone", relayUrl = relay.url("/").toString().trimEnd('/'), http = OkHttpClient(), store = store, requestTimeout = timeout,
            elapsedMs = { elapsed },
        )

    /** Enrollment, the first handshake and device.paired: a device with a live session. */
    private suspend fun pairedDevice(
        scope: CoroutineScope,
        store: DeviceStateStore = InMemoryDeviceStateStore(),
        secrets: DeviceSecrets = DeviceSecrets.generate(),
        timeout: java.time.Duration = java.time.Duration.ofSeconds(5),
    ): Pair<VaultDevice, FakeVault> {
        val d = VaultDevice.create(config(store, timeout), secrets)
        val v = FakeVault()
        d.handle(v.enrolled(d))
        d.awaitEnrolled(java.time.Duration.ofSeconds(1))
        assertEquals(v.vaultId, d.vaultId)
        val paired = scope.async(Dispatchers.IO) { d.completeEnrollment(java.time.Duration.ofSeconds(10)) }
        val (mbx, init) = nextDeposit()
        assertEquals(v.mailbox, mbx)
        d.handle(v.respond(init, d))
        v.fin(nextDeposit().second, d)
        d.handle(v.seal("device.paired", """{"device_id":"dev-1","role":"app","vault_id":"${v.vaultId}","release":"${"a3".repeat(48)}","release_number":3}"""))
        paired.await()
        assertEquals("dev-1", d.deviceId)
        assertTrue(d.paired.value)
        assertEquals(3, d.altState.releaseNumber)
        return d to v
    }

    @Test
    fun enrollPairRequestAndEvents() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d)
            // A request and its response (§8.1).
            val status = async(Dispatchers.IO) { api.status() }
            val req = v.open(nextDeposit().second)
            assertEquals("vault.status", req.type)
            d.handle(v.seal("vault.status", """{"vault_id":"${v.vaultId}","state_seq":4,"header_seq":4,"provisional":false,"devices":1,"connections":0}""", re = req.id, status = Inner.STATUS_OK))
            assertEquals(4, status.await().stateSeq)

            // An error response carries the spec's code (§10.1).
            val failing = async(Dispatchers.IO) { runCatching { api.itemGet("nope") } }
            val r2 = v.open(nextDeposit().second)
            d.handle(
                v.msg(
                    v.keyring.current()!!.seal(
                        Inner(id = Ulid.new(), type = r2.type, ts = Instant.now(), re = r2.id, status = Inner.STATUS_ERROR, error = com.vettid.core.crypto.envelope.InnerError("not_found")),
                    ),
                ),
            )
            val e = failing.await().exceptionOrNull() as VaultOpException
            assertEquals("not_found", e.code)

            // Events reach the inbox and the flow; a duplicate msg_id or inner id is dropped (§8.2).
            val m = v.seal("message.new", """{"connection_id":"c1","message_id":"m1","direction":"in","text":"hi","sent_at":"2026-10-04T00:00:00.000Z","delivered":true,"read":false}""")
            d.handle(m)
            d.handle(m)
            val id = Ulid.new()
            d.handle(v.seal("message.new", """{"connection_id":"c1","message_id":"m2","direction":"in","text":"again","sent_at":"x"}""", id = id))
            d.handle(v.seal("message.new", """{"connection_id":"c1","message_id":"m2","direction":"in","text":"again","sent_at":"x"}""", id = id))
            assertEquals("hi", VaultJson.str(api.awaitEvent("message.new") { VaultJson.str(it, "text") == "hi" }, "text"))
            assertEquals("again", VaultJson.str(api.awaitEvent("message.new"), "text"))
            assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
                runBlocking { d.awaitEvent("message.new", java.time.Duration.ofMillis(200)) }
            }

            // Only the vault's relay key may send to the device (§6.3).
            d.handle(v.msg(v.keyring.current()!!.seal(Inner(id = Ulid.new(), type = "message.new", ts = Instant.now(), body = "{}".toByteArray())), RelayAuth.encodeKey(Ed25519PrivateKey.generate().publicKey)))
            assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
                runBlocking { d.awaitEvent("message.new", java.time.Duration.ofMillis(200)) }
            }

            // relay.token.refresh from the vault is answered with a fresh standing token (§7.2).
            d.handle(v.seal("relay.token.refresh", "{}"))
            val ans = v.open(nextDeposit().second)
            assertEquals("relay.token.refresh", ans.type)
            assertNotNull(ans.re)
            val tok = VaultJson.str(VaultJson.parseObject(ans.body), "token")!!
            val c = DepositTokens.verify(tok, d.relayAddr.pk())
            assertEquals(RelayAuth.encodeKey(v.relayKey.publicKey), c.sub)
        }
    }

    /**
     * 0.10.5 (§6.7, §6.7.1): the owner rejects after the new device's hs.fin; the vault sends
     * device.pair.rejected under the handshake's epoch. The device stops waiting, drops the epoch and
     * the request token of hs.resp, and sends nothing back; a redelivery or a late device.paired is dropped.
     */
    @Test
    fun pairingRejectedEndsTheWaitAndDropsTheHandshake() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val d = VaultDevice.create(config(store), DeviceSecrets.generate())
            val v = FakeVault()
            d.handle(v.enrolled(d))
            d.awaitEnrolled(java.time.Duration.ofSeconds(1))
            val waiting = async(Dispatchers.IO) { runCatching { d.completeEnrollment(java.time.Duration.ofSeconds(10)) } }
            val (_, init) = nextDeposit()
            d.handle(v.respond(init, d))
            v.fin(nextDeposit().second, d)
            val rejected = v.seal("device.pair.rejected", "{}")
            d.handle(rejected)
            assertTrue(waiting.await().exceptionOrNull() is PairingRejectedException)
            assertTrue(!d.paired.value)
            assertNull(d.deviceId)
            // The request token and the epoch are gone, also from the persisted state.
            val saved = stateJson.decodeFromString(DeviceState.serializer(), String(store.load()!!))
            assertEquals("", saved.vault!!.token)
            assertEquals(0L, saved.vault!!.tokenExpMs)
            // Nothing was sent back.
            assertNull(deposits.poll(300, TimeUnit.MILLISECONDS))
            // A redelivery (new msg_id) or a device.paired after it finds no epoch: dropped.
            d.handle(v.msg(rejected.payload()))
            d.handle(v.seal("device.paired", """{"device_id":"dev-1","role":"app","vault_id":"${v.vaultId}"}"""))
            assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
                runBlocking { d.awaitEvent("device.paired", java.time.Duration.ofMillis(200)) }
            }
            assertTrue(!d.paired.value)
            // Pairing again needs a new code: there is no token to start a handshake with.
            assertThrows(VaultStateException::class.java) { runBlocking { d.completeEnrollment(java.time.Duration.ofMillis(200)) } }
        }
    }

    /** A paired device drops a device.pair.rejected (only a device waiting for device.paired acts on it). */
    @Test
    fun aPairedDeviceIgnoresAPairingRejection() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            d.handle(v.seal("device.pair.rejected", "{}"))
            assertTrue(d.paired.value)
            assertEquals("dev-1", d.deviceId)
            assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
                runBlocking { d.awaitEvent("device.pair.rejected", java.time.Duration.ofMillis(200)) }
            }
            // The session still works.
            val status = async(Dispatchers.IO) { d.op("vault.status") }
            val req = v.open(nextDeposit().second)
            d.handle(v.seal("vault.status", """{"vault_id":"x"}""", re = req.id, status = Inner.STATUS_OK))
            assertEquals("x", VaultJson.str(status.await(), "vault_id"))
        }
    }

    @Test
    fun answersAVaultInitiatedRekey() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val ini = Initiator.create(
                InitiatorConfig(
                    purpose = Purpose.REKEY, ctx = "", identity = v.ik, staticKem = v.kem.publicKey, relay = v.principal().relay,
                    responderIk = d.identityKey, responderEk = null, responderRelayKey = d.relayAddr.pk(), current = v.keyring.current(),
                    policy = Policy.VAULT_TO_DEVICE,
                ),
            )
            d.handle(v.msg(ini.envelope()))
            val res = ini.handleResp(nextDeposit().second, d.relayAddr.pk(), Instant.now())
            v.keyring.activate(res.epoch, Instant.now())
            d.handle(v.msg(res.fin))
            // The device now answers in the new epoch.
            val status = async(Dispatchers.IO) { d.op("vault.status") }
            val raw = nextDeposit().second
            val req = v.open(raw)
            assertEquals(res.epoch.recvKid, Envelope.parse(raw).recipientKid)
            d.handle(v.seal("vault.status", """{"vault_id":"x"}""", re = req.id, status = Inner.STATUS_OK))
            assertEquals("x", VaultJson.str(status.await(), "vault_id"))
        }
    }

    /**
     * A replaced phone (its relay key on the vault's denylist, §7.4) has every deposit to the vault refused with
     * `token_revoked`. The device counts them until the vault speaks to it again; other refusals do not count.
     */
    @Test
    fun refusedDepositsToTheVaultAreCountedUntilTheVaultSpeaks() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d)
            assertEquals(0, d.vaultRefusals.value)
            refuseDeposits = RelayException.TOKEN_REVOKED
            repeat(3) {
                val e = runCatching { api.status() }.exceptionOrNull()
                assertTrue(e is RelayException && e.code == RelayException.TOKEN_REVOKED)
            }
            assertEquals(3, d.vaultRefusals.value)
            refuseDeposits = RelayException.TOKEN_EXPIRED
            runCatching { api.status() }
            assertEquals(3, d.vaultRefusals.value)
            refuseDeposits = null
            // Any message the vault seals to this device under the session shows that it still knows it.
            d.handle(v.seal("message.new", """{"connection_id":"c1","message_id":"m1","direction":"in","text":"hi","sent_at":"x"}"""))
            assertEquals(0, d.vaultRefusals.value)
            refuseDeposits = RelayException.TOKEN_REVOKED
            runCatching { api.status() }
            assertEquals(1, d.vaultRefusals.value)
            d.clearVaultRefusals()
            assertEquals(0, d.vaultRefusals.value)
            refuseDeposits = null
        }
    }

    /**
     * W9 staging (2026-10-06): the old phone of a recovery found a vault message in its mailbox whose answer the
     * relay refused with `token_revoked` (the vault had denylisted it, §7.4). The refusal escaped the handler, ended
     * the collector and killed the process, and the unacked message did the same at every launch, so the
     * `device.unlinked` behind it was never read. Now the refused answer is counted as a refusal by the vault
     * (RefusalWatch then offers the erase), the message is handled once and acked, and the next one is read.
     */
    @Test
    fun aRefusedAnswerToTheVaultNeitherThrowsNorRepeats() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val secrets = DeviceSecrets.generate()
            val (d, v) = pairedDevice(this, store, secrets)
            refuseDeposits = RelayException.TOKEN_REVOKED
            val refresh = v.seal("relay.token.refresh", "{}")
            d.handle(refresh) // does not throw
            assertEquals(1, refused.get())
            assertEquals(1, d.vaultRefusals.value)
            // The message was handled once, also across a restart (its msg_id was saved with the refusal).
            val again = VaultDevice.load(config(store), secrets)!!
            again.handle(refresh)
            assertEquals(1, refused.get())
            // A rekey whose answer is refused: counted the same way, nothing thrown.
            val ini = Initiator.create(
                InitiatorConfig(
                    purpose = Purpose.REKEY, ctx = "", identity = v.ik, staticKem = v.kem.publicKey, relay = v.principal().relay,
                    responderIk = d.identityKey, responderEk = null, responderRelayKey = d.relayAddr.pk(), current = v.keyring.current(),
                    policy = Policy.VAULT_TO_DEVICE,
                ),
            )
            d.handle(v.msg(ini.envelope()))
            assertEquals(2, refused.get())
            assertEquals(1, d.vaultRefusals.value) // the rekey came from the vault (reset), then its answer was refused
            refuseDeposits = null
        }
    }

    /** Other relay errors on an answer neither throw nor count as a refusal by the vault. */
    @Test
    fun otherRelayErrorsOnAnAnswerAreNotRefusals() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            for (code in listOf(RelayException.TOKEN_EXPIRED, RelayException.MAILBOX_UNKNOWN, RelayException.TOKEN_INVALID)) {
                refuseDeposits = code
                d.handle(v.seal("relay.token.refresh", "{}"))
                assertEquals(code, 0, d.vaultRefusals.value)
            }
            refuseDeposits = null
        }
    }

    /** The collector itself: a refused answer is acked and the device.unlinked behind it arrives; nothing escapes. */
    @Test
    fun theCollectorSurvivesARefusedAnswerAndReadsTheNextMessage() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val escaped = LinkedBlockingQueue<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> escaped.add(e) })
            refuseDeposits = RelayException.TOKEN_REVOKED
            serveMailbox = true
            val refresh = v.seal("relay.token.refresh", "{}")
            val unlinked = v.seal("device.unlinked", """{"reason":"replaced"}""")
            queued.add(refresh)
            queued.add(unlinked)
            val job = d.start(scope)
            assertEquals("replaced", VaultJson.str(d.awaitEvent("device.unlinked", java.time.Duration.ofSeconds(10)).body, "reason"))
            assertEquals(refresh.msgId, acked.poll(10, TimeUnit.SECONDS))
            assertEquals(unlinked.msgId, acked.poll(10, TimeUnit.SECONDS))
            assertTrue(job.isActive)
            job.cancel()
            assertTrue(escaped.isEmpty())
            serveMailbox = false
            refuseDeposits = null
        }
    }

    /** A terminal relay error on collecting ends the collection quietly; a later start collects again. */
    @Test
    fun aTerminalCollectErrorEndsTheCollectionWithoutEscaping() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, _) = pairedDevice(this)
            val escaped = LinkedBlockingQueue<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> escaped.add(e) })
            refuseCollect = RelayException.TOKEN_REVOKED
            d.start(scope).join()
            assertEquals(RelayException.TOKEN_REVOKED, d.collectionEnded.value)
            assertTrue(escaped.isEmpty())
            refuseCollect = null
            serveMailbox = true
            val job = d.start(scope)
            assertNull(d.collectionEnded.value)
            assertTrue(job.isActive)
            job.cancel()
            serveMailbox = false
        }
    }

    private fun savedState(store: DeviceStateStore) = stateJson.decodeFromString(DeviceState.serializer(), String(store.load()!!))

    /**
     * A deposit refused with a stale or replayed signature (a request signed before the phone froze) stays in the
     * outbox and goes out at the next flush, signed afresh; a terminal refusal drops it (§8.6).
     */
    @Test
    fun aDepositRefusedForAStaleSignatureStaysInTheOutbox() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val (d, v) = pairedDevice(this, store)
            refuseStatus = 401
            refuseDeposits = RelayException.TIMESTAMP_STALE
            val e = runCatching { d.send("message.send", buildJsonObject { put("text", "hi") }) }.exceptionOrNull()
            assertTrue(e is RelayException && e.code == RelayException.TIMESTAMP_STALE)
            assertEquals(RelayClient.DEFAULT_ATTEMPTS, refused.get()) // each attempt signed afresh, then kept
            assertEquals(1, savedState(store).outbox.size)
            refuseDeposits = null
            d.flushOutbox()
            assertEquals("message.send", v.open(nextDeposit().second).type)
            assertEquals(0, savedState(store).outbox.size)
            // A terminal refusal drops the entry.
            refuseStatus = 403
            refuseDeposits = RelayException.TOKEN_INVALID
            runCatching { d.send("message.send", buildJsonObject { put("text", "again") }) }
            assertEquals(0, savedState(store).outbox.size)
            refuseDeposits = null
        }
    }

    /**
     * The vault rekeys at every unlock and switches to the new epoch on hs.resp (vettid-vault inbound.go, §6.5).
     * An app that ends between its hs.resp and the vault's hs.fin keeps the handshake (persisted), so the hs.fin
     * after the restart completes it. A message of the new epoch ahead of hs.fin is neither recorded as seen nor
     * acked (LeaveUnacked), and its redelivery opens after hs.fin.
     */
    @Test
    fun aRekeySurvivesARestartAndAMessageAheadOfItsFinWaits() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val secrets = DeviceSecrets.generate()
            val (d, v) = pairedDevice(this, store, secrets)
            val ini = Initiator.create(
                InitiatorConfig(
                    purpose = Purpose.REKEY, ctx = "", identity = v.ik, staticKem = v.kem.publicKey, relay = v.principal().relay,
                    responderIk = d.identityKey, responderEk = null, responderRelayKey = d.relayAddr.pk(), current = v.keyring.current(),
                    policy = Policy.VAULT_TO_DEVICE,
                ),
            )
            d.handle(v.msg(ini.envelope()))
            val res = ini.handleResp(nextDeposit().second, d.relayAddr.pk(), Instant.now())
            v.keyring.activate(res.epoch, Instant.now())
            d.stop() // the process ends before hs.fin arrives
            val again = VaultDevice.load(config(store), secrets)!!
            val early = v.seal("message.new", """{"connection_id":"c1","message_id":"m9","direction":"in","text":"early","sent_at":"x"}""")
            assertThrows(LeaveUnacked::class.java) { runBlocking { again.handle(early) } }
            assertTrue(savedState(store).seen[early.msgId] == null)
            again.handle(v.msg(res.fin))
            assertTrue(savedState(store).vault!!.awaiting.isEmpty())
            again.handle(early) // the relay's redelivery: same msg_id
            assertEquals("early", VaultJson.str(again.awaitEvent("message.new", java.time.Duration.ofSeconds(2)).body, "text"))
            // The device now answers in the new epoch.
            val status = async(Dispatchers.IO) { again.op("vault.status") }
            val raw = nextDeposit().second
            assertEquals(res.epoch.recvKid, Envelope.parse(raw).recipientKid)
            again.handle(v.seal("vault.status", """{"vault_id":"x"}""", re = v.open(raw).id, status = Inner.STATUS_OK))
            assertEquals("x", VaultJson.str(status.await(), "vault_id"))
        }
    }

    /** A collection that ended starts again with [VaultDevice.ensureCollecting]; never after stop(). */
    @Test
    fun anEndedCollectionStartsAgain() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            refuseCollect = RelayException.SIGNATURE_INVALID
            d.start(scope).join()
            assertEquals(RelayException.SIGNATURE_INVALID, d.collectionEnded.value)
            assertTrue(!d.collecting)
            refuseCollect = null
            serveMailbox = true
            val m = v.seal("message.new", """{"connection_id":"c1","message_id":"m3","direction":"in","text":"after","sent_at":"x"}""")
            queued.add(m)
            assertTrue(d.ensureCollecting())
            assertTrue(d.collecting)
            assertNull(d.collectionEnded.value)
            assertTrue(!d.ensureCollecting()) // already running
            assertEquals("after", VaultJson.str(d.awaitEvent("message.new", java.time.Duration.ofSeconds(10)).body, "text"))
            assertEquals(m.msgId, acked.poll(10, TimeUnit.SECONDS))
            d.stop()
            assertTrue(!d.ensureCollecting())
            serveMailbox = false
            scope.cancel()
        }
    }

    @Test
    fun stateSurvivesARestart() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val secrets = DeviceSecrets.generate()
            val (d, v) = pairedDevice(this, store, secrets)
            d.stop()
            val again = VaultDevice.load(config(store), secrets)!!
            assertEquals("dev-1", again.deviceId)
            assertEquals(v.vaultId, again.vaultId)
            // The restored keyring still talks to the vault.
            val r = async(Dispatchers.IO) { again.op("vault.status") }
            val req = v.open(nextDeposit().second)
            again.handle(v.seal("vault.status", "{}", re = req.id, status = Inner.STATUS_OK))
            r.await()
            assertNull(VaultDevice.load(config(InMemoryDeviceStateStore()), secrets))
        }
    }

    /** Sends one credential operation and returns its opened sealed payload; the operation is then left unanswered. */
    private suspend fun CoroutineScope.sealedPayloadOf(
        d: VaultDevice,
        v: FakeVault,
        utkId: String,
        op: suspend () -> Unit,
    ): com.vettid.core.crypto.json.JsonObject {
        val utk = KemPrivateKey.generate()
        val job = async(Dispatchers.IO) { runCatching { op() } }
        val get = v.open(nextDeposit().second)
        assertEquals("credential.utk.get", get.type)
        val utkJson = """{"utk_id":"$utkId","ek":"${Base64s.encodeStd(utk.publicKey.bytes())}","expires_at":"2099-01-01T00:00:00.000Z"}"""
        d.handle(v.seal(get.type, """{"utks":[$utkJson]}""", re = get.id, status = Inner.STATUS_OK))
        val req = v.open(nextDeposit().second)
        val body = StrictJson.parseObject(req.body)
        val payload = com.vettid.core.crypto.credential.CredentialSeal.openPayload(utk, v.vaultId, utkId, req.type, req.id, body.base64("sealed"))
        // Answered (refused), so that the operation is settled (§11.14.9) and the next one may be sent.
        d.handle(v.error(req.id, req.type, "bad_request"))
        job.await()
        return StrictJson.parseObject(payload)
    }

    /**
     * Release R (VAULT-MESSAGING 0.33.0 §11.14.7, §11.14.9): `credential.create` and `credential.password.change` carry
     * the PIN when given, and passwords are normalized to NFC only for a vault of release R; a vault before it gets
     * every password exactly as typed.
     */
    /**
     * The end-of-keep check (release R): device.list over the session is accepted only as the answer to this request.
     * An earlier answer delivered again (a host replaying it) is not taken for a new request, which then gets no answer.
     */
    @Test
    fun aReplayedAnswerIsNotTakenForANewRequest() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d)
            val first = async(Dispatchers.IO) { api.deviceList() }
            val r1 = v.open(nextDeposit().second)
            assertEquals("device.list", r1.type)
            val answer = v.seal("device.list", """{"devices":[{"id":"dev-1","kind":"app","name":"phone"}]}""", re = r1.id, status = Inner.STATUS_OK)
            d.handle(answer)
            val listed = first.await()["devices"] as kotlinx.serialization.json.JsonArray
            assertEquals("dev-1", ((listed[0] as kotlinx.serialization.json.JsonObject)["id"] as JsonPrimitive).content)
            // A new request, answered only by the old answer again: no answer.
            val second = async(Dispatchers.IO) { runCatching { api.deviceList() } }
            val r2 = v.open(nextDeposit().second)
            assertTrue(r2.id != r1.id)
            runCatching { d.handle(answer) } // dropped however the device treats a duplicate
            val out = second.await()
            assertTrue(out.isFailure)
            assertTrue(out.exceptionOrNull() is VaultStateException)
        }
    }

    /** Release R: a recovery is finished only on a paired phone (after its credential.get), never before. */
    @Test
    fun aRecoveryIsFinishedOnlyOnAPairedPhone() = runBlocking<Unit> {
        val d = VaultDevice.create(config(InMemoryDeviceStateStore()), DeviceSecrets.generate())
        assertFalse(d.recoveryOpenedNotFinished)
        assertThrows(VaultStateException::class.java) { runBlocking { d.finishRecoveryOpen() } }
    }

    @Test
    fun releaseRCredentialPayloadsCarryThePinAndNormalizeOnlyForReleaseR() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val nfd = "Cafe\u0301 au lait now"
            val nfc = "Caf\u00e9 au lait now"
            assertTrue(nfd != nfc)
            // Before release R: as typed, and no PIN.
            val before = VaultApi(d)
            val p0 = sealedPayloadOf(d, v, "00112233445566a1") { before.credentialCreate(nfd) }
            assertEquals(nfd, p0.string("password"))
            assertFalse(p0.has("pin"))
            // Release R: the PIN, and NFC.
            val r = VaultApi(d) { true }
            val p1 = sealedPayloadOf(d, v, "00112233445566a2") { r.credentialCreate(nfd, "975310") }
            assertEquals(nfc, p1.string("password"))
            assertEquals("975310", p1.string("pin"))
            d.keepCredential("QkxPQg==", 3)
            val p2 = sealedPayloadOf(d, v, "00112233445566a3") { before.credentialChangePassword(nfd, nfd + "!") }
            assertEquals(nfd, p2.string("password"))
            assertEquals("$nfd!", p2.string("new_password"))
            assertFalse(p2.has("pin"))
            val p3 = sealedPayloadOf(d, v, "00112233445566a4") { r.credentialChangePassword(nfd, nfd + "!", "975310") }
            assertEquals(nfc, p3.string("password"))
            assertEquals("$nfc!", p3.string("new_password"))
            assertEquals("975310", p3.string("pin"))
        }
    }

    @Test
    fun credentialOperationsSealToUtksAndKeepTheBlob() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d)
            val utk = KemPrivateKey.generate()
            val create = async(Dispatchers.IO) { api.credentialCreate("password one") }
            // The pool is empty: credential.utk.get first.
            val get = v.open(nextDeposit().second)
            assertEquals("credential.utk.get", get.type)
            val utkJson = """{"utk_id":"00112233445566aa","ek":"${Base64s.encodeStd(utk.publicKey.bytes())}","expires_at":"2099-01-01T00:00:00.000Z"}"""
            d.handle(v.seal(get.type, """{"utks":[$utkJson]}""", re = get.id, status = Inner.STATUS_OK))
            val cr = v.open(nextDeposit().second)
            assertEquals("credential.create", cr.type)
            val body = StrictJson.parseObject(cr.body)
            assertEquals("00112233445566aa", body.string("utk_id"))
            val payload = com.vettid.core.crypto.credential.CredentialSeal.openPayload(utk, v.vaultId, "00112233445566aa", cr.type, cr.id, body.base64("sealed"))
            assertEquals("password one", StrictJson.parseObject(payload).string("password"))
            d.handle(v.seal(cr.type, """{"credential":"QkxPQg==","version":1,"key":"k","utks":[]}""", re = cr.id, status = Inner.STATUS_OK))
            val ack = v.open(nextDeposit().second)
            assertEquals("credential.ack", ack.type)
            assertEquals(1, StrictJson.parseObject(ack.body).uint("version", 0, 10))
            d.handle(v.seal(ack.type, "{}", re = ack.id, status = Inner.STATUS_OK))
            create.await()
            assertEquals(1L, d.credentialVersion)
            assertEquals(0, d.utkCount)
        }
    }

    /**
     * The daily owner check (VAULT-MESSAGING 0.13.0 §3.6.1): one `vault.owner_check` with the blob, the PIN, the
     * password and a hold change sealed together to one UTK; the new blob is kept and acked. A request answered
     * `owner_check_required` is signalled (§3.6.3); `vault.status` reports the check and `vault.held` its counts.
     */
    @Test
    fun ownerCheckSealsPinPasswordAndHoldTogether() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d)
            val utk = KemPrivateKey.generate()
            d.keepCredential("QkxPQg==", 3)
            val check = async(Dispatchers.IO) { api.ownerCheck("975310", "password one", hold = false, holdOffUntil = "2026-10-09T12:00:00Z") }
            val get = v.open(nextDeposit().second)
            assertEquals("credential.utk.get", get.type)
            val utkJson = """{"utk_id":"00112233445566bb","ek":"${Base64s.encodeStd(utk.publicKey.bytes())}","expires_at":"2099-01-01T00:00:00.000Z"}"""
            d.handle(v.seal(get.type, """{"utks":[$utkJson]}""", re = get.id, status = Inner.STATUS_OK))
            val oc = v.open(nextDeposit().second)
            assertEquals(VaultApi.TYPE_OWNER_CHECK, oc.type)
            val body = StrictJson.parseObject(oc.body)
            assertEquals("QkxPQg==", body.string("credential"))
            assertEquals("00112233445566bb", body.string("utk_id"))
            val payload = StrictJson.parseObject(
                com.vettid.core.crypto.credential.CredentialSeal.openPayload(utk, v.vaultId, "00112233445566bb", oc.type, oc.id, body.base64("sealed")),
            )
            assertEquals("975310", payload.string("pin"))
            assertEquals("password one", payload.string("password"))
            assertEquals(false, payload.bool("hold"))
            assertEquals("2026-10-09T12:00:00Z", payload.string("hold_off_until"))
            d.handle(
                v.seal(
                    oc.type,
                    """{"credential":"TkVX","version":4,"utks":[],"deadline":"2026-10-07T12:00:00.000Z","interval_seconds":86400,"hold":false,"hold_off_until":"2026-10-09T12:00:00Z"}""",
                    re = oc.id, status = Inner.STATUS_OK,
                ),
            )
            val ack = v.open(nextDeposit().second)
            assertEquals("credential.ack", ack.type)
            d.handle(v.seal(ack.type, "{}", re = ack.id, status = Inner.STATUS_OK))
            val passed = check.await()
            assertEquals("2026-10-07T12:00:00.000Z", passed.deadline)
            assertEquals(false, passed.hold)
            assertEquals(4L, d.credentialVersion)
            assertEquals("TkVX", d.credentialBlob())

            // Held: a refused request is signalled, with its type.
            // Subscribed before the refusal arrives (a SharedFlow without replay).
            val signal = async(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { d.ownerCheckRequired.first() }
            }
            val refused = async(Dispatchers.IO) { runCatching { api.messageSend("c1", "draft") } }
            val ms = v.open(nextDeposit().second)
            d.handle(
                v.msg(
                    v.keyring.current()!!.seal(
                        Inner(id = Ulid.new(), type = ms.type, ts = Instant.now(), re = ms.id, status = Inner.STATUS_ERROR,
                            error = com.vettid.core.crypto.envelope.InnerError("owner_check_required")),
                    ),
                ),
            )
            assertEquals("owner_check_required", (refused.await().exceptionOrNull() as VaultOpException).code)
            assertEquals("message.send", signal.await())

            val status = async(Dispatchers.IO) { api.status() }
            val sr = v.open(nextDeposit().second)
            d.handle(
                v.seal(
                    "vault.status",
                    """{"vault_id":"${v.vaultId}","state_seq":9,"owner_check":{"state":"held","deadline":"2026-10-07T12:00:00.000Z","interval_seconds":3600,"failures":2,"hold":true}}""",
                    re = sr.id, status = Inner.STATUS_OK,
                ),
            )
            val oc2 = status.await().ownerCheck!!
            assertEquals("held", oc2.state)
            assertEquals(2, oc2.failures)
            assertEquals(3600L, oc2.intervalSeconds)
            assertNull(oc2.holdOffUntil)

            val held = async(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { api.held.first() }
            }
            d.handle(v.seal("vault.held", """{"deadline":"2026-10-07T12:00:00.000Z","waiting":{"messages":3,"requests":1,"calls":0,"other":2}}"""))
            assertEquals(HeldCounts(3, 1, 0, 2), held.await().waiting)
        }
    }

    /** A test attester that records the challenge (the dev stack's TEST attester checks it for real). */
    private class RecordingAttester : com.vettid.core.altchan.Attester {
        val pair: java.security.KeyPair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        var challenge: ByteArray? = null

        override fun attest(challenge: ByteArray): com.vettid.core.crypto.altchan.DeviceAttest {
            this.challenge = challenge
            return com.vettid.core.crypto.altchan.DeviceAttest.android(listOf(pair.public.encoded))
        }

        override fun assert(message: ByteArray): com.vettid.core.crypto.altchan.DeviceAssertion = error("not used")
    }

    /** The old app's transfer QR (§6.7.1): a claim with an app bundle on the vault's relay. */
    private fun transferLink(v: FakeVault, inviteId: String, kind: InviteKind = InviteKind.APP, exp: Instant = Instant.now().plusSeconds(600)): String {
        val e = exp.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val tok = DepositTokens.mintOpen(v.relayKey, v.relayUrl, Instant.now(), java.time.Duration.ofMinutes(10))
        val (blob, kb, h) = InviteBundle.seal(InviteBundle(kind, inviteId, false, v.principal(), tok, e).marshal())
        val claimId = "abcdefghijklmnopqrstuvwxy" + "234567"[claims.size % 6]
        claims[claimId] = blob
        return InviteQr(kind, v.relayUrl, claimId, h, kb, e.epochSecond).link()
    }

    /** §6.7.1: the new app scans, sends the attested hs.init, shows the SAS after hs.fin, and is paired at the approval. */
    @Test
    fun directTransferToThisPhone() = runBlocking<Unit> {
        withTimeout(30_000) {
            val v = FakeVault()
            val d = VaultDevice.create(config(InMemoryDeviceStateStore()), DeviceSecrets.generate())
            val inviteId = Ulid.new()
            val att = RecordingAttester()
            d.startTransfer(transferLink(v, inviteId), att)
            val (mbx, init) = nextDeposit()
            assertEquals(v.mailbox, mbx)
            val p = PendingInit.open(init, { k -> if (k == v.kem.publicKey.kid) v.kem else null }, Instant.now())
            assertEquals(Purpose.APP, p.body.purpose)
            assertEquals(inviteId, p.body.ctx)
            assertEquals("phone", StrictJson.parseObject(p.body.profile!!).string("name"))
            // §6.7: the attestation is over the challenge with the hs.init id, an empty vault id and its ts.
            assertNotNull(p.body.deviceAttest)
            val ts = com.vettid.core.crypto.envelope.Timestamps.formatMillis(p.inner.ts)
            org.junit.Assert.assertArrayEquals(com.vettid.core.crypto.altchan.AltChannel.devattChallenge(p.inner.id, "", ts), att.challenge)
            assertNull(d.pairingSas.value)
            val (r, env) = p.respond(ResponderConfig(identity = v.ik, token = v.tokenFor(d), policy = Policy.VAULT_TO_DEVICE, collectSender = d.relayAddr.pk()))
            d.handle(v.msg(env))
            val fr = r.handleFin(nextDeposit().second, d.relayAddr.pk(), Instant.now())
            v.keyring.activate(fr.epoch, Instant.now())
            // Both sides show the same code.
            assertEquals(fr.sas, d.pairingSas.value)
            val paired = async(Dispatchers.IO) { d.awaitTransfer(java.time.Duration.ofSeconds(10)) }
            d.handle(v.seal("device.paired", """{"device_id":"dev-2","role":"app","vault_id":"${v.vaultId}","release":"${"a3".repeat(48)}","release_number":3,"token":"${v.tokenFor(d)}","transfer":true,"credential_version":5,"user_guid":"member-0017"}"""))
            // §6.7.1 (0.17.0): the member's user_guid, which this phone's unlocks need.
            assertEquals("member-0017", paired.await())
            assertEquals("dev-2", d.deviceId)
            assertEquals(v.vaultId, d.vaultId)
            assertTrue(d.paired.value)
            assertEquals(3, d.altState.releaseNumber)
        }
    }

    /** §6.7.1 (0.17.0): only a transfer's device.paired gives a user_guid, and only a well-formed one. */
    @Test
    fun aTransfersUserGuidIsCheckedBeforeItIsKept() {
        fun guid(body: String) = VaultDevice.transferUserGuid(VaultJson.parseObject(body.toByteArray()))
        assertEquals("u1", guid("""{"transfer":true,"user_guid":"u1"}"""))
        assertEquals("a".repeat(128), guid("""{"transfer":true,"user_guid":"${"a".repeat(128)}"}"""))
        // A vault release before 0.17.0 sends none; a plain pairing's is ignored.
        assertNull(guid("""{"transfer":true}"""))
        assertNull(guid("""{"user_guid":"u1"}"""))
        assertNull(guid("""{"transfer":false,"user_guid":"u1"}"""))
        assertNull(guid("""{"transfer":"true","user_guid":"u1"}"""))
        // It must fit the unlock's signing string (§11.4): 1–128 printable ASCII, no spaces or line breaks.
        assertNull(guid("""{"transfer":true,"user_guid":""}"""))
        assertNull(guid("""{"transfer":true,"user_guid":"${"a".repeat(129)}"}"""))
        assertNull(guid("""{"transfer":true,"user_guid":"a b"}"""))
        assertNull(guid("""{"transfer":true,"user_guid":"a\nb"}"""))
        assertNull(guid("""{"transfer":true,"user_guid":"é"}"""))
        assertNull(guid("""{"transfer":true,"user_guid":7}"""))
    }

    /** 0.10.5: device.pair.rejected ends the new phone's wait; it can scan a new code afterwards. */
    @Test
    fun aRejectedTransferEndsTheWait() = runBlocking<Unit> {
        withTimeout(30_000) {
            val v = FakeVault()
            val d = VaultDevice.create(config(InMemoryDeviceStateStore()), DeviceSecrets.generate())
            d.startTransfer(transferLink(v, Ulid.new()), RecordingAttester())
            val p = PendingInit.open(nextDeposit().second, { k -> if (k == v.kem.publicKey.kid) v.kem else null }, Instant.now())
            val (r, env) = p.respond(ResponderConfig(identity = v.ik, token = v.tokenFor(d), policy = Policy.VAULT_TO_DEVICE, collectSender = d.relayAddr.pk()))
            d.handle(v.msg(env))
            v.keyring.activate(r.handleFin(nextDeposit().second, d.relayAddr.pk(), Instant.now()).epoch, Instant.now())
            val waiting = async(Dispatchers.IO) { runCatching { d.awaitTransfer(java.time.Duration.ofSeconds(10)) } }
            d.handle(v.seal("device.pair.rejected", "{}"))
            assertTrue(waiting.await().exceptionOrNull() is PairingRejectedException)
            assertNull(d.pairingSas.value)
            assertNull(d.deviceId)
            d.abandonTransfer()
            assertTrue(!d.hasVault)
            // A new code works.
            d.startTransfer(transferLink(v, Ulid.new()), RecordingAttester())
            assertEquals(v.mailbox, nextDeposit().first)
        }
    }

    @Test
    fun onlyAnUnexpiredAppTransferCodeIsUsed() = runBlocking<Unit> {
        val v = FakeVault()
        val d = VaultDevice.create(config(InMemoryDeviceStateStore()), DeviceSecrets.generate())
        assertThrows(NotATransferCodeException::class.java) { runBlocking { d.startTransfer("not a link", RecordingAttester()) } }
        val desktop = transferLink(v, Ulid.new(), InviteKind.DESKTOP)
        assertThrows(NotATransferCodeException::class.java) { runBlocking { d.startTransfer(desktop, RecordingAttester()) } }
        val expired = transferLink(v, Ulid.new(), exp = Instant.now().minusSeconds(5))
        val e = assertThrows(NotATransferCodeException::class.java) { runBlocking { d.startTransfer(expired, RecordingAttester()) } }
        assertTrue(e.expired)
        assertNull(deposits.poll(200, TimeUnit.MILLISECONDS))
    }

    /** Fills the device's credential (one blob, an empty UTK pool), as [credentialOperationsSealToUtksAndKeepTheBlob]. */
    private suspend fun CoroutineScope.withCredential(d: VaultDevice, v: FakeVault, api: VaultApi) {
        val utk = KemPrivateKey.generate()
        val create = async(Dispatchers.IO) { api.credentialCreate("password one") }
        val get = v.open(nextDeposit().second)
        val utkJson = """{"utk_id":"00112233445566aa","ek":"${Base64s.encodeStd(utk.publicKey.bytes())}","expires_at":"2099-01-01T00:00:00.000Z"}"""
        d.handle(v.seal(get.type, """{"utks":[$utkJson]}""", re = get.id, status = Inner.STATUS_OK))
        val cr = v.open(nextDeposit().second)
        d.handle(v.seal(cr.type, """{"credential":"QkxPQg==","version":1,"key":"k","utks":[]}""", re = cr.id, status = Inner.STATUS_OK))
        val ack = v.open(nextDeposit().second)
        d.handle(v.seal(ack.type, "{}", re = ack.id, status = Inner.STATUS_OK))
        create.await()
    }

    /**
     * VAULT-MESSAGING 0.27.0 §6.7.1 step 3, §11.7.1: `device.transfer.pending` brings the new app's signer class and
     * `signer_changed`; an approval without `signer` is answered `signer_unconfirmed` before the UTK is spent, so the
     * retry is sealed to the same UTK and names the class.
     */
    @Test
    fun aTransferApprovalNamesTheSignerAndAnUnconfirmedOneKeepsItsUtk() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d)
            withCredential(d, v, api)
            val pending = async(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                api.awaitTransferPending("t-1", java.time.Duration.ofSeconds(5))
            }
            d.handle(v.seal("device.transfer.pending", """{"transfer_id":"t-1","name":"Pixel 10","sas":"123456","signer":"google_play","signer_changed":true}"""))
            assertEquals(TransferPending("t-1", "Pixel 10", "123456", "google_play", true), pending.await())

            // An approval as a build from before 0.27.0 sends it: no `signer`.
            val utk = KemPrivateKey.generate()
            val first = async(Dispatchers.IO) { runCatching { api.transferApprove("t-1", "246802", "password one") } }
            val get = v.open(nextDeposit().second)
            assertEquals("credential.utk.get", get.type)
            val utkJson = """{"utk_id":"00112233445566cc","ek":"${Base64s.encodeStd(utk.publicKey.bytes())}","expires_at":"2099-01-01T00:00:00.000Z"}"""
            d.handle(v.seal(get.type, """{"utks":[$utkJson]}""", re = get.id, status = Inner.STATUS_OK))
            val a1 = v.open(nextDeposit().second)
            assertEquals("device.transfer.approve", a1.type)
            val b1 = StrictJson.parseObject(a1.body)
            assertEquals("t-1", b1.string("transfer_id"))
            assertEquals("00112233445566cc", b1.string("utk_id"))
            assertTrue("signer" !in b1.members)
            d.handle(
                v.msg(
                    v.keyring.current()!!.seal(
                        Inner(id = Ulid.new(), type = a1.type, ts = Instant.now(), re = a1.id, status = Inner.STATUS_ERROR,
                            error = com.vettid.core.crypto.envelope.InnerError("signer_unconfirmed")),
                    ),
                ),
            )
            val e = first.await().exceptionOrNull() as VaultOpException
            assertEquals("signer_unconfirmed", e.code)
            // The vault did not spend the UTK: it is back in the pool, first.
            assertEquals(1, d.utkCount)

            // The retry, with the member's confirmation: the same UTK (no credential.utk.get), and the class.
            val second = async(Dispatchers.IO) { api.transferApprove("t-1", "246802", "password one", signer = "google_play") }
            val a2 = v.open(nextDeposit().second)
            assertEquals("device.transfer.approve", a2.type)
            val b2 = StrictJson.parseObject(a2.body)
            assertEquals("00112233445566cc", b2.string("utk_id"))
            assertEquals("google_play", b2.string("signer"))
            assertEquals("t-1", b2.string("transfer_id"))
            val payload = StrictJson.parseObject(
                com.vettid.core.crypto.credential.CredentialSeal.openPayload(utk, v.vaultId, "00112233445566cc", a2.type, a2.id, b2.base64("sealed")),
            )
            assertEquals("246802", payload.string("pin"))
            assertEquals("password one", payload.string("password"))
            assertTrue("signer" !in payload.members) // an outer member, as transfer_id
            d.handle(v.seal(a2.type, "{}", re = a2.id, status = Inner.STATUS_OK))
            second.await()
            assertEquals(0, d.utkCount)
        }
    }

    /** A pending from a vault before 0.27.0, or for an app without a signer, has neither member. */
    @Test
    fun aTransferPendingWithoutSignerMembersIsUnchanged() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d)
            for ((body, expected) in listOf(
                """{"transfer_id":"t-2","name":"Old","sas":"654321"}""" to TransferPending("t-2", "Old", "654321", null, false),
                """{"transfer_id":"t-2","name":"Old","sas":"654321","signer":"vettid","signer_changed":false}""" to
                    TransferPending("t-2", "Old", "654321", "vettid", false),
                // Not a boolean: not a confirmation the app may skip silently, but nothing it can act on either.
                """{"transfer_id":"t-2","name":"Old","sas":"654321","signer":"vettid","signer_changed":"true"}""" to
                    TransferPending("t-2", "Old", "654321", "vettid", false),
            )) {
                val pending = async(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    api.awaitTransferPending("t-2", java.time.Duration.ofSeconds(5))
                }
                d.handle(v.seal("device.transfer.pending", body))
                assertEquals(expected, pending.await())
            }
        }
    }

    /** A test attester with a "Keystore" chain: [attest] makes a new one, [chain] returns it (§11.7.1). */
    private class ChainAttester : com.vettid.core.altchan.Attester {
        private val pair: java.security.KeyPair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        var kept: List<ByteArray>? = null
        var generation = 0

        override fun attest(challenge: ByteArray): com.vettid.core.crypto.altchan.DeviceAttest {
            generation++
            kept = listOf(ByteArray(600) { generation.toByte() }, ByteArray(500) { 0x7f })
            return com.vettid.core.crypto.altchan.DeviceAttest.android(kept!!)
        }

        override fun assert(message: ByteArray): com.vettid.core.crypto.altchan.DeviceAssertion =
            com.vettid.core.crypto.altchan.DeviceAssertion.android(
                java.security.Signature.getInstance("SHA256withECDSA").run {
                    initSign(pair.private)
                    update(message)
                    sign()
                },
            )

        override fun chain(): List<ByteArray>? = kept
    }

    /**
     * VAULT-MESSAGING 0.27.0 §11.7.1: every unlock carries the device key's chain while the vault has not shown this
     * app a signer in `device.list`; it stops once it has (kept across a restart), and starts again after a new
     * attestation (a new key) and after an unlock answered `signer_refused`.
     */
    @Test
    fun unlocksCarryTheChainUntilTheVaultShowsASigner() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val secrets = DeviceSecrets.generate()
            val (d, _) = pairedDevice(this, store, secrets)
            val pcr0 = "a3".repeat(48)
            val etk = KemPrivateKey.generate()
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val entry = JsonBuilder().uint("release", 3).string("pcr0", pcr0).string("pcr1", "13".repeat(48)).string("pcr2", "23".repeat(48))
                .string("seal_key", "arn:aws:kms:us-east-1:111122223333:key/test").string("status", "active")
                .string("published_at", "2026-10-01T00:00:00Z").string("notes", "https://vettid.org/releases/3").build()
            val doc = JsonBuilder().uint("v", 1).uint("serial", 7).string("issued_at", "2026-10-01T00:00:00Z").raw("releases", "[$entry]").bytes()
            val der = java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(com.vettid.core.attestation.manifest.ReleaseManifest.LABEL.toByteArray())
                update(0)
                update(doc)
                sign()
            }
            val raw = org.bouncycastle.asn1.ASN1Sequence.getInstance(der).let { seq ->
                seq.map { org.bouncycastle.util.BigIntegers.asUnsignedByteArray(32, (it as org.bouncycastle.asn1.ASN1Integer).value) }.reduce { a, b -> a + b }
            }
            val key = com.vettid.core.attestation.manifest.ManifestKey(pair.public.encoded)
            val served = JsonBuilder().string("manifest", Base64s.encodeStd(doc)).base64("sig", raw).string("key_id", key.keyId).bytes()
            val manifest = com.vettid.core.attestation.manifest.ManifestVerifier(listOf(key)).verify(served)
            val enclave = com.vettid.core.attestation.VerifiedEnclave(
                com.vettid.core.crypto.altchan.Descriptor.parse(
                    com.vettid.core.crypto.altchan.Descriptor.marshal("inst-a", etk.publicKey, pcr0, Instant.now().plusSeconds(3600)),
                ),
                com.vettid.core.attestation.nitro.Measurements(pcr0, "13".repeat(48), "23".repeat(48)),
                manifest.byPcr0(pcr0)!!,
            )
            val att = ChainAttester().apply { attest(ByteArray(32)) }

            fun chainOf(dev: VaultDevice, b: com.vettid.core.altchan.BuiltUnlock): List<String>? {
                assertNotNull(dev)
                val (padded, _) = Envelope.openSealed(Envelope.parse(b.request.envelope()), etk)
                val inner = Inner.parse(com.vettid.core.crypto.envelope.Padding.unpadFixed(padded, com.vettid.core.crypto.envelope.Padding.ALT_CHANNEL_REQUEST), Mode.SEALED)
                val a = StrictJson.parseObject(inner.body).obj("device_assertion")
                return if (a.members.containsKey("chain")) a.array("chain").map { (it as com.vettid.core.crypto.json.JsonString).value } else null
            }
            val options = com.vettid.core.altchan.UnlockOptions()
            val sent = att.kept!!.map { Base64s.encodeStd(it) }

            // A vault enrolled before 0.27.0 (or whose device.list this app has not read): the chain, every time.
            assertTrue(!d.signerSeen)
            assertEquals(sent, chainOf(d, d.prepareUnlock("guid-1", "246802", enclave, manifest, att, options)))
            assertEquals(sent, chainOf(d, d.prepareUnlock("guid-1", "246802", enclave, manifest, att, options)))
            // device.list showed no signer: nothing changes.
            d.markSignerSeen(false)
            assertEquals(sent, chainOf(d, d.prepareUnlock("guid-1", "246802", enclave, manifest, att, options)))
            // device.list showed the signer: no chain any more, also after a restart.
            d.markSignerSeen(true)
            assertNull(chainOf(d, d.prepareUnlock("guid-1", "246802", enclave, manifest, att, options)))
            d.stop()
            val again = VaultDevice.load(config(store), secrets)!!
            assertTrue(again.signerSeen)
            assertNull(chainOf(again, again.prepareUnlock("guid-1", "246802", enclave, manifest, att, options)))
            // The entry lost its signer (a vault restored to an older state): the chain again.
            again.markSignerSeen(false)
            assertEquals(sent, chainOf(again, again.prepareUnlock("guid-1", "246802", enclave, manifest, att, options)))
            // An unlock answered signer_refused (a host may have served a header from before the binding): the chain
            // goes out again although device.list had shown a signer, also after a restart; a wrong PIN changes nothing.
            fun result(b: com.vettid.core.altchan.BuiltUnlock, body: String): ByteArray {
                val inner = Inner(id = Ulid.new(), type = "vault.unlock.result", ts = Instant.now(), re = b.request.requestId, status = Inner.STATUS_OK, body = body.toByteArray())
                val padded = com.vettid.core.crypto.envelope.Padding.padFixed(inner.marshal(Mode.SEALED), com.vettid.core.crypto.envelope.Padding.ALT_CHANNEL)
                return Envelope.sealSealed(again.kemKey, Kid.ANONYMOUS, padded).first
            }
            again.markSignerSeen(true)
            val wrongPin = again.prepareUnlock("guid-1", "111111", enclave, manifest, att, options)
            assertNull(chainOf(again, wrongPin))
            assertEquals("bad_pin", again.openUnlockResult(result(wrongPin, """{"ok":false,"code":"bad_pin","header_seq":5,"retry_after":30}""")).code)
            assertTrue(again.signerSeen)
            val refusedUnlock = again.prepareUnlock("guid-1", "246802", enclave, manifest, att, options)
            assertNull(chainOf(again, refusedUnlock))
            // As the vault writes it (retry_after 0), and as the spec shows it (no retry_after).
            val refused = again.openUnlockResult(result(refusedUnlock, """{"ok":false,"code":"signer_refused","header_seq":6,"retry_after":0}"""))
            assertEquals("signer_refused", refused.code)
            assertEquals(0L, refused.retryAfterSeconds)
            assertTrue(!again.signerSeen)
            assertEquals(sent, chainOf(again, again.prepareUnlock("guid-1", "246802", enclave, manifest, att, options)))
            val third = VaultDevice.load(config(store), secrets)!!
            assertTrue(!third.signerSeen)
            val retry = third.prepareUnlock("guid-1", "246802", enclave, manifest, att, options)
            assertEquals(sent, chainOf(third, retry))
            assertEquals("signer_refused", third.openUnlockResult(result(retry, """{"ok":false,"code":"signer_refused","header_seq":6}""")).code)
            assertTrue(!third.signerSeen)
            // A new attestation is a new key: whatever was seen before no longer counts, and the new chain goes out.
            again.markSignerSeen(true)
            again.prepareEnroll("guid-1", "246802", enclave, manifest, att)
            assertTrue(!again.signerSeen)
            assertEquals(2, att.generation)
            assertEquals(att.kept!!.map { Base64s.encodeStd(it) }, chainOf(again, again.prepareUnlock("guid-1", "246802", enclave, manifest, att, options)))
        }
    }

    // --- calls (§10.10; CALLS-PLAN C1) ---

    /** The other member of a call: a device, its vault, and the bodies they would produce. */
    private class CallPeer {
        val device = Ed25519PrivateKey.generate()
        val vault = Ed25519PrivateKey.generate()

        /** The connection's identity as the app shows it (§10.8): what a call is checked against. */
        val pinned: String = com.vettid.core.crypto.IkFingerprint.format(vault.publicKey)

        /** The vouched signatures of a share, with `peer_ik` as this app's own vault adds it. */
        fun vouched(b: JsonBuilder, m: ByteArray, voucher: Ed25519PrivateKey = vault, peerIk: ByteArray = vault.publicKey): JsonBuilder =
            b.base64("device_ik", device.publicKey).base64("device_sig", CallWire.signShare(device, m))
                .base64("vault_sig", voucher.sign(CallWire.LABEL_VOUCH, CallWire.vouchMessage(device.publicKey, m))).base64("peer_ik", peerIk)
    }

    private val sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n"

    private fun FakeVault.ice(b: JsonBuilder, callId: String, exp: Instant = Instant.now().plusSeconds(21_600)): JsonBuilder {
        val cfg = CallWire.marshalIce(CallWire.IceConfig(callId, Instant.ofEpochSecond(exp.epochSecond), emptyList()))
        return b.base64("ice_config", cfg).base64("ice_sig", ik.sign(CallWire.LABEL_ICE, cfg))
    }

    private fun ts(t: Instant) = com.vettid.core.crypto.envelope.Timestamps.formatMillis(t)

    /** An outgoing call: `call.start` is deposited once with a signed share, the answer is verified, and both devices hold the same two keys. */
    @Test
    fun placingACallAndTakingTheAnswer() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val secrets = DeviceSecrets.generate()
            val (d, v) = pairedDevice(this, store, secrets)
            val api = VaultApi(d)
            val peer = CallPeer()

            val starting = async(Dispatchers.IO) { api.callStart("conn-1", sdp) }
            val req = v.open(nextDeposit().second)
            assertEquals("call.start", req.type)
            assertNull(req.exp)
            assertEquals("never in the outbox", 0, savedState(store).outbox.size)
            val b = StrictJson.parseObject(req.body)
            val callId = b.string("call_id")
            assertTrue(Ulid.isValid(callId))
            assertEquals("conn-1", b.string("connection_id"))
            assertEquals("audio", b.string("media"))
            assertEquals(listOf(1), CallMedia.offeredIn(b.string("sdp")))
            val ek = b.base64("ek", 1216)
            assertTrue(com.vettid.core.crypto.Ed25519.verify(secrets.identity.publicKey, CallWire.LABEL_SHARE, CallWire.shareMessage("offer", callId, "audio", ek), b.base64("ek_sig", 64)))
            val exp = Instant.now().plusSeconds(45).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            d.handle(v.seal("call.start", v.ice(JsonBuilder().string("call_id", callId).string("exp", ts(exp)), callId).build(), re = req.id, status = Inner.STATUS_OK))
            val call = starting.await()
            assertEquals(callId, call.callId)
            assertEquals(exp, call.exp)
            assertTrue(call.ice.servers.isEmpty())

            // The callee's device answers: enc, its choice of suite in the SDP, the vouched share.
            val a = CallWire.answer(ek, callId)
            val expected = CallMedia.Negotiated(MediaSuite.AES_128_GCM, listOf(1))
            val fromCaller = CallMedia.directionKey(a.kCall, expected, CallMedia.DIRECTION_CALLER, callId)
            val fromCallee = CallMedia.directionKey(a.kCall, expected, CallMedia.DIRECTION_CALLEE, callId)
            val m = CallWire.shareMessage("answer", callId, "", a.enc)
            val other = CallPeer()
            fun answer(
                sdpAnswer: String = CallMedia.sdpWithChoice(sdp, MediaSuite.AES_128_GCM),
                enc: ByteArray = a.enc,
                voucher: Ed25519PrivateKey = peer.vault,
                peerIk: ByteArray = peer.vault.publicKey,
            ) = peer.vouched(JsonBuilder().string("call_id", callId).string("sdp", sdpAnswer).base64("enc", enc), m, voucher, peerIk).build()

            // Review F8: the device's signature is good, but the vouch is another vault's; or the whole answer is
            // another vault's, consistent in itself, with its own `peer_ik`: not the connection the member called.
            for (bad in listOf(answer(voucher = other.vault), answer(voucher = other.vault, peerIk = other.vault.publicKey))) {
                d.handle(v.seal("call.answer", bad))
                val ev = d.awaitEvent("call.answer")
                assertThrows(CallMessageException::class.java) { api.callAccept(call, ev, peer.pinned) }
            }
            // And the right answer checked against another connection's identity, or against none.
            d.handle(v.seal("call.answer", answer()))
            val right = d.awaitEvent("call.answer")
            assertThrows(CallMessageException::class.java) { api.callAccept(call, right, other.pinned) }
            assertThrows(CallMessageException::class.java) { api.callAccept(call, right, "") }

            // A share that was swapped on the way, an answer without the suite, a suite that was not offered: unusable.
            // And an answer with a second media section: its audio would play outside the frame encryption (review F1).
            val twoSections = CallMedia.sdpWithChoice(sdp + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n", MediaSuite.AES_128_GCM)
            for (bad in listOf(answer(enc = CallWire.answer(ek, callId).enc), answer(sdpAnswer = sdp), answer(sdpAnswer = CallMedia.sdpWithChoice(sdp, MediaSuite.AES_256_GCM)), answer(sdpAnswer = twoSections))) {
                d.handle(v.seal("call.answer", bad))
                val ev = d.awaitEvent("call.answer")
                assertThrows(CallMessageException::class.java) { api.callAccept(call, ev, peer.pinned) }
            }
            d.handle(v.seal("call.answer", answer()))
            val accepted = api.callAccept(call, d.awaitEvent("call.answer"), peer.pinned)
            assertEquals("the media stack gets the SDP without VettID's attribute", sdp, accepted.sdp)
            assertEquals(MediaSuite.AES_128_GCM, accepted.keys.suite)
            assertTrue(accepted.keys.send().contentEquals(fromCaller))
            assertTrue(accepted.keys.receive().contentEquals(fromCallee))
            assertTrue("no key in a log line", !accepted.keys.toString().contains(com.vettid.core.crypto.Bytes.hex(fromCaller)))
            accepted.keys.destroy()
            assertThrows(IllegalStateException::class.java) { accepted.keys.send() }
            // The ephemeral key is gone with the answer.
            assertThrows(com.vettid.core.crypto.CryptoException::class.java) { call.key().seed() }

            // A refusal is the vault's code.
            val refused = async(Dispatchers.IO) { runCatching { api.callStart("conn-1", sdp) } }
            val r2 = v.open(nextDeposit().second)
            d.handle(v.msg(v.keyring.current()!!.seal(Inner(id = Ulid.new(), type = r2.type, ts = Instant.now(), re = r2.id, status = Inner.STATUS_ERROR, error = com.vettid.core.crypto.envelope.InnerError("busy")))))
            assertEquals("busy", (refused.await().exceptionOrNull() as VaultOpException).code)

            // An ICE configuration signed by anyone but this device's vault, or for another call, is refused.
            assertNull("the vault refused: there is no call to end", deposits.poll(300, TimeUnit.MILLISECONDS))
            val forged = async(Dispatchers.IO) { runCatching { api.callStart("conn-1", sdp) } }
            val r3 = v.open(nextDeposit().second)
            val id3 = StrictJson.parseObject(r3.body).string("call_id")
            val cfg = CallWire.marshalIce(CallWire.IceConfig(id3, Instant.ofEpochSecond(Instant.now().epochSecond + 600), listOf(CallWire.IceServer(listOf("turn:evil.example:3478"), "u", "p"))))
            d.handle(v.seal("call.start", JsonBuilder().string("call_id", id3).string("exp", ts(exp)).base64("ice_config", cfg).base64("ice_sig", peer.vault.sign(CallWire.LABEL_ICE, cfg)).build(), re = r3.id, status = Inner.STATUS_OK))
            assertTrue(forged.await().exceptionOrNull() is CallMessageException)
            // The vault did place that call: it is rung off, or the other phone would ring for 45 s for nobody (review F3).
            val rungOff = v.open(nextDeposit().second)
            assertEquals("call.end", rungOff.type)
            assertEquals("""{"call_id":"$id3","reason":"hangup"}""", String(rungOff.body))

            // The device's own SDP is held to the same shape.
            assertTrue(runCatching { api.callStart("conn-1", sdp + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n") }.exceptionOrNull() is CallMessageException)
            assertNull("nothing left the phone", deposits.poll(300, TimeUnit.MILLISECONDS))
        }
    }

    /** An incoming call: the offer is checked before it may ring; the answer carries a signed share and the keys match the caller's. */
    @Suppress("LongMethod")
    @Test
    fun takingAnOfferAndAnsweringIt() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val secrets = DeviceSecrets.generate()
            val (d, v) = pairedDevice(this, store, secrets)
            val api = VaultApi(d)
            val peer = CallPeer()
            val callId = Ulid.new()
            val offerKey = CallWire.newOfferKey()
            val ek = offerKey.publicKey.bytes()
            val m = CallWire.shareMessage("offer", callId, "audio", ek)
            val exp = Instant.now().plusSeconds(45).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            fun offer(
                offerSdp: String = CallMedia.sdpWithOffer(sdp, listOf(1)),
                share: ByteArray = ek,
                at: Instant = exp,
                iceExp: Instant = Instant.now().plusSeconds(21_600),
                signed: ByteArray = m,
                voucher: Ed25519PrivateKey = peer.vault,
                peerIk: ByteArray = peer.vault.publicKey,
            ) = peer.vouched(
                v.ice(JsonBuilder().string("call_id", callId).string("connection_id", "conn-1").string("media", "audio").string("sdp", offerSdp).base64("ek", share).string("exp", ts(at)), callId, iceExp),
                signed,
                voucher,
                peerIk,
            ).build()
            val other = CallPeer()

            val bad = listOf(
                offer(share = CallWire.newOfferKey().publicKey.bytes()), // a swapped key
                offer(signed = CallWire.shareMessage("offer", callId, "video", ek)), // signed for another media
                offer(voucher = other.vault), // review F8: a good device signature, another vault's vouch
                offer(voucher = other.vault, peerIk = other.vault.publicKey), // another vault's offer, consistent in itself
                offer(offerSdp = CallMedia.sdpWithOffer(sdp, listOf(2))), // no suite this device can do: refused before it rings
                offer(offerSdp = sdp), // no media suites
                offer(offerSdp = CallMedia.sdpWithOffer(sdp + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n", listOf(1))), // a second audio section (F1)
                offer(offerSdp = CallMedia.sdpWithOffer(sdp + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n", listOf(1))), // a data channel
                offer(offerSdp = CallMedia.sdpWithOffer(sdp.replace("opus/48000/2", "PCMU/8000"), listOf(1))), // not Opus
                offer(at = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)), // expired
                offer(at = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)), // too far ahead
                offer(iceExp = Instant.now().minusSeconds(5)), // an expired ICE configuration
            )
            for (b in bad) {
                d.handle(v.seal("call.offer", b))
                val ev = d.awaitEvent("call.offer")
                assertThrows(b.take(60), CallMessageException::class.java) { api.callIncoming(ev, peer.pinned) }
            }
            // A good offer checked against another connection's identity, or against none, does not ring either.
            d.handle(v.seal("call.offer", offer()))
            val good = d.awaitEvent("call.offer")
            assertThrows(CallMessageException::class.java) { api.callIncoming(good, other.pinned) }
            assertThrows(CallMessageException::class.java) { api.callIncoming(good, "") }
            // A device that can do suites 2 and 1 answers a `2 1` offer with 2, fixed before it rings.
            d.handle(v.seal("call.offer", offer(offerSdp = CallMedia.sdpWithOffer(sdp, listOf(2, 1)))))
            val two = api.callIncoming(d.awaitEvent("call.offer"), peer.pinned, MediaSuite.entries)
            assertEquals(CallMedia.Negotiated(MediaSuite.AES_256_GCM, listOf(2, 1)), two.negotiated)

            d.handle(v.seal("call.offer", offer()))
            val call = api.callIncoming(d.awaitEvent("call.offer"), peer.pinned)
            assertTrue("rings for the offer's lifetime by the vault's clock", call.ringFor.seconds in 44..45)
            assertEquals(callId, call.callId)
            assertEquals("conn-1", call.connectionId)
            assertEquals(exp, call.exp)
            assertEquals(sdp, call.sdp)
            assertEquals(listOf(1), call.offered)

            api.callRinging(callId)
            val ringing = v.open(nextDeposit().second)
            assertEquals("call.ringing", ringing.type)
            assertTrue("ephemeral", ringing.exp!!.isAfter(Instant.now()) && ringing.exp!!.isBefore(Instant.now().plusSeconds(31)))

            val keys = api.callAnswer(call, sdp)
            val ans = v.open(nextDeposit().second)
            assertEquals("call.answer", ans.type)
            assertEquals(0, savedState(store).outbox.size)
            val b = StrictJson.parseObject(ans.body)
            assertEquals(callId, b.string("call_id"))
            val enc = b.base64("enc", 1120)
            assertTrue(com.vettid.core.crypto.Ed25519.verify(secrets.identity.publicKey, CallWire.LABEL_SHARE, CallWire.shareMessage("answer", callId, "", enc), b.base64("enc_sig", 64)))
            val n = CallMedia.chosenIn(b.string("sdp"), listOf(1))
            val kCall = CallWire.accept(offerKey, enc, callId)
            assertTrue(keys.send().contentEquals(CallMedia.directionKey(kCall, n, CallMedia.DIRECTION_CALLEE, callId)))
            assertTrue(keys.receive().contentEquals(CallMedia.directionKey(kCall, n, CallMedia.DIRECTION_CALLER, callId)))

            // Candidates: batched by the caller of callIce, at most 16 a message, ephemeral.
            api.callIce(callId, listOf(CallCandidate("candidate:1 1 udp 2122260223 192.0.2.1 50000 typ host", "0", 0), CallCandidate("candidate:2 1 udp 1 192.0.2.2 50001 typ host")))
            val ice = v.open(nextDeposit().second)
            assertEquals("call.ice", ice.type)
            assertNotNull(ice.exp)
            assertEquals("""{"call_id":"$callId","candidates":[{"candidate":"candidate:1 1 udp 2122260223 192.0.2.1 50000 typ host","sdp_mid":"0","sdp_mline_index":0},{"candidate":"candidate:2 1 udp 1 192.0.2.2 50001 typ host"}]}""", String(ice.body))
            assertThrows(CallMessageException::class.java) { runBlocking { api.callIce(callId, emptyList()) } }
            assertThrows(CallMessageException::class.java) { runBlocking { api.callIce(callId, List(17) { CallCandidate("c") }) } }
            assertThrows(CallMessageException::class.java) { runBlocking { api.callIce(callId, listOf(CallCandidate("c".repeat(1025)))) } }

            // The other side's candidates and the end of the call, as events.
            val got = async { api.callEvents.take(3).toList() }
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(100)
            d.handle(v.seal("call.ice", """{"call_id":"$callId","candidates":[{"candidate":"candidate:9","sdp_mid":"0","sdp_mline_index":0}]}"""))
            d.handle(v.seal("message.new", """{"connection_id":"c1","message_id":"m9","direction":"in","text":"not a call","sent_at":"x"}"""))
            d.handle(v.seal("call.ringing", """{"call_id":"$callId"}"""))
            d.handle(v.seal("call.end", """{"call_id":"$callId","reason":"hangup"}"""))
            // One flow, in the vault's order.
            assertEquals(
                listOf(CallEvent.Ice(CallIce(callId, listOf(CallCandidate("candidate:9", "0", 0)))), CallEvent.Ringing(callId), CallEvent.Ended(callId, "hangup")),
                got.await(),
            )
        }
    }

    /**
     * Call signalling is deposited once: a relay failure reaches the caller and nothing is kept for a later flush
     * (a `call.start` sent again after a restart would ring for a call nobody is in). `call.end` is durable, so a
     * hang-up still frees the vault. A `call.start` without an answer is deposited once, not three times.
     */
    @Test
    fun callSignallingIsNeverSentAgainButAHangUpIs() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val (d, v) = pairedDevice(this, store)
            val api = VaultApi(d)
            val callId = Ulid.new()
            refuseStatus = 503
            refuseDeposits = "unavailable"
            assertTrue(runCatching { api.callRinging(callId) }.exceptionOrNull() is RelayException)
            assertTrue(runCatching { api.callStart("conn-1", sdp) }.exceptionOrNull() is RelayException)
            // The relay's answers were lost, as far as the app knows: `call.start` is not kept, but a hang-up for
            // its call is (review F3).
            assertEquals(listOf("call.end"), savedState(store).outbox.map { it.type })
            assertTrue(runCatching { api.callEnd(callId, CallReason.HANGUP) }.exceptionOrNull() is RelayException)
            assertEquals(2, savedState(store).outbox.size)
            refuseDeposits = null
            d.flushOutbox()
            val ends = listOf(v.open(nextDeposit().second), v.open(nextDeposit().second))
            assertTrue(ends.all { it.type == "call.end" && it.exp == null })
            assertEquals("""{"call_id":"$callId","reason":"hangup"}""", String(ends[1].body))
            assertNull("only the hang-ups were sent again", deposits.poll(300, TimeUnit.MILLISECONDS))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.callEnd(callId, "bored") } }

            // No answer: one deposit.
            val e = runCatching { d.requestOnce("call.start", timeout = java.time.Duration.ofMillis(300)) }.exceptionOrNull()
            assertTrue(e is VaultStateException)
            assertEquals("call.start", v.open(nextDeposit().second).type)
            assertNull(deposits.poll(300, TimeUnit.MILLISECONDS))

            // `callStart` without an answer: the call may exist in the vault, so its hang-up follows (review F3).
            val unanswered = runCatching { api.callStart("conn-1", sdp, timeout = java.time.Duration.ofMillis(300)) }.exceptionOrNull()
            assertTrue(unanswered is VaultStateException)
            val started = v.open(nextDeposit().second)
            assertEquals("call.start", started.type)
            val after = v.open(nextDeposit().second)
            assertEquals("call.end", after.type)
            assertEquals("""{"call_id":"${StrictJson.parseObject(started.body).string("call_id")}","reason":"hangup"}""", String(after.body))

            // call.list, and the answer of a vault release without calls.
            val list = async(Dispatchers.IO) { api.callList(10) }
            val req = v.open(nextDeposit().second)
            assertEquals("""{"limit":10}""", String(req.body))
            d.handle(v.seal("call.list", """{"calls":[{"call_id":"$callId","connection_id":"conn-1","direction":"in","media":"audio","state":"ended","reason":"timeout","started_at":"2026-10-12T10:00:00.000Z","ended_at":"2026-10-12T10:00:45.000Z"}]}""", re = req.id, status = Inner.STATUS_OK))
            assertEquals(listOf(CallRecord(callId, "conn-1", "in", "audio", "ended", "timeout", "2026-10-12T10:00:00.000Z", null, "2026-10-12T10:00:45.000Z")), list.await())
        }
    }

    /**
     * A lock must never land late (the member may have opened the vault again): it is deposited once, with an
     * `exp`, never from the outbox; and one that an earlier version of the app left in the outbox is dropped.
     */
    @Test
    fun aLockIsSentOnceWithAnExpiryAndNeverFromTheOutbox() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val (d, v) = pairedDevice(this, store)
            val api = VaultApi(d)
            val locking = async(Dispatchers.IO) { runCatching { api.lock(timeout = java.time.Duration.ofSeconds(5)) } }
            val req = v.open(nextDeposit().second)
            assertEquals("vault.lock", req.type)
            val exp = req.exp!!
            assertTrue(exp.isAfter(Instant.now().plusSeconds(20)) && exp.isBefore(Instant.now().plusSeconds(31)))
            assertEquals(0, savedState(store).outbox.size)
            d.handle(v.seal("vault.lock", "{}", re = req.id, status = Inner.STATUS_OK))
            assertTrue(locking.await().isSuccess)

            // The relay cannot be reached: the lock fails now and nothing is kept to be sent later.
            refuseStatus = 503
            refuseDeposits = "unavailable"
            assertTrue(runCatching { api.lock(timeout = java.time.Duration.ofMillis(500)) }.isFailure)
            assertEquals(0, savedState(store).outbox.size)
            // What an earlier version left there (it sent locks through the outbox) is dropped, and only that.
            runCatching { d.send("vault.lock") }
            runCatching { d.send("message.send", buildJsonObject { put("text", "hi") }) }
            assertEquals(listOf("vault.lock", "message.send"), savedState(store).outbox.map { it.type })
            assertEquals(1, d.dropFromOutbox("vault.lock"))
            assertEquals(listOf("message.send"), savedState(store).outbox.map { it.type })
            refuseDeposits = null
            d.flushOutbox()
            assertEquals("message.send", v.open(nextDeposit().second).type)
            assertNull(deposits.poll(300, TimeUnit.MILLISECONDS))
        }
    }

    // --- a lost answer to a PIN or password change (VAULT-MESSAGING 0.44.0 §11.14.9) ---

    private fun utkJson(id: String, utk: KemPrivateKey) =
        """{"utk_id":"$id","ek":"${Base64s.encodeStd(utk.publicKey.bytes())}","expires_at":"2099-01-01T00:00:00.000Z"}"""

    private fun FakeVault.error(re: String, type: String, code: String): RelayMessage = msg(
        keyring.current()!!.seal(
            Inner(id = Ulid.new(), type = type, ts = Instant.now(), re = re, status = Inner.STATUS_ERROR,
                error = com.vettid.core.crypto.envelope.InnerError(code)),
        ),
    )

    /**
     * §11.14.9 steps 1–3: the marker (type, inner id, blob version, exp; no secret) is stored before anything is sent;
     * the request carries an exp 10 minutes after the vault's clock and is sent three times with the same inner id and
     * body, never through the outbox; no answer is [ChangeUnansweredException] with the kept request; a resend is the
     * same request again, and the vault's cached answer settles it (the new blob kept and acked).
     */
    @Test
    fun aLostChangeIsKeptAndResentAsTheSameRequestUntilItsAnswerSettlesIt() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val (d, v) = pairedDevice(this, store, timeout = java.time.Duration.ofMillis(400))
            val api = VaultApi(d) { true }
            d.keepCredential("QkxPQg==", 3)
            val utk = KemPrivateKey.generate()
            val markers = mutableListOf<ChangeMarker>()
            val depositsAtMarker = mutableListOf<Int>()
            val change = async(Dispatchers.IO) {
                runCatching {
                    api.credentialChangePassword("old password", "new password!", "975310") { m ->
                        markers.add(m)
                        depositsAtMarker.add(deposits.size)
                    }
                }
            }
            val get = v.open(nextDeposit().second)
            assertEquals("credential.utk.get", get.type)
            d.handle(v.seal(get.type, """{"utks":[${utkJson("00112233445566c1", utk)}]}""", re = get.id, status = Inner.STATUS_OK))
            val sends = (1..3).map { v.open(nextDeposit().second) }
            val e = change.await().exceptionOrNull() as ChangeUnansweredException
            val m = markers.single()
            assertEquals("stored before the first send", listOf(0), depositsAtMarker)
            assertEquals(ChangeTypes.PASSWORD_CHANGE, m.type)
            assertEquals(3L, m.sentVersion)
            assertEquals(m, e.kept.marker)
            val ahead = java.time.Duration.between(Instant.now(), m.exp)
            assertTrue("exp 10 minutes ahead: $ahead", ahead > java.time.Duration.ofMinutes(9) && ahead <= java.time.Duration.ofMinutes(10))
            for (r in sends) {
                assertEquals(m.innerId, r.id)
                assertEquals(m.type, r.type)
                assertEquals(m.exp, r.exp)
                org.junit.Assert.assertArrayEquals(sends[0].body, r.body)
            }
            // The payload is the one typed, sealed once: the same secrets, never re-typed.
            val payload = StrictJson.parseObject(
                com.vettid.core.crypto.credential.CredentialSeal.openPayload(
                    utk, v.vaultId, "00112233445566c1", m.type, m.innerId, StrictJson.parseObject(sends[0].body).base64("sealed"),
                ),
            )
            assertEquals("old password", payload.string("password"))
            assertTrue("never written to the phone's storage", savedState(store).outbox.none { it.type == m.type })

            // The resend: the same id and body once more; the vault answers from its cache.
            val again = async(Dispatchers.IO) { api.changeResend(e.kept) }
            val r = v.open(nextDeposit().second)
            assertEquals(m.innerId, r.id)
            org.junit.Assert.assertArrayEquals(sends[0].body, r.body)
            d.handle(v.seal(r.type, """{"credential":"TkVX","version":4,"utks":[]}""", re = r.id, status = Inner.STATUS_OK))
            val answer = again.await()
            assertNotNull(answer)
            val settle = async(Dispatchers.IO) { api.changeSettle(e.kept, answer!!) }
            val ack = v.open(nextDeposit().second)
            assertEquals("credential.ack", ack.type)
            d.handle(v.seal(ack.type, "{}", re = ack.id, status = Inner.STATUS_OK))
            settle.await()
            assertEquals(4L, d.credentialVersion)
            assertEquals("TkVX", d.credentialBlob())
        }
    }

    /** §11.14.9 step 4: an answer that comes after the sends gave up is not lost: the next check takes it, unsent. */
    @Test
    fun aLateAnswerToALostChangeIsTakenWithoutSendingAgain() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this, timeout = java.time.Duration.ofMillis(300))
            val api = VaultApi(d) { true }
            d.keepCredential("QkxPQg==", 3)
            val change = async(Dispatchers.IO) {
                runCatching { api.pinChange("975310", "135792", "password one", ByteArray(32) { 7 }) }
            }
            val get = v.open(nextDeposit().second)
            d.handle(v.seal(get.type, """{"utks":[${utkJson("00112233445566c2", KemPrivateKey.generate())}]}""", re = get.id, status = Inner.STATUS_OK))
            val first = (1..3).map { v.open(nextDeposit().second) }.first()
            assertEquals(ChangeTypes.PIN_CHANGE, first.type)
            val e = change.await().exceptionOrNull() as ChangeUnansweredException
            // The vault's answer to the first send arrives late.
            d.handle(v.seal(first.type, """{"credential":"TkVX","version":4,"utks":[],"epoch_advanced":true}""", re = first.id, status = Inner.STATUS_OK))
            val answer = api.changeResend(e.kept)
            assertNotNull(answer)
            assertNull("nothing sent again", deposits.poll(300, TimeUnit.MILLISECONDS))
        }
    }

    /**
     * §11.14.9 step 4: `stale_credential` to a change is never retried with the typed secrets (it would spend an attempt
     * with what may be the old PIN): the latest blob is fetched and the refusal thrown, and nothing else is sent.
     */
    @Test
    fun aStaleCredentialToAChangeIsNotRetriedWithTheTypedSecrets() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d) { true }
            d.keepCredential("QkxPQg==", 3)
            val change = async(Dispatchers.IO) {
                runCatching { api.pinChange("975310", "135792", "password one", ByteArray(32) { 7 }) }
            }
            val get = v.open(nextDeposit().second)
            d.handle(v.seal(get.type, """{"utks":[${utkJson("00112233445566c3", KemPrivateKey.generate())}]}""", re = get.id, status = Inner.STATUS_OK))
            val pc = v.open(nextDeposit().second)
            assertEquals(ChangeTypes.PIN_CHANGE, pc.type)
            d.handle(v.error(pc.id, pc.type, "stale_credential"))
            val fetch = v.open(nextDeposit().second)
            assertEquals("credential.get", fetch.type)
            d.handle(v.seal(fetch.type, """{"credential":"TkVX","version":5,"utks":[]}""", re = fetch.id, status = Inner.STATUS_OK))
            val ack = v.open(nextDeposit().second)
            assertEquals("credential.ack", ack.type)
            d.handle(v.seal(ack.type, "{}", re = ack.id, status = Inner.STATUS_OK))
            assertEquals("stale_credential", (change.await().exceptionOrNull() as VaultOpException).code)
            assertNull("no second pin.change", deposits.poll(500, TimeUnit.MILLISECONDS))
            assertEquals(5L, d.credentialVersion)
        }
    }

    /** §11.14.9 step 1: a marker that cannot be stored stops the change before anything is sent; the UTK goes back. */
    @Test
    fun aMarkerThatCannotBeStoredStopsTheChangeBeforeTheSend() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d) { true }
            d.keepCredential("QkxPQg==", 3)
            val change = async(Dispatchers.IO) {
                runCatching { api.credentialChangePassword("old password", "new password!", "975310") { throw java.io.IOException("not stored") } }
            }
            val get = v.open(nextDeposit().second)
            d.handle(v.seal(get.type, """{"utks":[${utkJson("00112233445566c4", KemPrivateKey.generate())}]}""", re = get.id, status = Inner.STATUS_OK))
            assertEquals("not stored", change.await().exceptionOrNull()?.message)
            assertNull("nothing sent", deposits.poll(500, TimeUnit.MILLISECONDS))
            assertEquals(1, d.utkCount)
        }
    }

    /**
     * §11.14.9 step 2: while a change is unresolved the gate refuses every other credential operation before anything is
     * sent (an owner check, another change of secret, a sealed operation), and lets them through once it is decided.
     */
    @Test
    fun theGateStopsEveryOtherCredentialOperationBeforeItIsSent() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d) { true }
            d.keepCredential("QkxPQg==", 3)
            var undecided = true
            api.setCredentialGate { if (undecided) throw VaultStateException("change undecided") }
            assertTrue(runCatching { api.ownerCheck("975310", "password one") }.exceptionOrNull() is VaultStateException)
            assertTrue(runCatching { api.secretRenew("975310", "password one", ByteArray(32)) }.exceptionOrNull() is VaultStateException)
            assertTrue(runCatching { api.credentialUnlock("password one") }.exceptionOrNull() is VaultStateException)
            assertTrue(runCatching { api.credentialCreate("password one") }.exceptionOrNull() is VaultStateException)
            assertNull("nothing sent, not even a UTK request", deposits.poll(300, TimeUnit.MILLISECONDS))
            undecided = false
            val check = async(Dispatchers.IO) { runCatching { api.ownerCheck("975310", "password one") } }
            assertEquals("credential.utk.get", v.open(nextDeposit().second).type)
            check.cancel()
        }
    }

    /**
     * §11.14.9 step 1: the vault's clock as last seen is the newest answer's `ts` carried forward on the phone's monotonic
     * clock; `credential.version` comes with its answer's `ts`.
     */
    @Test
    fun theVaultClockIsTheNewestAnswerCarriedForward() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (d, v) = pairedDevice(this)
            val api = VaultApi(d)
            val ahead = Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val asked = async(Dispatchers.IO) { api.credentialVersionAt() }
            val req = v.open(nextDeposit().second)
            assertEquals("credential.version", req.type)
            d.handle(v.msg(v.keyring.current()!!.seal(Inner(id = Ulid.new(), type = req.type, ts = ahead, re = req.id, status = Inner.STATUS_OK, body = """{"exists":true,"version":4}""".toByteArray()))))
            val (info, ts) = asked.await()
            assertEquals(4L, info.version)
            assertEquals(ahead, ts)
            assertEquals(ahead, d.vaultNow())
            elapsed += 30_000
            assertEquals(ahead.plusSeconds(30), d.vaultNow())
            // An older message delivered late does not move it back.
            d.handle(v.seal("feed.event", """{"id":"f1"}"""))
            assertEquals(ahead.plusSeconds(30), d.vaultNow())
        }
    }

    /**
     * §11.14.9 (M1): every credential operation carries an exp and stays unsettled until it is answered; while one may
     * still reach the vault no PIN or password change is sent (nothing at all goes out), so that "version + 1" can only
     * be the change's. Its answer, also one that comes after its wait ended, settles it.
     */
    @Test
    fun noChangeIsSentWhileAnEarlierCredentialOperationIsUnsettled() = runBlocking<Unit> {
        withTimeout(30_000) {
            val store = InMemoryDeviceStateStore()
            val (d, v) = pairedDevice(this, store, timeout = java.time.Duration.ofMillis(300))
            val api = VaultApi(d) { true }
            d.keepCredential("QkxPQg==", 3)
            val check = async(Dispatchers.IO) { runCatching { api.ownerCheck("975310", "password one") } }
            val get = v.open(nextDeposit().second)
            d.handle(v.seal(get.type, """{"utks":[${utkJson("00112233445566c5", KemPrivateKey.generate())}]}""", re = get.id, status = Inner.STATUS_OK))
            val sends = (1..3).map { v.open(nextDeposit().second) }
            assertEquals(VaultApi.TYPE_OWNER_CHECK, sends[0].type)
            assertNotNull("a credential operation carries an exp", sends[0].exp)
            assertTrue(check.await().isFailure) // no answer
            assertEquals(setOf(sends[0].id), savedState(store).unsettled.keys)
            val change = runCatching { api.pinChange("975310", "135792", "password one", ByteArray(32) { 7 }) }
            assertTrue(change.exceptionOrNull() is CredentialUnsettledException)
            assertNull("nothing sent", deposits.poll(300, TimeUnit.MILLISECONDS))
            // The owner check's answer comes late: settled.
            d.handle(v.seal(sends[0].type, """{"credential":"TkVX","version":4,"utks":[]}""", re = sends[0].id, status = Inner.STATUS_OK))
            assertTrue(savedState(store).unsettled.isEmpty())
            assertTrue(d.credentialSettled())
        }
    }

    /** §11.14.9 (M1): an unanswered request is settled once the vault's own clock is more than a minute past its exp. */
    @Test
    fun anUnansweredRequestIsSettledOnlyByTheVaultsClock() {
        val exp = 1_000_000L
        val m = mutableMapOf("a" to exp, "b" to exp + 120_000)
        assertFalse("no vault ts seen yet", VaultDevice.pruneSettled(m, null))
        assertFalse(VaultDevice.pruneSettled(m, exp + 60_000))
        assertEquals(2, m.size)
        assertTrue(VaultDevice.pruneSettled(m, exp + 60_001))
        assertEquals(setOf("b"), m.keys)
    }

    @Test
    fun deviceStateJsonRoundTrips() {
        val s = DeviceState("app", "phone", "https://relay.vettid.test", vaultId = "v", outbox = mutableListOf(OutboxEntry("i", "t", "u", "m", "tok", "ZW52")))
        val b = stateJson.encodeToString(DeviceState.serializer(), s)
        assertEquals(s, stateJson.decodeFromString(DeviceState.serializer(), b))
        val api = buildJsonObject { put("x", JsonPrimitive(1)) }
        assertEquals("{\"x\":1}", String(VaultJson.bytes(api)))
    }
}
