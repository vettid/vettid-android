package com.vettid.core.calls

import com.vettid.core.crypto.call.CallMedia
import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.vault.CallShareFaults
import com.vettid.core.vault.IncomingCall
import com.vettid.core.vault.MadeByCallApi
import com.vettid.core.vault.VaultApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration

/**
 * The gateway's check of the signalling faults (CALLS-PLAN §13.4 step 8): outside a call-test build any fault is
 * refused before the vault is asked for anything; a gateway with no faults is the same gateway.
 */
@OptIn(MadeByCallApi::class)
class GatewayFaultsTest {
    @Test
    fun aBuildThatIsNotATestBuildRefusesEverySignallingFaultBeforeAskingTheVault() = runTest {
        var asked = 0
        val api: suspend () -> VaultApi = { asked++; error("the vault must not be asked") }
        for (f in listOf(CallShareFaults(wrongConfirm = true), CallShareFaults(suitesAfterSigning = true))) {
            val g = VaultCallGateway(api, emptyFlow(), testBuild = false).withFaults { f }
            val e = runCatching { g.start("conn-1", "v=0\r\n", MediaSuite.DEFAULT, null) }.exceptionOrNull()
            assertEquals("$f", IllegalStateException::class.java, e?.javaClass)
            assertEquals("$f: the vault was asked", 0, asked)
        }
    }

