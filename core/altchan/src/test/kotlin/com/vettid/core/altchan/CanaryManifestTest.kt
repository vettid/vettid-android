package com.vettid.core.altchan

import com.vettid.core.attestation.AttestationException
import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * The canary manifest (VAULT-RELEASES §10.1 step 9, W10-READINESS P31/B5): verified like the published one
 * (pinned key, signature, strict format, serial rule) and used only while it is newer than the published one.
 */
class CanaryManifestTest {
    private val verifier = ManifestVerifier(listOf(TestSupport.manifestKey))
    private val trust = AltTrust(emptyList(), listOf(TestSupport.manifestKey))
    private lateinit var server: MockWebServer

    /** The published serial; null: nothing is published yet (404). */
    private var publishedSerial: Long? = null

    private fun served(serial: Long, number: Long = 3, status: String = "active") =
        TestSupport.served(TestSupport.manifestBytes(serial, number, status = status))

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val s = publishedSerial ?: return MockResponse.Builder().code(404).body("not found").build()
                return MockResponse.Builder().code(200).body(String(served(s))).build()
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private class Source(var doc: ByteArray?) : CanaryManifestSource {
        val retired = mutableListOf<ByteArray>()

        override fun served(): ByteArray? = doc

        override fun retire(served: ByteArray) {
            retired.add(served)
            if (doc?.contentEquals(served) == true) doc = null
        }
    }

    private fun flow(src: Source?): AltChannelFlow {
        val manifestUrl = server.url(MemberApiClient.MANIFEST_PATH).toString()
        val api = MemberApiClient(server.url("/").toString(), manifestUrl, OkHttpClient(), MemberAuth.Bearer("g"))
        return AltChannelFlow(api, trust, verifyEnclave = { _, _, _, _, _ -> error("not used") }, canary = src)
    }

    // --- installation checks ---

    @Test
    fun aSignedNewerCanaryIsAccepted() {
        val m = CanaryManifests.check(verifier, served(8), seen = 7, published = 7)
        assertEquals(8, m.serial)
        assertEquals(8, CanaryManifests.check(verifier, served(8), seen = 0, published = null).serial)
    }

