// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.altchan

import com.vettid.core.attestation.AttestationException
import com.vettid.core.attestation.VerifiedEnclave
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.session.Mailbox
import com.vettid.core.crypto.session.RelayAddr
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The member API client and the alternate-channel flows against a fake member API + enclave. */
@Suppress("LargeClass") // One class: every test shares the fake member API and its helpers.
class MemberApiTest {
    private lateinit var server: MockWebServer
    private val enclaves = ArrayDeque<TestSupport.Enclave>()
    private val slots = ConcurrentHashMap<String, String>()
    private val polled = HashSet<String>()
    private val log = mutableListOf<String>()
    private var manifestSerial = 7L
    private var manifestFailFirst = false
    private var instanceMovedFirst = false
    private var recoveryResult = """{"ok":true}"""
    private var slotCode: String? = null
    private val kem = KemPrivateKey.generate()

    private fun resp(
        code: Int,
        body: String,
    ) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    @Before
    fun start() {
        enclaves.add(TestSupport.Enclave("inst-a"))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(log) { handle(request) }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun handle(r: RecordedRequest): MockResponse {
        val path = r.url.encodedPath
        log.add("${r.method} $path")
        if (path == MemberApiClient.MANIFEST_PATH) return resp(200, String(TestSupport.served(TestSupport.manifestBytes(manifestSerial))))
        if (r.headers[AppRequestSigning.HEADER] != null) return signedRoutes(r, path)
        if (r.headers["Authorization"] != "Bearer guid-1") return resp(401, """{"error":"unauthorized","message":"unauthorized"}""")
        val e = enclaves.first()
        return when {
            path == "/api/vault/enclave" -> resp(
                200,
                """{"instance_id":"${e.instanceId}","release":"${e.pcr0}","descriptor":"${Base64s.encodeStd(e.descriptor)}","attestation":"AA=="}""",
            )
            path == "/api/vault/enroll" || path == "/api/vault/unlock" -> {
                val o = StrictJson.parseObject(r.body!!.toByteArray())
                if (o.string("instance_id") != e.instanceId) return resp(409, """{"error":"instance_moved","code":"instance_moved"}""")
                if (instanceMovedFirst) {
                    instanceMovedFirst = false
                    enclaves.removeFirst()
                    return resp(409, """{"error":"instance_moved","code":"instance_moved","message":"moved"}""")
                }
                val env = Base64s.decodeStd(o.string("envelope"))
                assertEquals(13_444, env.size)
                val inner = e.open(env)
                val rid = o.string("request_id")
                assertEquals(rid, inner.id)
                val body = StrictJson.parseObject(inner.body)
                assertEquals(body.string("manifest_sha256"), o.string("manifest_sha256"))
                val enroll = path.endsWith("enroll")
                if (enroll) enrolledVaultIds.add(o.string("vault_id"))
                val result = when {
                    manifestFailFirst -> {
                        manifestFailFirst = false
                        if (enroll) """{"ok":false,"code":"manifest"}""" else """{"ok":false,"code":"manifest","header_seq":0,"retry_after":0}"""
                    }
                    enroll -> """{"ok":true,"vault_id":"0123456789abcdef0123456789abcdef"}"""
                    else -> """{"ok":true,"state_seq":5,"header_seq":3,"token":"v4.public.t","release":"${e.pcr0}","release_number":3,"release_status":"active","manifest_serial":$manifestSerial}"""
                }
                val type = if (enroll) AltResults.TYPE_ENROLL_RESULT else AltResults.TYPE_UNLOCK_RESULT
                slots[rid] = """{"status":"done","envelope":"${Base64s.encodeStd(TestSupport.sealResult(kem.publicKey, type, rid, result))}"}"""
                resp(202, """{"vault_id":"0123456789abcdef0123456789abcdef","request_id":"$rid"}""")
            }
            path == "/api/vault/recovery/register" -> {
                // §11.11.3 (0.10.6): a successful register's slot carries the clear marker beside the sealed result.
                val o = StrictJson.parseObject(r.body!!.toByteArray())
                val rid = o.string("request_id")
                assertEquals(rid, e.open(Base64s.decodeStd(o.string("envelope"))).id)
                val sealed = Base64s.encodeStd(TestSupport.sealResult(kem.publicKey, AltResults.TYPE_RECOVERY_RESULT, rid, recoveryResult))
                slots[rid] = """{"status":"done","envelope":"$sealed"${slotCode?.let { ",\"code\":\"$it\"" } ?: ""}}"""
                resp(202, """{"vault_id":"0123456789abcdef0123456789abcdef","request_id":"$rid"}""")
            }
            path == "/api/vault/lock" -> {
                val o = StrictJson.parseObject(r.body!!.toByteArray())
                val rid = o.string("request_id")
                lockBodies += o
                slots[rid] = lockSlot
                resp(202, "{}")
            }
            path.startsWith("/api/vault/requests/") -> {
                // The first poll of each slot is still queued (the enclave has not answered yet).
                val rid = path.substringAfterLast('/')
                if (polled.add(rid)) resp(200, """{"status":"queued"}""") else resp(200, slots.remove(rid) ?: """{"status":"expired"}""")
            }
            else -> resp(404, """{"error":"not_found"}""")
        }
    }

    private var statusBody = """{"vault":null,"service":"available"}"""

    /** The lock requests' bodies, and the slot each lock gets. */
    private val lockBodies = mutableListOf<com.vettid.core.crypto.json.JsonObject>()
    private var lockSlot = """{"status":"done"}"""
    private val enrolledVaultIds = mutableListOf<String>()
    private val signed = mutableListOf<Pair<String, String>>()
    private val appKey = SoftAppKey()

    /** A software P-256 key in place of the Keystore's (the signature format is the same). */
    class SoftAppKey : AppKeySigner {
        private val kp = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        override fun spki(): ByteArray = kp.public.encoded

        override fun sign(message: ByteArray): ByteArray = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(message)
            sign()
        }

        fun verify(message: ByteArray, sig: ByteArray): Boolean = java.security.Signature.getInstance("SHA256withECDSA").run {
            initVerify(kp.public)
            update(message)
            verify(sig)
        }
    }

