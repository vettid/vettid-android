// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.vault

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Kid
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
import com.vettid.core.relay.RelayAuth
import com.vettid.core.relay.RelayException
import com.vettid.core.relay.RelayMessage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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

    /** When set, the relay refuses every deposit with this code (403). */
    @Volatile
    private var refuseDeposits: String? = null

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
                        MockResponse.Builder().code(403).body("""{"code":"$refuseDeposits","message":""}""").build()
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

    private fun config(store: DeviceStateStore) =
        DeviceConfig(name = "phone", relayUrl = relay.url("/").toString().trimEnd('/'), http = OkHttpClient(), store = store, requestTimeout = java.time.Duration.ofSeconds(5))

    /** Enrollment, the first handshake and device.paired: a device with a live session. */
    private suspend fun pairedDevice(
        scope: CoroutineScope,
        store: DeviceStateStore = InMemoryDeviceStateStore(),
        secrets: DeviceSecrets = DeviceSecrets.generate(),
    ): Pair<VaultDevice, FakeVault> {
        val d = VaultDevice.create(config(store), secrets)
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
            d.handle(v.seal("device.paired", """{"device_id":"dev-2","role":"app","vault_id":"${v.vaultId}","release":"${"a3".repeat(48)}","release_number":3,"token":"${v.tokenFor(d)}","transfer":true,"credential_version":5}"""))
            paired.await()
            assertEquals("dev-2", d.deviceId)
            assertEquals(v.vaultId, d.vaultId)
            assertTrue(d.paired.value)
            assertEquals(3, d.altState.releaseNumber)
        }
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

    @Test
    fun deviceStateJsonRoundTrips() {
        val s = DeviceState("app", "phone", "https://relay.vettid.test", vaultId = "v", outbox = mutableListOf(OutboxEntry("i", "t", "u", "m", "tok", "ZW52")))
        val b = stateJson.encodeToString(DeviceState.serializer(), s)
        assertEquals(s, stateJson.decodeFromString(DeviceState.serializer(), b))
        val api = buildJsonObject { put("x", JsonPrimitive(1)) }
        assertEquals("{\"x\":1}", String(VaultJson.bytes(api)))
    }
}