    @Test
    fun aKeyThisBuildDoesNotPinIsRefused() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val doc = TestSupport.served(TestSupport.manifestBytes(8), other)
        val e = assertThrows(AttestationException::class.java) { CanaryManifests.check(verifier, doc, 0, null) }
        assertEquals(AttestationException.Kind.MANIFEST_KEY, e.kind)
    }

    @Test
    fun aForgedSignatureUnderThePinnedKeyIdIsRefused() {
        // Signed by another key but naming the pinned key_id: the signature does not verify.
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val forged = StrictJson.parseObject(TestSupport.served(TestSupport.manifestBytes(8), other))
        val doc = JsonBuilder().string("manifest", forged.string("manifest")).string("sig", forged.string("sig"))
            .string("key_id", TestSupport.manifestKey.keyId).bytes()
        val e = assertThrows(AttestationException::class.java) { CanaryManifests.check(verifier, doc, 0, null) }
        assertEquals(AttestationException.Kind.SIGNATURE, e.kind)
    }

    @Test
    fun aManifestChangedAfterSigningIsRefused() {
        val good = StrictJson.parseObject(served(8))
        val changed = TestSupport.manifestBytes(9) // other bytes under the old signature
        val doc = JsonBuilder().string("manifest", Base64s.encodeStd(changed)).string("sig", good.string("sig"))
            .string("key_id", good.string("key_id")).bytes()
        val e = assertThrows(AttestationException::class.java) { CanaryManifests.check(verifier, doc, 0, null) }
        assertEquals(AttestationException.Kind.SIGNATURE, e.kind)
    }

    @Test
    fun malformedDocumentsAreRefused() {
        for (doc in listOf("{}".toByteArray(), "not json".toByteArray(), ByteArray(0), ByteArray(90_113) { 0x20 })) {
            val e = assertThrows(AttestationException::class.java) { CanaryManifests.check(verifier, doc, 0, null) }
            assertEquals(AttestationException.Kind.FORMAT, e.kind)
        }
    }

    @Test
    fun aSerialBelowTheOneSeenIsRefused() {
        val e = assertThrows(CanaryManifestException::class.java) { CanaryManifests.check(verifier, served(6), seen = 7, published = null) }
        assertEquals(CanaryManifestException.Reason.OLDER, e.reason)
        // Equal to the one seen: allowed (the same document again).
        assertEquals(7, CanaryManifests.check(verifier, served(7), seen = 7, published = null).serial)
    }

    @Test
    fun aSerialAlreadyPublishedIsRefused() {
        for (p in listOf(8L, 9L)) {
            val e = assertThrows(CanaryManifestException::class.java) {
                CanaryManifests.check(verifier, served(8), seen = 0, published = p)
            }
            assertEquals(CanaryManifestException.Reason.PUBLISHED, e.reason)
        }
    }

    @Test
    fun theHigherSerialIsChosen() {
        val p = verifier.verify(served(7))
        val c = verifier.verify(served(8))
        assertEquals(8, CanaryManifests.choose(p, c)!!.serial)
        assertEquals(8, CanaryManifests.choose(null, c)!!.serial)
        assertEquals(7, CanaryManifests.choose(p, null)!!.serial)
        assertNull(CanaryManifests.choose(null, null))
        val same = verifier.verify(served(8, number = 4))
        assertTrue(CanaryManifests.choose(same, c) === same) // equal serials: the published one
    }

    // --- use by the alternate channel ---

    @Test
    fun beforeAnythingIsPublishedTheCanaryIsUsed() = runBlocking<Unit> {
        publishedSerial = null // production before release 1: 404
        val src = Source(served(1, number = 1))
        val m = flow(src).manifest(0)
        assertEquals(1, m.serial)
        assertTrue(src.retired.isEmpty())
    }

    @Test
    fun withoutACanaryNothingPublishedStaysAnError() = runBlocking<Unit> {
        publishedSerial = null
        val e = assertThrows(MemberApiException::class.java) { runBlocking { flow(null).manifest(0) } }
        assertEquals(MemberApiException.MANIFEST_UNAVAILABLE, e.code)
        assertThrows(MemberApiException::class.java) { runBlocking { flow(Source(null)).manifest(0) } }
    }

    @Test
    fun aNewerCanaryWinsOverThePublishedManifest() = runBlocking<Unit> {
        publishedSerial = 7
        val src = Source(served(8, number = 4))
        assertEquals(8, flow(src).manifest(7).serial)
        assertTrue(src.retired.isEmpty())
    }

    @Test
    fun theCanaryIsRetiredOncePublished() = runBlocking<Unit> {
        for (p in listOf(8L, 9L)) {
            publishedSerial = p
            val doc = served(8, number = 4)
            val src = Source(doc)
            assertEquals(p, flow(src).manifest(8).serial)
            assertNull(src.doc)
            assertArrayEquals(doc, src.retired.single())
        }
    }

    @Test
    fun theSerialRuleAppliesToTheCanary() = runBlocking<Unit> {
        publishedSerial = null
        val e = assertThrows(AltRefusedException::class.java) { runBlocking { flow(Source(served(5))).manifest(6) } }
        assertEquals(AltRefusedException.Reason.MANIFEST_OLDER, e.reason)
    }

    @Test
    fun aCanaryThatNoLongerVerifiesIsRetiredAndIgnored() = runBlocking<Unit> {
        publishedSerial = 7
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val bad = TestSupport.served(TestSupport.manifestBytes(9), other)
        val src = Source(bad)
        assertEquals(7, flow(src).manifest(0).serial)
        assertNull(src.doc)
        // ...and with nothing published, it is not a fallback either.
        publishedSerial = null
        val e = assertThrows(MemberApiException::class.java) { runBlocking { flow(Source(bad)).manifest(0) } }
        assertEquals(MemberApiException.MANIFEST_UNAVAILABLE, e.code)
    }

    @Test
    fun aPublishedManifestThatFailsVerificationIsNotMaskedByTheCanary() = runBlocking<Unit> {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).body(String(TestSupport.served(TestSupport.manifestBytes(9), other))).build()
        }
        assertThrows(AttestationException::class.java) { runBlocking { flow(Source(served(8))).manifest(0) } }
    }
}
