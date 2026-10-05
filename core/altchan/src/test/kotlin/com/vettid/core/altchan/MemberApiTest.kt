// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.altchan

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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The member API client and the alternate-channel flows against a fake member API + enclave. */
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
        if (path.startsWith("/api/auth/") || path.startsWith("/api/account/")) return authRoutes(r, path)
        if (r.headers["Authorization"] != "Bearer guid-1") return resp(401, """{"error":"unauthorized","message":"sign in"}""")
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
                val rid = StrictJson.parseObject(r.body!!.toByteArray()).string("request_id")
                slots[rid] = """{"status":"done"}"""
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

    private var refreshed = 0

    private fun authRoutes(r: RecordedRequest, path: String): MockResponse {
        if (r.method == "POST") assertEquals("1", r.headers[MemberAuth.CSRF_HEADER])
        return when (path) {
            "/api/auth/verify" -> MockResponse.Builder().code(200).body("""{"status":"pin_required"}""")
                .addHeader("Set-Cookie", "vid_pin=p1; Path=/api/auth; HttpOnly; Max-Age=300").build()
            "/api/auth/pin" -> {
                assertTrue(r.headers["Cookie"]!!.contains("vid_pin=p1"))
                MockResponse.Builder().code(200).body("""{"status":"signed_in"}""")
                    .addHeader("Set-Cookie", "vid_id=id1; Path=/api; HttpOnly; Max-Age=3600")
                    .addHeader("Set-Cookie", "vid_rt=rt1; Path=/api; HttpOnly; Max-Age=86400").build()
            }
            "/api/auth/refresh" -> {
                refreshed++
                MockResponse.Builder().code(200).body("""{"ok":true}""").addHeader("Set-Cookie", "vid_id=id2; Path=/api; HttpOnly; Max-Age=3600").build()
            }
            "/api/account/me" -> if (r.headers["Cookie"]?.contains("vid_id=id2") == true) {
                resp(200, """{"user_guid":"guid-1","email":"a@b.c","first_name":"A","last_name":"B","state":"member","terms":{"needs_acceptance":false},"pin_enabled":true}""")
            } else {
                resp(401, """{"error":"unauthorized"}""")
            }
            else -> resp(404, """{"error":"not_found"}""")
        }
    }

    private fun api(auth: MemberAuth = MemberAuth.Bearer("guid-1")) =
        MemberApiClient(server.url("/").toString(), server.url(MemberApiClient.MANIFEST_PATH).toString(), OkHttpClient(), auth, sleep = { })

    /** A minimal device: what :core:vault's VaultDevice does for the alternate channel. */
    private inner class Party : AltParty {
        var state = AltState()
        var vaultId: String? = null
        var pending: PendingUnlock? = null
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
        ): SealedRequest {
            val b = AltRequests.buildEnroll(userGuid, pin, enclave, manifest, attester, app, "v4.public.open", state, Instant.now())
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
            return b
        }

        override suspend fun openUnlockResult(raw: ByteArray): UnlockResult {
            val p = pending!!
            val r = UnlockResult.parse(AltResults.open(raw, kem, AltResults.TYPE_UNLOCK_RESULT, p.requestId))
            state = AltRequests.applyUnlock(state, p, r)
            return r
        }
    }

    private fun flow(
        api: MemberApiClient = api()) = AltChannelFlow(api,
        AltTrust(emptyList(), listOf(TestSupport.manifestKey)),
    ) { d, _, m, enroll, _ ->
        val e = enclaves.first()
        assertTrue(d.contentEquals(e.descriptor))
        if (enroll) assertNotNullRelease(m)
        e.verified(m)
    }

    private fun assertNotNullRelease(m: ReleaseManifest) = assertTrue(m.byPcr0(TestSupport.pcr0) != null)

    @Test
    fun enrollUnlockLock() = runBlocking<Unit> {
        val p = Party()
        val f = flow()
        val out = f.enroll(p, "guid-1", "246802", TestSupport.SoftAttester())
        assertTrue(out.ok)
        assertEquals("0123456789abcdef0123456789abcdef", out.vaultId)
        assertEquals("inst-a", out.instanceId)
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

    @Test
    fun reSealsAfterInstanceMoved() = runBlocking<Unit> {
        enclaves.add(TestSupport.Enclave("inst-b"))
        instanceMovedFirst = true
        val out = flow().enroll(Party(), "guid-1", "246802", TestSupport.SoftAttester())
        assertTrue(out.ok)
        assertEquals("inst-b", out.instanceId)
        assertEquals(2, log.count { it == "POST /api/vault/enroll" })
    }

    @Test
    fun refetchesTheManifestOnceAfterAManifestResult() = runBlocking<Unit> {
        manifestFailFirst = true
        val out = flow().enroll(Party(), "guid-1", "246802", TestSupport.SoftAttester())
        assertTrue(out.ok)
        assertEquals(2, log.count { it == "GET ${MemberApiClient.MANIFEST_PATH}" })
    }

    @Test
    fun refusesAnOlderManifest() = runBlocking<Unit> {
        val p = Party()
        p.state = AltState(manifestSerial = 9)
        val e = assertThrows(AltRefusedException::class.java) { runBlocking { flow().enroll(p, "guid-1", "246802", TestSupport.SoftAttester()) } }
        assertEquals(AltRefusedException.Reason.MANIFEST_OLDER, e.reason)
    }

    @Test
    fun apiErrorsCarryTheSpecCode() = runBlocking<Unit> {
        val e = assertThrows(MemberApiException::class.java) { runBlocking { api(MemberAuth.Bearer("someone-else")).enclave() } }
        assertEquals(401, e.status)
        assertEquals(MemberApiException.UNAUTHORIZED, e.code)
    }

    @Test
    fun sessionCookiesCsrfAndRefresh() = runBlocking<Unit> {
        val jar = SessionCookieJar()
        val a = api(MemberAuth.Session(jar))
        assertEquals(SignInStatus.PIN_REQUIRED, a.authVerify("a@b.c", "tok"))
        assertEquals(SignInStatus.SIGNED_IN, a.authPin("1234"))
        assertTrue(jar.hasSession())
        // vid_id=id1 is refused; the client refreshes once and retries with id2.
        val me = a.me()
        assertEquals("guid-1", me.userGuid)
        assertEquals(1, refreshed)
        jar.clear()
        assertFalse(jar.hasSession())
    }

    @Test
    fun cookiesPersist() {
        val saved = mutableListOf<String>()
        val p = object : CookiePersistence {
            override fun load() = saved.toList()

            override fun save(cookies: List<String>) {
                saved.clear()
                saved.addAll(cookies)
            }
        }
        val jar = SessionCookieJar(p)
        val url = server.url("/api/auth/pin")
        jar.saveFromResponse(url, listOf(okhttp3.Cookie.parse(url, "vid_id=x; Path=/api; HttpOnly; Max-Age=3600")!!))
        val again = SessionCookieJar(p)
        assertTrue(again.hasSession())
        assertEquals("x", again.loadForRequest(server.url("/api/vault/status")).single().value)
        assertTrue(again.loadForRequest(server.url("/other")).isEmpty())
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
}