    /** Checks `X-VettID-App` as the member API does (§11.12.2), then answers the 2.0.0 routes. */
    private fun signedRoutes(r: RecordedRequest, path: String): MockResponse {
        val h = r.headers[AppRequestSigning.HEADER]!!
        val f = h.split("; ").associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(listOf("v", "vault", "kid", "ts", "nonce", "sig"), h.split("; ").map { it.substringBefore('=') })
        assertEquals("1", f["v"])
        assertEquals(AppRequestSigning.akid(appKey.spki()), f["kid"])
        assertEquals(22, f["nonce"]!!.length) // 16 bytes, base64url without padding
        assertTrue(kotlin.math.abs(f["ts"]!!.toLong() - Instant.now().epochSecond) < 300)
        val body = r.body?.toByteArray() ?: ByteArray(0)
        val msg = AppRequestSigning.signingString(r.method!!, path, r.url.encodedQuery ?: "", f["vault"]!!, f["kid"]!!, f["ts"]!!.toLong(), f["nonce"]!!, body)
        if (!appKey.verify(msg.toByteArray(), Base64s.decodeRawUrl(f["sig"]!!))) return resp(401, """{"error":"unauthorized"}""")
        assertEquals("cookies are never sent", null, r.headers["Cookie"])
        signed.add(path to f["vault"]!!)
        return when (path) {
            "/api/vault/enroll/redeem" -> {
                val o = StrictJson.parseObject(body)
                assertEquals(Base64s.encodeStd(appKey.spki()), o.string("app_key"))
                val ok = o.optString("secret") == "AbCdEfGhIjKlMnOpQrStUv" || (o.optString("email") == "sam@example.org" && o.optString("code") == "K7QM4XRP")
                if (ok) resp(200, """{"vault_id":"0123456789abcdef0123456789abcdef","user_guid":"guid-1","email_hint":"s***@example.org"}""")
                else resp(404, """{"error":"invalid_code","code":"invalid_code"}""")
            }
            "/api/vault/recovery/claim" -> {
                val o = StrictJson.parseObject(body)
                assertEquals(Base64s.encodeStd(appKey.spki()), o.string("app_key"))
                if (o.has("locator")) return claimByLocator(String(body), o.string("locator"), f["vault"]!!)
                if (o.string("recovery_id") == "01JA0RECVERY0000000000001X") resp(200, """{"user_guid":"guid-1","email_hint":"s***@example.org"}""")
                else resp(409, """{"error":"recovery_not_available","code":"recovery_not_available"}""")
            }
            "/api/vault/status" -> resp(200, statusBody)
            "/api/vault/deletion/cancel" -> {
                val id = StrictJson.parseObject(body).string("deletion_id")
                resp(200, """{"cancelled":${id == "01JDELETION0000000000000000"}}""")
            }
            else -> resp(404, """{"error":"not_found"}""")
        }
    }

    private val locatorClaims = mutableListOf<String>()

    /** The typed form's claim (MEMBER-API 2.4.0), as the member API answers it; the made-up locators pick the answer. */
    private fun claimByLocator(body: String, locator: String, vault: String): MockResponse {
        locatorClaims.add(body)
        // Signed by the key it registers with an empty `vault`: anything else is not this route's caller.
        if (vault != "") return resp(401, """{"error":"unauthorized","message":"unauthorized"}""")
        return when (locator) {
            "7ZK3M9QX2HTD" -> resp(
                200,
                """{"user_guid":"guid-1","email_hint":"s***@example.org","vault_id":"0123456789abcdef0123456789abcdef","recovery_id":"01JA0RECVERY0000000000001X"}""",
            )
            "N0TAVA11AB1E" -> resp(409, """{"error":"recovery_not_available","code":"recovery_not_available","message":"No recovery code is valid now"}""")
            "RATE11M1TED0" -> MockResponse.Builder().code(429).addHeader("Retry-After", "754").addHeader("Content-Type", "application/json")
                .body("""{"error":"rate_limited","message":"Too many requests"}""").build()
            "BADREQVEST00" -> resp(400, """{"error":"bad_request","message":"Send exactly one of {vault_id, recovery_id, app_key} or {locator, app_key}"}""")
            "PAVSED000000" -> MockResponse.Builder().code(503).addHeader("Retry-After", "120").addHeader("Content-Type", "application/json")
                .body("""{"error":"vault_unavailable","code":"vault_unavailable","service":"paused"}""").build()
            "01DAP1000000" -> resp(200, """{"user_guid":"guid-1","email_hint":"s***@example.org"}""")
            else -> resp(404, """{"error":"not_found","message":"No such recovery"}""")
        }
    }

    private fun api(auth: MemberAuth = MemberAuth.Bearer("guid-1")) =
        MemberApiClient(server.url("/").toString(), server.url(MemberApiClient.MANIFEST_PATH).toString(), OkHttpClient(), auth, sleep = { })

