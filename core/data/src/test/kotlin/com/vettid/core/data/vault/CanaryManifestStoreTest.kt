package com.vettid.core.data.vault

import com.vettid.core.attestation.manifest.ManifestKey
import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.vault.InMemoryDeviceStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Installing, refusing, retiring and removing a canary manifest (VAULT-RELEASES §10.1 step 9). */
class CanaryManifestStoreTest {
    private val pinned = newKey()
    private val other = newKey()
    private val bytes = InMemoryDeviceStateStore()
    private var seenSerial = 0L
    private var publishedDoc: ByteArray? = null
    private var publishedFails = false

    private val store = CanaryManifestStore(
        bytes,
        verifier = { ManifestVerifier(listOf(ManifestKey(pinned.public.encoded))) },
        seen = { seenSerial },
        published = { if (publishedFails) throw IOException("offline") else publishedDoc },
    )

    private fun newKey(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun manifest(serial: Long): ByteArray {
        val pcr = { b: String -> b.repeat(48) }
        val e = """{"release":3,"pcr0":"${pcr("a3")}","pcr1":"${pcr("13")}","pcr2":"${pcr("23")}",""" +
            """"seal_key":"arn:aws:kms:us-east-1:111122223333:key/test","status":"active",""" +
            """"published_at":"2026-10-01T00:00:00Z","notes":"https://vettid.org/security/releases/3/"}"""
        return """{"v":1,"serial":$serial,"issued_at":"2026-10-01T00:00:00Z","releases":[$e]}""".toByteArray()
    }

    private fun served(serial: Long, key: KeyPair = pinned): ByteArray {
        val m = manifest(serial)
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(ReleaseManifest.LABEL.toByteArray())
            update(0)
            update(m)
            sign()
        }
        val b64 = Base64.getEncoder()
        val keyId = ManifestKey(key.public.encoded).keyId
        return """{"manifest":"${b64.encodeToString(m)}","sig":"${b64.encodeToString(derToRaw(der))}","key_id":"$keyId"}""".toByteArray()
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        fun int(): ByteArray {
            val n = der[i + 1].toInt()
            val v = der.copyOfRange(i + 2, i + 2 + n).dropWhile { it == 0.toByte() }.toByteArray()
            i += 2 + n
            return ByteArray(32 - v.size) + v
        }
        return int() + int()
    }

    private suspend fun refusedCode(served: ByteArray): String? = try {
        store.installCanaryManifest(served)
        fail("installed")
        null
    } catch (e: VaultFailure) {
        assertEquals(FailureKind.MANIFEST, e.kind)
        e.code
    }

    @Test
    fun installsAVerifiedNewerDocument() = runTest {
        publishedDoc = served(7)
        seenSerial = 7
        val doc = served(8)
        val v = store.installCanaryManifest(doc)
        assertEquals(8, v.serial)
        assertEquals(ManifestKey(pinned.public.encoded).keyId, v.keyId)
        assertEquals(listOf(3L), v.releases.map { it.number })
        assertArrayEquals(doc, bytes.load())
        assertEquals(v, store.canaryManifest.value)
        assertArrayEquals(doc, store.served())
    }

    @Test
    fun installsBeforeAnythingIsPublished() = runTest {
        publishedDoc = null
        assertEquals(1, store.installCanaryManifest(served(1)).serial)
        publishedFails = true // the published one cannot be read: the canary is still checked on its own
        assertEquals(2, store.installCanaryManifest(served(2)).serial)
    }

    @Test
    fun refusesAndKeepsNothing() = runTest {
        publishedDoc = served(8)
        seenSerial = 5
        assertEquals(CanaryManifestRepository.CODE_SIGNATURE, refusedCode(served(9, other)))
        assertEquals(CanaryManifestRepository.CODE_OLDER, refusedCode(served(4)))
        assertEquals(CanaryManifestRepository.CODE_PUBLISHED, refusedCode(served(8)))
        assertEquals(CanaryManifestRepository.CODE_FORMAT, refusedCode("{}".toByteArray()))
        assertEquals(CanaryManifestRepository.CODE_TOO_LARGE, refusedCode(ByteArray(ReleaseManifest.MAX_SERVED + 1)))
        assertNull(bytes.load())
        assertNull(store.canaryManifest.value)
    }

    @Test
    fun checkingInstallsNothing() = runTest {
        assertEquals(3, store.checkCanaryManifest(served(3)).serial)
        assertNull(bytes.load())
        assertNull(store.canaryManifest.value)
    }

    @Test
    fun retireForgetsOnlyThatDocument() = runTest {
        val first = served(3)
        store.installCanaryManifest(first)
        val second = served(4)
        store.installCanaryManifest(second)
        store.retire(first) // an older document: the installed one stays
        assertArrayEquals(second, store.served())
        store.retire(second)
        assertNull(store.served())
        assertNull(store.canaryManifest.value)
    }

    @Test
    fun removeAndLoad() = runTest {
        store.installCanaryManifest(served(3))
        store.removeCanaryManifest()
        assertNull(store.served())
        assertNull(store.canaryManifest.value)
        // At start: a stored document that verifies is shown; one that does not is dropped.
        bytes.save(served(5))
        store.load()
        assertEquals(5L, store.canaryManifest.value?.serial)
        bytes.save(served(6, other))
        store.load()
        assertNull(store.canaryManifest.value)
        assertNull(bytes.load())
    }

    @Test
    fun readsAtMostOneByteMoreThanAServedManifest() {
        val big = ByteArray(ReleaseManifest.MAX_SERVED * 2)
        assertEquals(ReleaseManifest.MAX_SERVED + 1, CanaryManifestInbox.readBounded(ByteArrayInputStream(big)).size)
        assertEquals(10, CanaryManifestInbox.readBounded(ByteArrayInputStream(ByteArray(10))).size)
    }
}