    private fun incoming() = IncomingCall(
        CALL_ID, "conn-1", "audio", "v=0\r\n", T0.plusSeconds(45), Duration.ofSeconds(45),
        CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList()),
        CallMedia.Negotiated(MediaSuite.AES_128_GCM, listOf(1)),
        ByteArray(1216),
    )

    @Test
    fun anAnswerIsRefusedTooOutsideATestBuild() = runTest {
        var asked = 0
        val api: suspend () -> VaultApi = { asked++; error("the vault must not be asked") }
        val call = IncomingCall(
            CALL_ID, "conn-1", "audio", "v=0\r\n", T0.plusSeconds(45), Duration.ofSeconds(45),
            CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList()),
            CallMedia.Negotiated(MediaSuite.AES_128_GCM, listOf(1)),
            ByteArray(1216),
        )
        val g = VaultCallGateway(api, emptyFlow(), testBuild = false).withFaults { CallShareFaults(wrongConfirm = true) }
        val e = runCatching { g.answer(call, "v=0\r\n", keepalive = true) }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, e?.javaClass)
        assertEquals(0, asked)
    }

    /** A call-test build's gateway hands `CallApi` its own `testBuild` and the faults of the moment. */
    @Test
    fun aTestBuildsGatewayPassesItsTestBuildAndTheFaultsOn() = runTest {
        val seen = mutableListOf<String>()
        val ops = object : CallOps() {
            override suspend fun start(
                api: suspend () -> VaultApi, connectionId: String, sdp: String, suites: Collection<MediaSuite>,
                peerIk: ByteArray?, faults: CallShareFaults, testBuild: Boolean, onCallId: (String) -> Unit,
                wanted: () -> Boolean,
            ): com.vettid.core.vault.OutgoingCall {
                seen += "start $testBuild $faults"
                throw IllegalArgumentException("stop here")
            }

            override suspend fun answer(
                api: suspend () -> VaultApi,
                call: IncomingCall,
                sdp: String,
                keepalive: Boolean,
                faults: CallShareFaults,
                testBuild: Boolean,
            ): com.vettid.core.vault.CallKeys {
                seen += "answer $testBuild $faults"
                throw IllegalArgumentException("stop here")
            }
        }
        var f = CallShareFaults(suitesAfterSigning = true)
        for (testBuild in listOf(true, false)) {
            val g = VaultCallGateway({ error("not asked") }, emptyFlow(), { _, _ -> }, testBuild, { CallShareFaults.NONE }, ops)
                .withFaults { f }
            f = if (testBuild) CallShareFaults(suitesAfterSigning = true) else CallShareFaults.NONE
            runCatching { g.start("conn-1", "v=0\r\n", MediaSuite.DEFAULT, ByteArray(32)) }
            f = if (testBuild) CallShareFaults(wrongConfirm = true) else CallShareFaults.NONE
            runCatching { g.answer(incoming(), "v=0\r\n", keepalive = true) }
        }
        assertEquals(
            listOf(
                "start true ${CallShareFaults(suitesAfterSigning = true)}",
                "answer true ${CallShareFaults(wrongConfirm = true)}",
                "start false ${CallShareFaults.NONE}",
                "answer false ${CallShareFaults.NONE}",
            ),
            seen,
        )
    }

    /**
     * (0.97.0) Through the real signalling and gateway, a start hands `CallApi` the machine's [onCallId] and its wanted:
     * the early end and the re-sends' stop depend on both arriving.
     */
    @Test
    fun aStartPassesItsCallIdHookAndItsWantedThrough() = runTest {
        var wantedSeen: Boolean? = null
        val ops = object : CallOps() {
            override suspend fun start(
                api: suspend () -> VaultApi, connectionId: String, sdp: String, suites: Collection<MediaSuite>,
                peerIk: ByteArray?, faults: CallShareFaults, testBuild: Boolean, onCallId: (String) -> Unit,
                wanted: () -> Boolean,
            ): com.vettid.core.vault.OutgoingCall {
                onCallId("01JB2Z6V9K3M4N5P6Q7R8S9T00")
                wantedSeen = wanted()
                throw IllegalArgumentException("stop here")
            }
        }
        val g = VaultCallGateway({ error("not asked") }, emptyFlow(), { _, _ -> }, false, { CallShareFaults.NONE }, ops)
        for (wanted in listOf(true, false)) {
            val told = mutableListOf<String>()
            val s = VaultCallSignalling(g, { "fp" }, listOf(MediaSuite.AES_128_GCM), vaultIsC2 = { false })
            runCatching { s.start("conn-1", "v=0\r\n", listOf(MediaSuite.AES_128_GCM), { wanted }) { told += it } }
            assertEquals(listOf("01JB2Z6V9K3M4N5P6Q7R8S9T00"), told)
            assertEquals(wanted, wantedSeen)
        }
    }

    /**
     * (0.97.0) The signalling tells the machine a `call_id` that is no call: one the vault refused (each of a form 2
     * retry's two), and one of which nothing was sent (unwanted before its first send, held by the lock gate); never one
     * that may have reached the vault (no answer, the relay).
     */
    @Test
    fun anIdThatIsNoCallIsToldAsNotPlaced() = runTest {
        val ids = ArrayDeque(listOf("01JB2Z6V9K3M4N5P6Q7R8S9T01", "01JB2Z6V9K3M4N5P6Q7R8S9T02"))
        var failure: () -> Exception = { error("set below") }
        val ops = object : CallOps() {
            override suspend fun start(
                api: suspend () -> VaultApi, connectionId: String, sdp: String, suites: Collection<MediaSuite>,
                peerIk: ByteArray?, faults: CallShareFaults, testBuild: Boolean, onCallId: (String) -> Unit,
                wanted: () -> Boolean,
            ): com.vettid.core.vault.OutgoingCall {
                onCallId(ids.removeFirst().also { ids.addLast(it) })
                throw failure()
            }
        }
        val g = VaultCallGateway({ error("not asked") }, emptyFlow(), { _, _ -> }, false, { CallShareFaults.NONE }, ops)
        val cases = listOf<Pair<() -> Exception, Boolean>>(
            { com.vettid.core.vault.VaultOpException("call.start", "busy", "", null) } to true,
            { com.vettid.core.vault.VaultStateException("not sent", "call.start", com.vettid.core.vault.VaultDevice.CODE_NOT_SENT) } to true,
            { com.vettid.core.vault.VaultStateException("held", "call.start", com.vettid.core.vault.VaultDevice.CODE_LOCK_PENDING) } to true,
            { com.vettid.core.vault.VaultStateException("no response", "call.start") } to false,
            { com.vettid.core.vault.VaultStateException("unwanted", "call.start", com.vettid.core.vault.VaultDevice.CODE_NOT_WANTED) } to false,
            { java.io.IOException("relay") } to false,
        )
        for ((f, isNoCall) in cases) {
            failure = f
            val told = mutableListOf<String>()
            val notPlaced = mutableListOf<String>()
            val s = VaultCallSignalling(g, { "fp" }, listOf(MediaSuite.AES_128_GCM), vaultIsC2 = { false })
            runCatching {
                s.start("conn-1", "v=0\r\n", listOf(MediaSuite.AES_128_GCM), wanted = { true }, onCallId = { told += it }, notPlaced = { notPlaced += it })
            }
            assertEquals("${f()}", if (isNoCall) told else emptyList<String>(), notPlaced)
        }

        // A form 2 start answered `bad_request`, and its retry refused too: both ids are no call.
        val answers = ArrayDeque(listOf("bad_request", "busy"))
        failure = { com.vettid.core.vault.VaultOpException("call.start", answers.removeFirst(), "", null) }
        val told = mutableListOf<String>()
        val notPlaced = mutableListOf<String>()
        val c2 = VaultCallSignalling(g, { "fp" }, listOf(MediaSuite.AES_128_GCM), peerKey = { ByteArray(32) { 7 } }, vaultIsC2 = { true })
        runCatching {
            c2.start("conn-1", "v=0\r\n", listOf(MediaSuite.AES_128_GCM), wanted = { true }, onCallId = { told += it }, notPlaced = { notPlaced += it })
        }
        assertEquals(2, told.toSet().size)
        assertEquals(told, notPlaced)
    }

    /**
     * (0.97.0) `CallOps.start` hands the machine's `wanted` on to `callStart` as its `stillWanted`: a call no longer wanted
     * when its first copy would go sends nothing (a real device, never paired, so anything sent would fail otherwise),
     * and `callStart` sends no hang-up for it.
     */
    @Test
    fun callOpsStartPassesWantedOnToCallStart() = kotlinx.coroutines.runBlocking {
        // A relay that registers the mailbox and records anything else.
        val other = mutableListOf<String>()
        val relay = okhttp3.Interceptor { chain ->
            val path = chain.request().url.encodedPath
            val registered = path == "/v1/register"
            if (!registered) synchronized(other) { other += path }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (registered) 201 else 500).message("test")
                .body(
                    (if (registered) REGISTERED else "{}").toResponseBody("application/json".toMediaType()),
                ).build()
        }
        val cfg = com.vettid.core.vault.DeviceConfig(
            name = "phone", relayUrl = "http://relay.test", http = okhttp3.OkHttpClient.Builder().addInterceptor(relay).build(),
            store = com.vettid.core.vault.InMemoryDeviceStateStore(),
        )
        val d = com.vettid.core.vault.VaultDevice.create(cfg, com.vettid.core.vault.DeviceSecrets.generate())
        val api = VaultApi(d)
        val sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n"
        val told = mutableListOf<String>()
        val e = runCatching {
            CallOps().start({ api }, "conn-1", sdp, MediaSuite.DEFAULT, null, CallShareFaults.NONE, false, { told += it }, { false })
        }.exceptionOrNull()
        assertEquals(1, told.size)
        assertEquals("$e", com.vettid.core.vault.VaultDevice.CODE_NOT_SENT, (e as? com.vettid.core.vault.VaultStateException)?.code)
        assertEquals("nothing sent, no hang-up", emptyList<String>(), synchronized(other) { other.toList() })
    }

    @Test
    fun noFaultsGoThroughToTheVault() = runTest {
        var asked = 0
        val api: suspend () -> VaultApi = { asked++; throw IllegalArgumentException("stop here") }
        val g = VaultCallGateway(api, emptyFlow(), testBuild = false).withFaults { CallShareFaults.NONE }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { g.start("conn-1", "v=0\r\n", MediaSuite.DEFAULT, null) }
        }
        assertEquals(1, asked)
    }

    private companion object {
        const val REGISTERED = """{"mailbox_id":"x","limits":{"max_payload_bytes":262144,"message_ttl_seconds":1,""" +
            """"visibility_timeout_seconds":60,"max_token_lifetime_seconds":1,"open_token_max_lifetime_seconds":1,""" +
            """"max_claim_bytes":1,"claim_ttl_seconds":1}}"""
    }
}