    /** A minimal device: what :core:vault's VaultDevice does for the alternate channel. */
    private inner class Party : AltParty {
        var state = AltState()
        var vaultId: String? = null
        var pending: PendingUnlock? = null
        var built = 0
        val discarded = mutableListOf<BuiltUnlock>()
        val ik = Ed25519PrivateKey.generate()
        private val relayKey = Ed25519PrivateKey.generate()
        val app = AppIdentity(ik.publicKey, kem.publicKey, RelayAddr("https://relay.vettid.test", Mailbox.id(relayKey.publicKey), relayKey.publicKey), "phone")

        override fun manifestSerialSeen() = state.manifestSerial

        override fun vaultId() = vaultId

        override suspend fun prepareEnroll(
            userGuid: String,
            pin: String,
            enclave: VerifiedEnclave,
            manifest: ReleaseManifest,
            attester: Attester,
            unlockSecret: ByteArray?,
        ): SealedRequest {
            val b = AltRequests.buildEnroll(userGuid, pin, enclave, manifest, attester, app, "v4.public.open", state, Instant.now(), unlockSecret)
            state = b.state
            return b.request
        }

        override suspend fun openEnrollResult(
            raw: ByteArray,
            requestId: String,
        ) = EnrollResult.parse(AltResults.open(raw, kem, AltResults.TYPE_ENROLL_RESULT, requestId))

        override suspend fun prepareUnlock(
            userGuid: String,
            pin: String,
            enclave: VerifiedEnclave,
            manifest: ReleaseManifest,
            attester: Attester,
            options: UnlockOptions,
        ): BuiltUnlock {
            val b = AltRequests.buildUnlock(userGuid, vaultId!!, pin, enclave, manifest, attester, ik, "v4.public.tok", state, options, Instant.now())
            pending = b.pending
            built++
            return b
        }

        override suspend fun discardUnlock(built: BuiltUnlock) {
            discarded += built
            if (pending == built.pending) pending = null
        }

        override suspend fun openUnlockResult(raw: ByteArray): UnlockResult {
            val p = pending!!
            val r = UnlockResult.parse(AltResults.open(raw, kem, AltResults.TYPE_UNLOCK_RESULT, p.requestId))
            state = AltRequests.applyUnlock(state, p, r)
            return r
        }
    }

    private fun flow(
        api: MemberApiClient = api(),
        timeScales: (Long) -> Boolean = AltTrust.PRODUCTION_SCALES,
    ) = AltChannelFlow(api,
        AltTrust(emptyList(), listOf(TestSupport.manifestKey), timeScales),
    ) { d, _, m, enroll, _ ->
        val e = enclaves.first()
        assertTrue(d.contentEquals(e.descriptor))
        if (enroll) assertNotNullRelease(m)
        e.verified(m)
    }

    private fun assertNotNullRelease(m: ReleaseManifest) = assertTrue(m.byPcr0(TestSupport.pcr0) != null)

    /** MEMBER-API 2.5.0 "Locks" (release L): a bound lock names its unlock; the slot's code is passed on. */
    @Test
    fun aBoundLockNamesItsUnlockAndItsSlotsCodeIsRead() = runBlocking<Unit> {
        val f = flow()
        val id = "00112233445566778899aabbccddeeff"
        lockSlot = """{"status":"done","code":"stale"}"""
        val slot = f.lock("0123456789abcdef0123456789abcdef", unlockId = id)
        assertEquals(Slot.DONE, slot.status)
        assertEquals(Slot.CODE_STALE, slot.code)
        assertEquals(id, lockBodies.last().optString("unlock_id"))
        lockSlot = """{"status":"done"}"""
        assertEquals(null, f.lock("0123456789abcdef0123456789abcdef").code)
        assertFalse("unconditional: no member at all", lockBodies.last().has("unlock_id"))
    }

    @Test
    fun enrollUnlockLock() = runBlocking<Unit> {
        val p = Party()
        val f = flow()
        val out = f.enroll(p, "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester())
        assertTrue(out.ok)
        assertEquals("0123456789abcdef0123456789abcdef", out.vaultId)
        assertEquals("inst-a", out.instanceId)
        assertEquals("MEMBER-API 2.0.0: the enroll names the redeemed vault", listOf("0123456789abcdef0123456789abcdef"), enrolledVaultIds)
        p.vaultId = out.vaultId
        p.state = AltRequests.applyEnrolled(p.state, 1)
        val u = f.unlock(p, "guid-1", "246802", TestSupport.SoftAttester())
        assertTrue(u.ok)
        assertEquals(5, p.state.stateSeq)
        assertEquals(3L, p.state.headerSeq[TestSupport.pcr0])
        assertEquals(7, p.state.manifestSerial)
        assertEquals(Slot.DONE, f.lock(out.vaultId).status)
        assertTrue(log.contains("GET ${MemberApiClient.MANIFEST_PATH}"))
        assertEquals(6, log.count { it.startsWith("GET /api/vault/requests/") }) // each slot: queued, then done
    }

    /**
     * (VAULT-MESSAGING §11.14.9 step 2) The unlock's `beforePost` runs before the request is built and again right before
     * it is posted: a throw before the build builds nothing (no token minted, nothing recorded); a throw between the build
     * and the post posts nothing and discards what the build recorded; otherwise the unlock goes as usual.
     */
    @Test
    fun beforePostRunsBeforeEachUnlockPostAndAThrowPostsNothing() = runBlocking<Unit> {
        val p = Party()
        val f = flow()
        val out = f.enroll(p, "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester())
        p.vaultId = out.vaultId
        p.state = AltRequests.applyEnrolled(p.state, 1)
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking { f.unlock(p, "guid-1", "246802", TestSupport.SoftAttester(), beforePost = { error("pin taken before a change") }) }
        }
        assertEquals("pin taken before a change", e.message)
        assertEquals(0, log.count { it == "POST /api/vault/unlock" })
        assertEquals("nothing built", 0, p.built)
        // Moved while the request was built: not posted, and what the build recorded is discarded.
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking { f.unlock(p, "guid-1", "246802", TestSupport.SoftAttester(), beforePost = { check(++calls < 2) { "moved" } }) }
        }
        assertEquals(1, p.built)
        assertEquals(1, p.discarded.size)
        assertEquals(null, p.pending)
        assertEquals(0, log.count { it == "POST /api/vault/unlock" })
        var checks = 0
        assertTrue(f.unlock(p, "guid-1", "246802", TestSupport.SoftAttester(), beforePost = { checks++ }).ok)
        assertEquals("before the build and before the post", 2, checks)
        assertEquals(1, log.count { it == "POST /api/vault/unlock" })
    }

