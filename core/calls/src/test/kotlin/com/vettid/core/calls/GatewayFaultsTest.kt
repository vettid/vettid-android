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
}
