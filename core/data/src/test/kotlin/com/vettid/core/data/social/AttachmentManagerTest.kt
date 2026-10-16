// Fake answers stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.ClaimBlob
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.media.PreparedPhoto
import com.vettid.core.relay.RelayException
import com.vettid.core.vault.BlobRef
import com.vettid.core.vault.FetchedAttachment
import com.vettid.core.vault.SentAttachment
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sending, retrying, cancelling and fetching photos (VAULT-MESSAGING 0.32.0 §10.5.1, ANDROID-PLAN 0.1.39). */
class AttachmentManagerTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(500) { it.toByte() }
    private val thumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()) + ByteArray(50)

    private fun photo() = PreparedPhoto(jpeg.copyOf(), thumb.copyOf(), 2_560, 1_920)

    private class FakeOps : AttachmentOps {
        val calls = mutableListOf<String>()
        val uploads = mutableListOf<ByteArray>()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val sendFailures = ArrayDeque<Exception>()
        var uploadFailure: Exception? = null
        var supported: Boolean? = true
        var served: ByteArray = ByteArray(0)
        var servedRef: BlobRef? = null
        var seq = 0

        override suspend fun supported() = supported
        override fun newRequestId() = "REQ${++seq}"
        override suspend fun upload(blob: ByteArray): String {
            calls += "upload"
            uploadFailure?.let { uploadFailure = null; throw it }
            uploads += blob.copyOf()
            return "blob${uploads.size}"
        }
        override suspend fun send(connectionId: String, text: String?, attachment: JsonObject, requestId: String): SentAttachment {
            calls += "send:$requestId"
            requests += requestId to attachment
            sendFailures.removeFirstOrNull()?.let { throw it }
            return SentAttachment("M1", "2026-10-15T10:00:00Z", "ATT1")
        }
        override suspend fun discard(blobId: String) {
            calls += "discard:$blobId"
        }
        override suspend fun thumb(connectionId: String, messageId: String): ByteArray {
            calls += "thumb:$messageId"
            return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
        }
        override suspend fun content(connectionId: String, messageId: String): FetchedAttachment {
            calls += "content:$messageId"
            return FetchedAttachment(servedRef!!, "2026-10-22T10:00:00Z")
        }
        override suspend fun fetchBlob(blobId: String): ByteArray {
            calls += "fetch:$blobId"
            return served.copyOf()
        }
        override suspend fun deleteBlob(blobId: String) {
            calls += "delete:$blobId"
        }

        fun serve(content: ByteArray, flip: Boolean = false, sizeOff: Long = 0) {
            val s = ClaimBlob.seal(content)
            served = s.blob.copyOf().also { if (flip) it[30] = (it[30].toInt() xor 1).toByte() }
            servedRef = BlobRef("B9", Base64s.encodeStd(s.key), Base64s.encodeStd(s.sha256), content.size + sizeOff, "image/png", "../x.png")
        }
    }

    private val ops = FakeOps()
    private val sent = mutableListOf<MessageInfo>()
    private val m = AttachmentManager(ops, onSent = { sent += it })

    @Test
    fun theProbeReadsReleaseAFromTheAnswer() = runTest {
        m.probe()
        assertTrue(m.available.value)
        ops.supported = false
        m.probe()
        assertFalse(m.available.value)
        ops.supported = null
        m.probe()
        assertFalse(m.available.value)
    }

    @Test
    fun aPhotoIsUploadedSealedAndSentWithItsReference() = runTest {
        val p = m.begin("c1", "caption", photo())
        val msg = m.send(p)
        assertEquals(listOf("upload", "send:REQ1"), ops.calls)
        val att = ops.requests.single().second
        val blob = att["blob"]!!.jsonObject
        assertEquals("blob1", blob["id"]!!.jsonPrimitive.content)
        assertNull("apps send no name for a photo", blob["name"])
        assertEquals("photo", att["kind"]!!.jsonPrimitive.content)
        assertEquals("2560", att["width"]!!.jsonPrimitive.content)
        // What was uploaded opens with the reference sent.
        val opened = ClaimBlob.open(
            ops.uploads.single(),
            Base64s.decodeStd(blob["key"]!!.jsonPrimitive.content),
            Base64s.decodeStd(blob["sha256"]!!.jsonPrimitive.content),
            blob["size"]!!.jsonPrimitive.content.toLong(),
        )
        assertArrayEquals(jpeg, opened)
        assertArrayEquals(thumb, Base64s.decodeStd(att["thumb"]!!.jsonPrimitive.content))
        assertEquals("M1", msg.messageId)
        assertEquals(AttachmentState.PREPARING, msg.attachment!!.state)
        assertEquals(listOf(msg), sent)
        assertArrayEquals("the thumbnail is shown from memory", thumb, m.cachedThumbnail("c1", "M1"))
        assertTrue("prepared bytes dropped after the answer", p.thumbnail.all { it == 0.toByte() })
    }

    @Test
    fun retryReSendsTheSameInnerIdAndMakesNoUpload() = runTest {
        ops.sendFailures += VaultFailure(FailureKind.NO_RESPONSE)
        ops.sendFailures += VaultOpException("message.send", "busy")
        val p = m.begin("c1", "", photo())
        assertEquals(FailureKind.NO_RESPONSE, (runCatching { m.send(p) }.exceptionOrNull() as VaultFailure).kind)
        assertTrue(p.uploaded)
        val busy = runCatching { m.send(p) }.exceptionOrNull() as VaultFailure
        assertEquals("busy", busy.code)
        m.send(p)
        assertEquals(1, ops.calls.count { it == "upload" })
        assertEquals(listOf("send:REQ1", "send:REQ1", "send:REQ1"), ops.calls.filter { it.startsWith("send") })
        assertNull("no caption: text absent", ops.requests.first().second["text"])
    }

    @Test
    fun cancelAfterTheUploadAsksTheVaultToDiscardIt() = runTest {
        ops.sendFailures += VaultFailure(FailureKind.NO_RESPONSE)
        val p = m.begin("c1", "", photo())
        runCatching { m.send(p) }
        m.cancel(p)
        assertEquals("discard:blob1", ops.calls.last())
        assertTrue(p.thumbnail.all { it == 0.toByte() })
    }

    @Test
    fun cancelBeforeAnyUploadSendsNothing() = runTest {
        val p = m.begin("c1", "", photo())
        m.cancel(p)
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun aFullMailboxIsNotRetriedUntilSendIsTappedAgain() = runTest {
        ops.uploadFailure = RelayException(409, RelayException.QUOTA_EXCEEDED, "full")
        val p = m.begin("c1", "", photo())
        val e = runCatching { m.send(p) }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.LIMIT, e.kind)
        assertEquals(AttachmentManager.CODE_QUOTA, e.code)
        assertFalse(p.uploaded)
        assertEquals(listOf("upload"), ops.calls)
        m.send(p) // the member taps Send again
        assertEquals(listOf("upload", "upload", "send:REQ1"), ops.calls)
    }

    @Test
    fun aFinalRefusalEndsThePendingPhoto() = runTest {
        ops.sendFailures += VaultOpException("message.send", "limit")
        val p = m.begin("c1", "", photo())
        val e = runCatching { m.send(p) }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.LIMIT, e.kind)
        assertTrue("wiped", p.thumbnail.all { it == 0.toByte() })
    }

    @Test
    fun aFetchedPhotoIsCheckedDecryptedDeletedAndCached() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100) { 7 }
        ops.serve(png)
        val got = m.photo("c1", "M7")
        assertArrayEquals(png, got.bytes)
        assertEquals(com.vettid.core.media.ImageType.PNG, got.type)
        assertEquals(listOf("content:M7", "fetch:B9", "delete:B9"), ops.calls)
        m.photo("c1", "M7")
        assertEquals("from memory the second time", 3, ops.calls.size)
    }

    @Test
    fun aBadBlobIsRefusedAndStillDeleted() = runTest {
        ops.serve(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(100), flip = true)
        val e = runCatching { m.photo("c1", "M8") }.exceptionOrNull() as VaultFailure
        assertEquals(AttachmentManager.CODE_BAD, e.code)
        assertEquals("delete:B9", ops.calls.last())
        ops.calls.clear()
        ops.serve(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(100), sizeOff = 1)
        assertEquals(AttachmentManager.CODE_BAD, (runCatching { m.photo("c1", "M8") }.exceptionOrNull() as VaultFailure).code)
        assertEquals("delete:B9", ops.calls.last())
    }

    @Test
    fun aBlobThatIsNoPhotoByItsBytesIsRefused() = runTest {
        ops.serve("GIF89a not accepted".toByteArray())
        val e = runCatching { m.photo("c1", "M9") }.exceptionOrNull() as VaultFailure
        assertEquals(AttachmentManager.CODE_TYPE, e.code)
    }

    @Test
    fun clearWipesThumbnailsPhotosAndPendingUploads() = runTest {
        val p = m.begin("c1", "", photo())
        m.thumbnail("c1", "M5")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(10)
        ops.serve(png)
        val got = m.photo("c1", "M6")
        m.clear() // a lock, the hold, a wipe or an unlink
        assertNull(m.cachedThumbnail("c1", "M5"))
        assertEquals(1L, m.cleared.value)
        assertTrue(p.thumbnail.all { it == 0.toByte() })
        ops.calls.clear()
        m.thumbnail("c1", "M5")
        assertEquals(listOf("thumb:M5"), ops.calls)
    }

    @Test
    fun aThumbnailFromMessageNewIsKeptInMemory() {
        val b64 = Base64s.encodeStd(thumb)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"connection_id":"c1","message_id":"M3","direction":"in","text":"","sent_at":"2026-10-15T10:00:00Z",""" +
                """"attachment":{"att_id":"A3","kind":"photo","size":10,"width":320,"height":240,"state":"fetching","thumb":"$b64"}}""",
        ).jsonObject
        m.onEvent("message.new", body)
        assertArrayEquals(thumb, m.cachedThumbnail("c1", "M3"))
    }

    @Test
    fun aMalformedReferenceIsABadBlobNotACrash() = runTest {
        ops.serve(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(10))
        ops.servedRef = ops.servedRef!!.copy(key = "not base64!")
        assertEquals(AttachmentManager.CODE_BAD, (runCatching { m.photo("c1", "M10") }.exceptionOrNull() as VaultFailure).code)
        ops.serve(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(10))
        ops.servedRef = ops.servedRef!!.copy(sha256 = Base64s.encodeStd(ByteArray(31)))
        assertEquals(AttachmentManager.CODE_BAD, (runCatching { m.photo("c1", "M11") }.exceptionOrNull() as VaultFailure).code)
        assertEquals("delete:B9", ops.calls.last())
    }

    @Test
    fun cachesAreKeyedByConversationAndHandOutCopies() = runTest {
        val p = m.begin("c1", "", photo())
        m.send(p)
        assertNull(m.cachedThumbnail("c2", "M1"))
        val a = m.cachedThumbnail("c1", "M1")!!
        a.fill(9)
        assertArrayEquals(thumb, m.cachedThumbnail("c1", "M1"))
        val e = runCatching { m.send(p) }.exceptionOrNull() as VaultFailure
        assertEquals("already_sent", e.code)
        assertEquals(1, ops.calls.count { it == "upload" })
    }
}