    private suspend fun register(): RecoveryResult {
        val code = RecoveryCode("0123456789abcdef0123456789abcdef", Ulid.new(), "SK01TG8WK2FYJ1Y5MEHJ5R5J7QZKWHX0")
        val app = Party().app
        return flow().recoveryRegister(
            code.vaultId,
            build = { e -> AltRequests.buildRecoveryRegister("guid-1", code, e, TestSupport.SoftAttester(), app, Instant.now()) },
            open = { raw, rid -> RecoveryResult.parse(AltResults.open(raw, kem, AltResults.TYPE_RECOVERY_RESULT, rid)) },
        )
    }

    /**
     * 0.10.6 (§11.5, §11.11.3): the host copies `recovery_registered` into a successful register's slot, with
     * the envelope. The marker is for the member API; the app reads its sealed result as before.
     */
    @Test
    fun theRecoveryRegisteredMarkerInTheSlotIsIgnored() = runBlocking<Unit> {
        slotCode = Slot.RECOVERY_REGISTERED
        assertEquals(RecoveryResult(true, null), register())
        assertEquals(1, log.count { it == "POST /api/vault/recovery/register" }) // not retried as an error
        assertEquals(2, log.count { it.startsWith("GET /api/vault/requests/") })
    }

    @Test
    fun theSealedResultDecidesWhateverTheSlotsCode() = runBlocking<Unit> {
        // A forged marker on a refusal changes nothing: the sealed result says bad_code.
        slotCode = Slot.RECOVERY_REGISTERED
        recoveryResult = """{"ok":false,"code":"bad_code"}"""
        assertEquals(RecoveryResult(false, "bad_code"), register())
        // An unknown host code beside an envelope is not an error either.
        slotCode = "some_future_code"
        recoveryResult = """{"ok":true}"""
        assertEquals(RecoveryResult(true, null), register())
        assertEquals(2, log.count { it == "POST /api/vault/recovery/register" })
    }

    /**
     * VAULT-MESSAGING 0.34.0 §11.10.9: the request's shape is chosen from the verified instance's capabilities before
     * anything is built; a refusal there (a vault known to have release R, an instance without it) sends nothing.
     */
    @Test
    fun theInstancesCapabilitiesChooseTheShapeAndARefusalSendsNothing() = runBlocking<Unit> {
        enclaves.clear()
        enclaves.add(TestSupport.Enclave("inst-a", capabilities = listOf("attachments", "recovery_offered", "recovery_r")))
        val p = Party()
        var seen: List<String>? = null
        val out = flow().enroll(p, "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester()) {
            seen = it.capabilities
            ByteArray(32) { 0x42 }
        }
        assertTrue(out.ok)
        assertEquals(listOf("attachments", "recovery_offered", "recovery_r"), seen)
        p.vaultId = out.vaultId
        p.state = AltRequests.applyEnrolled(p.state, 1)
        // An instance without recovery_r for a vault known to have it: refused before the request is built.
        enclaves.clear()
        enclaves.add(TestSupport.Enclave("inst-a"))
        val enrolls = log.count { it == "POST /api/vault/enroll" }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { flow().unlock(p, "guid-1", "246802", TestSupport.SoftAttester()) { t -> check("recovery_r" in t.capabilities) { "rollback" }; null } }
        }
        assertEquals(0, log.count { it == "POST /api/vault/unlock" })
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                flow().enroll(p, "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester()) { t ->
                    check("recovery_r" in t.capabilities) { "rollback" }
                    null
                }
            }
        }
        assertEquals(enrolls, log.count { it == "POST /api/vault/enroll" })
        // A release before R (no list): the earlier shape, as before.
        var plain: List<String>? = null
        assertTrue(flow().unlock(p, "guid-1", "246802", TestSupport.SoftAttester()) { t -> plain = t.capabilities; null }.ok)
        assertEquals(emptyList<String>(), plain)
    }

    /**
     * STAGING-TIME-SCALE §4 item 4, §5.6: a production build refuses a descriptor whose `time_scale` is not 1 before
     * anything is built or sent; a staging build accepts 1 and 24 and hands the scale to the app with the capabilities.
     */
    @Test
    fun aProductionBuildRefusesAScaledInstanceAndSendsNothing() = runBlocking<Unit> {
        enclaves.clear()
        enclaves.add(TestSupport.Enclave("inst-a", timeScale = 24))
        val e = assertThrows(AltRefusedException::class.java) {
            runBlocking {
                flow().enroll(Party(), "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester()) {
                    error("asked for the request's shape")
                }
            }
        }
        assertEquals(AltRefusedException.Reason.TIME_SCALE, e.reason)
        assertEquals(0, log.count { it == "POST /api/vault/enroll" })
        val p = Party().also { it.vaultId = "0123456789abcdef0123456789abcdef" }
        assertThrows(AltRefusedException::class.java) { runBlocking { flow().unlock(p, "guid-1", "246802", TestSupport.SoftAttester()) } }
        assertEquals(0, log.count { it == "POST /api/vault/unlock" })
        assertThrows(AltRefusedException::class.java) { runBlocking { flow().preflight(p.state) } }
    }

    /**
     * STAGING-TIME-SCALE §4 item 4 (VAULT-MESSAGING §11.10.9 "Where the app reads it": a recovery's register and open
     * read the descriptor of the instance the vault is routed to): a production build refuses a scaled instance there
     * too, before the request is built or anything is posted.
     */
    @Test
    fun aProductionBuildRefusesAScaledInstanceForARecoveryRegister() = runBlocking<Unit> {
        enclaves.clear()
        enclaves.add(TestSupport.Enclave("inst-a", timeScale = 24))
        val e = assertThrows(AltRefusedException::class.java) {
            runBlocking {
                flow().recoveryRegister(
                    "0123456789abcdef0123456789abcdef",
                    build = { error("built a request") },
                    open = { _, _ -> error("opened a result") },
                )
            }
        }
        assertEquals(AltRefusedException.Reason.TIME_SCALE, e.reason)
        assertEquals(0, log.count { it == "POST /api/vault/recovery/register" })
        assertEquals(0, log.count { it.startsWith("GET /api/vault/requests/") })
        // The same instance at real time: registered as before.
        enclaves.clear()
        enclaves.add(TestSupport.Enclave("inst-a", timeScale = 1))
        assertEquals(RecoveryResult(true, null), register())
    }

    @Test
    fun aProductionBuildRefusesAScaledInstanceForARecoveryOpen() = runBlocking<Unit> {
        enclaves.clear()
        enclaves.add(TestSupport.Enclave("inst-a", timeScale = 24))
        var sent = false
        val e = assertThrows(AltRefusedException::class.java) {
            runBlocking {
                flow().recoveryOpen(
                    "0123456789abcdef0123456789abcdef",
                    build = { _, _ -> error("built a request") },
                    open = { _, _ -> error("opened a result") },
                    onSent = { sent = true },
                )
            }
        }
        assertEquals(AltRefusedException.Reason.TIME_SCALE, e.reason)
        assertFalse("nothing may have reached the vault, no attempt used", sent)
        assertEquals(0, log.count { it == "POST /api/vault/recovery/open" })
        assertEquals(0, log.count { it.startsWith("GET /api/vault/requests/") })
    }

    @Test
    fun aStagingBuildAcceptsOneAndTwentyFourOnly() = runBlocking<Unit> {
        for (scale in listOf(null, 1L, 24L)) {
            enclaves.clear()
            enclaves.add(TestSupport.Enclave("inst-a", timeScale = scale))
            var seen: Long? = null
            val out = flow(timeScales = AltTrust.STAGING_SCALES)
                .enroll(Party(), "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester()) {
                    seen = it.timeScale
                    null
                }
            assertTrue(out.ok)
            // Absent: a release before the scale, real time.
            assertEquals(scale ?: 1L, seen)
        }
        enclaves.clear()
        enclaves.add(TestSupport.Enclave("inst-a", timeScale = 2))
        val e = assertThrows(AltRefusedException::class.java) {
            runBlocking {
                flow(timeScales = AltTrust.STAGING_SCALES)
                    .enroll(Party(), "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester())
            }
        }
        assertEquals(AltRefusedException.Reason.TIME_SCALE, e.reason)
    }

    @Test
    fun theTrustsTimeScales() {
        assertTrue(AltTrust.production().acceptsTimeScale(1))
        assertFalse(AltTrust.production().acceptsTimeScale(24))
        assertTrue(AltTrust.staging().acceptsTimeScale(1))
        assertTrue(AltTrust.staging().acceptsTimeScale(24))
        assertFalse(AltTrust.staging().acceptsTimeScale(12))
        assertFalse(AltTrust.staging().acceptsTimeScale(3_600))
        assertTrue(AltTrust(emptyList(), emptyList(), AltTrust.DEV_STACK_SCALES).acceptsTimeScale(3_600))
    }

    @Test
    fun reSealsAfterInstanceMoved() = runBlocking<Unit> {
        enclaves.add(TestSupport.Enclave("inst-b"))
        instanceMovedFirst = true
        val out = flow().enroll(Party(), "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester())
        assertTrue(out.ok)
        assertEquals("inst-b", out.instanceId)
        assertEquals(2, log.count { it == "POST /api/vault/enroll" })
    }

    @Test
    fun refetchesTheManifestOnceAfterAManifestResult() = runBlocking<Unit> {
        manifestFailFirst = true
        val out = flow().enroll(Party(), "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester())
        assertTrue(out.ok)
        assertEquals(2, log.count { it == "GET ${MemberApiClient.MANIFEST_PATH}" })
    }

    @Test
    fun refusesAnOlderManifest() = runBlocking<Unit> {
        val p = Party()
        p.state = AltState(manifestSerial = 9)
        val e = assertThrows(AltRefusedException::class.java) { runBlocking { flow().enroll(p, "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester()) } }
        assertEquals(AltRefusedException.Reason.MANIFEST_OLDER, e.reason)
    }

    @Test
    fun apiErrorsCarryTheSpecCode() = runBlocking<Unit> {
        val e = assertThrows(MemberApiException::class.java) { runBlocking { api(MemberAuth.Bearer("someone-else")).enclave() } }
        assertEquals(401, e.status)
        assertEquals(MemberApiException.UNAUTHORIZED, e.code)
    }

    /** MEMBER-API 2.1.0: `credential_backup` and a pending start-over in the status; the app's signed cancel. */
    @Test
    fun deletionInTheStatusAndTheAppsCancel() = runBlocking<Unit> {
        val vid = "0123456789abcdef0123456789abcdef"
        val a = api(MemberAuth.AppKey(appKey) { vid })
        statusBody = """{"vault":{"vault_id":"$vid","state":"unlocked","leased":true,"credential_backup":false,""" +
            """"deletion":{"state":"pending","deletes_at":"2026-10-07T20:00:00Z","deletion_id":"01JDELETION0000000000000000"}},"service":"available"}"""
        val v = a.vaultStatus()!!
        assertEquals(false, v.credentialBackup)
        assertEquals(PendingDeletion("pending", "2026-10-07T20:00:00Z", "01JDELETION0000000000000000"), v.deletion)
        statusBody = """{"vault":{"vault_id":"$vid","state":"locked","deletion":{"state":"executing","deletes_at":"2026-10-07T20:00:00Z"}}}"""
        assertEquals(PendingDeletion("executing", "2026-10-07T20:00:00Z", null), a.vaultStatus()!!.deletion)
        statusBody = """{"vault":{"vault_id":"$vid","state":"locked","deletion":null,"credential_backup":null}}"""
        assertEquals(null, a.vaultStatus()!!.deletion)
        assertEquals(null, a.vaultStatus()!!.credentialBackup)
        assertTrue(a.deletionCancel("01JDELETION0000000000000000"))
        assertEquals("/api/vault/deletion/cancel" to vid, signed.last())
        assertEquals(false, a.deletionCancel("01JOTHER0000000000000000XX"))
    }

    @Test
    fun appKeySignedRedeemClaimAndStatus() = runBlocking<Unit> {
        var vault: String? = null
        val a = api(MemberAuth.AppKey(appKey) { vault })
        // §11.12.1: the redeem names no vault (`vault=` empty) whatever the client's vault is.
        vault = "previous-vault"
        assertEquals(Redeemed("0123456789abcdef0123456789abcdef", "guid-1", "s***@example.org"), a.redeemSecret("AbCdEfGhIjKlMnOpQrStUv", appKey.spki()))
        assertEquals("/api/vault/enroll/redeem" to "", signed.last())
        assertEquals("guid-1", a.redeemTyped("sam@example.org", "K7QM4XRP", appKey.spki()).userGuid)
        val e = assertThrows(MemberApiException::class.java) { runBlocking { a.redeemTyped("sam@example.org", "K7QM4XRQ", appKey.spki()) } }
        assertEquals(MemberApiException.INVALID_CODE, e.code)
        // §11.11.7: the claim is signed with the QR's vault.
        assertEquals(Claimed("guid-1", "s***@example.org"), a.recoveryClaim("qr-vault", "01JA0RECVERY0000000000001X", appKey.spki()))
        assertEquals("/api/vault/recovery/claim" to "qr-vault", signed.last())
        val n = assertThrows(MemberApiException::class.java) { runBlocking { a.recoveryClaim("qr-vault", "01JA0OTHER00000000000000XX", appKey.spki()) } }
        assertEquals(MemberApiException.RECOVERY_NOT_AVAILABLE, n.code)
        // Later calls name the client's vault.
        vault = "0123456789abcdef0123456789abcdef"
        a.vaultStatusAnswer()
        assertEquals("/api/vault/status" to "0123456789abcdef0123456789abcdef", signed.last())
    }

    /** VAULT-MESSAGING 0.25.0 §11.11.10, MEMBER-API 2.4.0: the typed form's claim by locator. */
    @Test
    fun theClaimByLocatorSendsExactlyTheLocatorAndTheKeySignedWithAnEmptyVault() = runBlocking<Unit> {
        // The client's own vault (a previous one on this phone) is never named: the app does not know the vault yet.
        val a = api(MemberAuth.AppKey(appKey) { "previous-vault" })
        val c = a.recoveryClaimByLocator("7ZK3M9QX2HTD", appKey.spki())
        assertEquals(Claimed("guid-1", "s***@example.org", "0123456789abcdef0123456789abcdef", "01JA0RECVERY0000000000001X"), c)
        assertEquals("/api/vault/recovery/claim" to "", signed.last())
        assertEquals("POST /api/vault/recovery/claim", log.last())
        // The body: these two members in this order and nothing else (no vault_id, no recovery_id, no code, no check).
        assertEquals(listOf("""{"locator":"7ZK3M9QX2HTD","app_key":"${Base64s.encodeStd(appKey.spki())}"}"""), locatorClaims)
        // Only a canonical locator is ever sent.
        for (bad in listOf("7zk3m9qx2htd", "7ZK3M9QX2HT", "7ZK3M9QX2HTDV", "7ZK3M9QX2HTU", "")) {
            assertThrows(bad, IllegalArgumentException::class.java) { runBlocking { a.recoveryClaimByLocator(bad, appKey.spki()) } }
        }
        assertEquals(1, locatorClaims.size)
    }

    @Test
    fun theClaimByLocatorsRefusalsCarryTheApisCodeAndWait() = runBlocking<Unit> {
        val a = api(MemberAuth.AppKey(appKey) { null })
        fun refused(locator: String) = assertThrows(MemberApiException::class.java) { runBlocking { a.recoveryClaimByLocator(locator, appKey.spki()) } }
        // Any miss (no such locator, a replaced or cancelled recovery's) is the same 404.
        refused("M1SS00000000").let {
            assertEquals(404, it.status)
            assertEquals(MemberApiException.NOT_FOUND, it.code)
        }
        refused("N0TAVA11AB1E").let {
            assertEquals(409, it.status)
            assertEquals(MemberApiException.RECOVERY_NOT_AVAILABLE, it.code)
        }
        refused("RATE11M1TED0").let {
            assertEquals(429, it.status)
            assertEquals(MemberApiException.RATE_LIMITED, it.code)
            assertEquals(754, it.retryAfterSeconds)
        }
        refused("BADREQVEST00").let {
            assertEquals(400, it.status)
            assertEquals("bad_request", it.code)
        }
        refused("PAVSED000000").let {
            assertEquals(503, it.status)
            assertEquals(MemberApiException.VAULT_UNAVAILABLE, it.code)
            assertTrue(it.servicePaused)
            assertEquals(120, it.retryAfterSeconds)
        }
        // A signature the API does not accept (here: another key's).
        val other = SoftAppKey()
        val forged = object : AppKeySigner {
            override fun spki() = appKey.spki()

            override fun sign(message: ByteArray) = other.sign(message)
        }
        val u = assertThrows(MemberApiException::class.java) {
            runBlocking { api(MemberAuth.AppKey(forged) { null }).recoveryClaimByLocator("7ZK3M9QX2HTD", appKey.spki()) }
        }
        assertEquals(401, u.status)
        assertEquals(MemberApiException.UNAUTHORIZED, u.code)
        // An answer without the two identifiers (an API before 2.4.0 would not know the route's typed form) is not a claim.
        assertThrows(com.vettid.core.crypto.CryptoException::class.java) { runBlocking { a.recoveryClaimByLocator("01DAP1000000", appKey.spki()) } }
        // No server.
        server.close()
        assertThrows(java.io.IOException::class.java) { runBlocking { a.recoveryClaimByLocator("7ZK3M9QX2HTD", appKey.spki()) } }
    }

    @Test
    fun aSignatureOverAnotherRequestIsRefused() = runBlocking<Unit> {
        val other = SoftAppKey()
        val forged = object : AppKeySigner {
            override fun spki() = appKey.spki()

            override fun sign(message: ByteArray) = other.sign(message)
        }
        val e = assertThrows(MemberApiException::class.java) { runBlocking { api(MemberAuth.AppKey(forged) { null }).vaultStatusAnswer() } }
        assertEquals(401, e.status)
    }

    @Test
    fun slotPollingAndReleaseStarting() = runBlocking<Unit> {
        var starting = 2
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (starting-- > 0) {
                resp(503, """{"error":"release_starting","code":"release_starting","release":"${TestSupport.pcr0}","retry_after":30}""")
            } else {
                resp(200, """{"instance_id":"i","release":"r","descriptor":"AQ==","attestation":"Ag=="}""")
            }
        }
        val waits = mutableListOf<Long>()
        val a = MemberApiClient(server.url("/").toString(), "", OkHttpClient(), MemberAuth.Bearer("g"), sleep = { waits.add(it) })
        assertEquals("i", a.enclaveWait().instanceId)
        assertEquals(listOf(2000L, 2000L), waits)
    }

    @Test
    fun aBoundedPollGivesTheSlotBackStillQueued() = runBlocking<Unit> {
        var looks = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                looks++
                return resp(200, """{"status":"queued"}""")
            }
        }
        val waits = mutableListOf<Long>()
        val a = MemberApiClient(server.url("/").toString(), "", OkHttpClient(), MemberAuth.Bearer("g"), sleep = { waits.add(it) })
        // A lock whose slot nobody answers: the wait ends, where the unbounded poll would go on for as long as the slot lives.
        assertEquals(Slot.QUEUED, a.poll("01JABCDEFGHJKMNPQRSTVWXYZ0", atMost = Duration.ofSeconds(12)).status)
        assertEquals(12_000L, waits.sum())
        assertEquals(waits.size + 1, looks)
    }

    private val pausedBody = """{"error":"vault_unavailable","code":"vault_unavailable","message":"The vault service is paused","service":"paused","retry_after":300}"""

    @Test
    fun aPausedServiceIsThrownAtOnceWithItsRetryAfter() = runBlocking<Unit> {
        var calls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                return MockResponse.Builder().code(503).body(pausedBody).addHeader("Retry-After", "300").build()
            }
        }
        val waits = mutableListOf<Long>()
        val a = MemberApiClient(server.url("/").toString(), "", OkHttpClient(), MemberAuth.Bearer("g"), sleep = { waits.add(it) })
        val e = assertThrows(MemberApiException::class.java) { runBlocking { a.enclaveWait() } }
        assertEquals(503, e.status)
        assertEquals(MemberApiException.VAULT_UNAVAILABLE, e.code)
        assertTrue(e.servicePaused)
        assertEquals(300, e.retryAfterSeconds)
        assertEquals(1, calls) // no retry loop
        assertTrue(waits.isEmpty())
    }

    @Test
    fun retryAfterHeaderWhenTheBodyHasNone() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(503)
                .body("""{"error":"vault_unavailable","code":"vault_unavailable","service":"paused"}""").addHeader("Retry-After", "120").build()
        }
        val a = MemberApiClient(server.url("/").toString(), "", OkHttpClient(), MemberAuth.Bearer("g"), sleep = { })
        val e = assertThrows(MemberApiException::class.java) { runBlocking { a.enclave() } }
        assertTrue(e.servicePaused)
        assertEquals(120, e.retryAfterSeconds)
        // Not JSON (a load balancer's page): the header still counts.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(503).body("<html>busy</html>").addHeader("Retry-After", "60").build()
        }
        val e2 = assertThrows(MemberApiException::class.java) { runBlocking { a.enclave() } }
        assertFalse(e2.servicePaused)
        assertEquals(60, e2.retryAfterSeconds)
        assertEquals(0, MemberApiClient.retryAfterHeader("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(0, MemberApiClient.retryAfterHeader("-5"))
        assertEquals(MemberApiClient.MAX_RETRY_AFTER_S, MemberApiClient.retryAfterHeader("99999999"))
    }

    @Test
    fun notPausedWithoutServicePaused() {
        assertFalse(MemberApiException(503, MemberApiException.VAULT_UNAVAILABLE, retryAfterSeconds = 300).servicePaused)
        assertFalse(MemberApiException(503, MemberApiException.RELEASE_STARTING, service = "paused").servicePaused)
    }

    @Test
    fun statusReportsTheServiceSwitch() = runBlocking<Unit> {
        var body = """{"vault":null,"service":"paused"}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = resp(200, body)
        }
        val a = MemberApiClient(server.url("/").toString(), "", OkHttpClient(), MemberAuth.Bearer("g"), sleep = { })
        assertEquals(VaultStatusAnswer(null, servicePaused = true), a.vaultStatusAnswer())
        body = """{"vault":{"vault_id":"v1","state":"locked","leased":false},"service":"available"}"""
        val st = a.vaultStatusAnswer()
        assertFalse(st.servicePaused)
        assertEquals("v1", st.vault?.vaultId)
        body = """{"vault":null}""" // before 1.2.0: no `service`, available
        assertFalse(a.vaultStatusAnswer().servicePaused)
        assertEquals(null, a.vaultStatus())
    }

    @Test
    fun anUnlockWhilePausedIsNotRetried() = runBlocking<Unit> {
        val p = Party()
        p.vaultId = flow().enroll(p, "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester()).vaultId
        p.state = AltRequests.applyEnrolled(p.state, 1)
        val before = log.size
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(log) {
                log.add("${request.method} ${request.url.encodedPath}")
                if (request.url.encodedPath == MemberApiClient.MANIFEST_PATH) {
                    resp(200, String(TestSupport.served(TestSupport.manifestBytes(manifestSerial))))
                } else {
                    MockResponse.Builder().code(503).body(pausedBody).addHeader("Retry-After", "300").build()
                }
            }
        }
        val e = assertThrows(MemberApiException::class.java) { runBlocking { flow().unlock(p, "guid-1", "246802", TestSupport.SoftAttester()) } }
        assertTrue(e.servicePaused)
        assertEquals(listOf("GET ${MemberApiClient.MANIFEST_PATH}", "GET /api/vault/enclave"), log.drop(before))
    }

    @Test
    fun noPinIsSealedOrSentToAnEnclaveThatDidNotVerify() = runBlocking<Unit> {
        // Owner report 2026-10-11 (a release this app's manifest does not list): the check fails before the request
        // with the PIN is built, at the preflight and at the unlock; nothing but the manifest and the descriptor is read.
        val p = Party()
        p.vaultId = flow().enroll(p, "0123456789abcdef0123456789abcdef", "guid-1", "246802", TestSupport.SoftAttester()).vaultId
        p.state = AltRequests.applyEnrolled(p.state, 1)
        val refusals = listOf<() -> AttestationException>(
            { AttestationException.Release("not listed", unknown = true) },
            { AttestationException.Signature("attestation") },
            { AttestationException.Chain() },
            { AttestationException.Pcr("manifest") },
        )
        for (refusal in refusals) {
            val unverified = AltChannelFlow(api(), AltTrust(emptyList(), listOf(TestSupport.manifestKey))) { _, _, _, _, _ -> throw refusal() }
            p.pending = null
            val before = synchronized(log) { log.size }
            val atPreflight = assertThrows(AttestationException::class.java) { runBlocking { unverified.preflight(p.state) } }
            val atUnlock = assertThrows(AttestationException::class.java) {
                runBlocking { unverified.unlock(p, "guid-1", "246802", TestSupport.SoftAttester()) }
            }
            assertEquals(refusal().kind, atPreflight.kind)
            assertEquals(refusal().kind, atUnlock.kind)
            assertTrue("no unlock request was built", p.pending == null)
            val sent = synchronized(log) { log.drop(before) }
            assertTrue("only reads: $sent", sent.isNotEmpty() && sent.all { it.startsWith("GET ") })
            assertTrue("nothing to the unlock route: $sent", sent.none { it.contains("unlock") })
        }
    }

    @Test
    fun releaseStartingIsWaitedForBoundedly() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                resp(503, """{"error":"release_starting","code":"release_starting","release":"${TestSupport.pcr0}","retry_after":30}""")
        }
        var waited = 0L
        val a = MemberApiClient(server.url("/").toString(), "", OkHttpClient(), MemberAuth.Bearer("g"), sleep = { waited += it })
        val e = assertThrows(MemberApiException::class.java) { runBlocking { a.enclaveWait() } }
        assertEquals(MemberApiException.RELEASE_STARTING, e.code)
        assertEquals(MemberApiClient.MAX_START_TOTAL_MS, waited)
    }
}
