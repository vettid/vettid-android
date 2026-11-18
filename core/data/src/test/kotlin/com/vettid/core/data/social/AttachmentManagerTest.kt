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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

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
        var thumbFailure: Exception? = null
        var served: ByteArray = ByteArray(0)
        var servedRef: BlobRef? = null
        var seq = 0

        /** Run during the upload, the send, the thumbnail and the blob fetch (a clear landing then). */
        var duringUpload: (() -> Unit)? = null
        var duringSend: (() -> Unit)? = null
        var duringThumb: (() -> Unit)? = null
        var duringContent: (() -> Unit)? = null
        var duringFetch: (() -> Unit)? = null

        /** Every thumbnail served, to check that one fetched across a clear was zeroed. */
        val thumbsServed = mutableListOf<ByteArray>()

        override fun newRequestId() = "REQ${++seq}"
        override suspend fun upload(blob: ByteArray): String {
            calls += "upload"
            duringUpload?.invoke()
            uploadFailure?.let { uploadFailure = null; throw it }
            uploads += blob.copyOf()
            return "blob${uploads.size}"
        }
        override suspend fun send(connectionId: String, text: String?, attachment: JsonObject, requestId: String): SentAttachment {
            calls += "send:$requestId"
            requests += requestId to attachment
            duringSend?.invoke()
            sendFailures.removeFirstOrNull()?.let { throw it }
            return SentAttachment("M1", "2026-10-15T10:00:00Z", "ATT1")
        }
        override suspend fun discard(blobId: String) {
            calls += "discard:$blobId"
        }
        override suspend fun thumb(connectionId: String, messageId: String): ByteArray {
            calls += "thumb:$messageId"
            duringThumb?.invoke()
            thumbFailure?.let { thumbFailure = null; throw it }
            return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1).also { thumbsServed += it }
        }
        override suspend fun content(connectionId: String, messageId: String): FetchedAttachment {
            calls += "content:$messageId"
            duringContent?.invoke()
            return FetchedAttachment(servedRef!!, "2026-10-22T10:00:00Z")
        }
        override suspend fun fetchBlob(blobId: String): ByteArray {
            calls += "fetch:$blobId"
            duringFetch?.invoke()
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
    /** The vault is closed: not open on this phone, the owner check due or held, or the release gate checking or pausing. */
    @Volatile
    private var closedNow = false
    private val m = AttachmentManager(ops, onSent = { sent += it }, vaultClosed = { closedNow })

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

    // --- (ANDROID-PLAN "Memory only, wiped at lock") a clear that lands while a fetch or a send is on its way ---

    private suspend fun unavailable(block: suspend () -> Unit): VaultFailure = runCatching { block() }.exceptionOrNull() as VaultFailure

    @Test
    fun aPhotoFetchedAcrossAClearIsNotKeptAndFailsAsUnavailable() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100) { 7 }
        ops.serve(png)
        ops.duringFetch = { m.clear() }
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { m.photo("c1", "M7") }.code)
        assertEquals("the blob is still deleted", "delete:B9", ops.calls.last())
        // Nothing was cached: the next view fetches again.
        ops.duringFetch = null
        ops.serve(png)
        assertArrayEquals(png, m.photo("c1", "M7").bytes)
        assertEquals(2, ops.calls.count { it == "content:M7" })
    }

    @Test
    fun aThumbnailFetchedAcrossAClearIsZeroedAndNotKept() = runTest {
        ops.duringThumb = { m.clear() }
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { m.thumbnail("c1", "M5") }.code)
        assertTrue(ops.thumbsServed.single().all { it == 0.toByte() })
        assertNull(m.cachedThumbnail("c1", "M5"))
    }

    @Test
    fun aClearBeforeTheSendSealsAndUploadsNothing() = runTest {
        val p = m.begin("c1", "", photo())
        m.clear()
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { m.send(p) }.code)
        assertTrue(ops.calls.isEmpty())
        assertTrue(ops.uploads.isEmpty())
    }

    @Test
    fun aPhotoWipedBeforeItsSendIsNeitherSealedNorUploaded() = runTest {
        // The conversation dropped its draft (a clear it heard first, or a Cancel): the bytes are zeros.
        val prepared = photo()
        val p = m.begin("c1", "", prepared)
        prepared.wipe()
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { m.send(p) }.code)
        assertTrue(ops.uploads.isEmpty())
    }

    @Test
    fun aClearDuringTheUploadSendsNoMessage() = runTest {
        // The upload's best-effort discard runs in this test's scope, after the checks below.
        val m = AttachmentManager(ops, onSent = { sent += it }, vaultClosed = { closedNow }, scope = this)
        val p = m.begin("c1", "", photo())
        ops.duringUpload = { m.clear() }
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { m.send(p) }.code)
        assertTrue(ops.calls.none { it.startsWith("send:") })
        assertTrue(sent.isEmpty())
        // A Retry sends nothing either: the pending photo ended.
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { m.send(p) }.code)
        assertTrue(ops.calls.none { it.startsWith("send:") })
    }

    @Test
    fun anAnswerAfterAClearKeepsNothing() = runTest {
        val p = m.begin("c1", "", photo())
        ops.duringSend = { m.clear() }
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { m.send(p) }.code)
        assertTrue(sent.isEmpty())
        assertNull(m.cachedThumbnail("c1", "M1"))
        assertTrue(p.sent)
    }

    /**
     * A clear from another thread that lands while the request is built, after the last check: it waits for the request
     * to be complete, so `message.send` carries the real key and thumbnail, never zeros; the answer then keeps nothing.
     */
    @Test
    fun aClearDuringTheRequestsBuildWaitsForACompleteRequest() = runTest {
        val p = m.begin("c1", "", photo())
        // Set on the send's thread, read on the test's: an AtomicReference, never a plain var.
        val clearing = AtomicReference<Thread>()
        m.testHook = { point ->
            if (point == AttachmentManager.HOOK_BUILD) {
                val t = Thread { m.clear() }.apply { start() }
                clearing.set(t)
                // The clear is blocked until the request is built.
                t.join(200)
                assertTrue("the clear waits for the build", t.isAlive)
            }
        }
        // The answer waits for the clear to be done, so its check sees it (else the send may retake the lock first).
        ops.duringSend = { clearing.get()?.join(5_000) }
        val e = runCatching { m.send(p) }.exceptionOrNull()
        // Anything but the clear's VaultFailure (an assertion in the hook, a failure before the build) is the real cause.
        if (e !is VaultFailure) throw AssertionError("send ended ${e ?: "normally"}, not unavailable", e)
        assertNotNull("the build hook ran", clearing.get())
        clearing.get().join(5_000)
        val blobField = ops.requests.single().second["blob"]
        assertNotNull("the request carries the blob", blobField)
        val blob = blobField!!.jsonObject
        val key = Base64s.decodeStd(blob["key"]!!.jsonPrimitive.content)
        assertFalse("a complete key, not zeros", key.all { it == 0.toByte() })
        val thumbField = ops.requests.single().second["thumb"]
        assertNotNull("the request carries the thumbnail", thumbField)
        assertArrayEquals(thumb, Base64s.decodeStd(thumbField!!.jsonPrimitive.content))
        // What was sent opens with the reference sent.
        assertArrayEquals(jpeg, ClaimBlob.open(ops.uploads.single(), key, Base64s.decodeStd(blob["sha256"]!!.jsonPrimitive.content), blob["size"]!!.jsonPrimitive.content.toLong()))
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, (e as VaultFailure).code)
        assertTrue(sent.isEmpty())
    }

    /**
     * A Cancel from another thread (the screen's Cancel, or an abandon when the screen goes) that lands while the request
     * is built: it waits for the request to be complete, so `message.send` carries the real key and thumbnail, never zeros.
     * The answer still adds the message, but no thumbnail copied from the wiped photo is kept: it is fetched from the vault.
     */
    @Test
    fun aCancelDuringTheRequestsBuildWaitsForACompleteRequest() = runTest {
        val p = m.begin("c1", "", photo())
        var cancelling: Thread? = null
        m.testHook = { point ->
            if (point == AttachmentManager.HOOK_BUILD) {
                val t = Thread { kotlinx.coroutines.runBlocking { m.cancel(p) } }.apply { start() }
                cancelling = t
                // The cancel's wipe is blocked until the request is built.
                t.join(200)
                assertTrue("the cancel waits for the build", t.isAlive)
            }
        }
        // The vault answers once the cancel went through (the photo wiped before the answer arrives).
        ops.duringSend = { cancelling!!.join(5_000) }
        assertEquals("M1", m.send(p).messageId)
        cancelling!!.join(5_000)
        assertFalse(cancelling.isAlive)
        val request = ops.requests.single().second
        val blob = request["blob"]!!.jsonObject
        val key = Base64s.decodeStd(blob["key"]!!.jsonPrimitive.content)
        assertFalse("a complete key, not zeros", key.all { it == 0.toByte() })
        assertArrayEquals(thumb, Base64s.decodeStd(request["thumb"]!!.jsonPrimitive.content))
        assertArrayEquals(jpeg, ClaimBlob.open(ops.uploads.single(), key, Base64s.decodeStd(blob["sha256"]!!.jsonPrimitive.content), blob["size"]!!.jsonPrimitive.content.toLong()))
        // The photo is wiped once the cancel went through.
        assertTrue(p.photo.wiped)
        // The complete request went out and its message is added, without an all-zero thumbnail in memory.
        assertEquals("M1", sent.single().messageId)
        assertNull(m.cachedThumbnail("c1", "M1"))
    }

    @Test
    fun aThumbnailFromMessageNewReadAcrossAClearIsNotKept() {
        val b64 = Base64s.encodeStd(thumb)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"connection_id":"c1","message_id":"M4","direction":"in","text":"","sent_at":"2026-10-15T10:00:00Z",""" +
                """"attachment":{"att_id":"A4","kind":"photo","size":10,"width":320,"height":240,"state":"fetching","thumb":"$b64"}}""",
        ).jsonObject
        m.testHook = { point -> if (point == AttachmentManager.HOOK_EVENT) m.clear() }
        m.onEvent("message.new", body)
        assertNull(m.cachedThumbnail("c1", "M4"))
        m.testHook = null
        m.onEvent("message.new", body)
        assertArrayEquals(thumb, m.cachedThumbnail("c1", "M4"))
    }

    // --- (Rule 3) no availability probe: every vault the app talks to has attachments; closed means nothing is sent ---

    /** The best-effort discards launched in the test's scope have run (they go through `Dispatchers.IO`). */
    private suspend fun kotlinx.coroutines.test.TestScope.settleLaunched() {
        testScheduler.advanceUntilIdle()
        coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() }
    }

    private fun closedRefusal(e: VaultFailure) {
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        assertEquals(AttachmentManager.CODE_CLOSED, e.code)
    }

    @Test
    fun anOpenVaultSendsAndFetchesWithoutAnyProbe() = runTest {
        assertFalse(m.closed())
        m.send(m.begin("c1", "", photo()))
        m.thumbnail("c1", "M5")
        assertEquals("nothing asked before the first photo", listOf("upload", "send:REQ1", "thumb:M5"), ops.calls)
    }

    @Test
    fun closedFollowsTheVault() {
        closedNow = true
        assertTrue(m.closed())
        closedNow = false
        assertFalse(m.closed())
    }

    @Test
    fun whileClosedAPhotoIsNeitherSealedUploadedNorSentAndEnds() = runTest {
        closedNow = true
        val p = m.begin("c1", "", photo())
        closedRefusal(unavailable { m.send(p) })
        assertTrue(ops.calls.isEmpty())
        assertTrue("ended as any refused photo", p.thumbnail.all { it == 0.toByte() })
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aRetryWhileClosedSendsNothingAndACancelDiscardsNothing() = runTest {
        ops.sendFailures += VaultFailure(FailureKind.NO_RESPONSE)
        val p = m.begin("c1", "", photo())
        runCatching { m.send(p) }
        assertTrue(p.uploaded)
        ops.calls.clear()
        closedNow = true
        closedRefusal(unavailable { m.send(p) })
        m.cancel(p)
        assertTrue("no send, no discard while closed", ops.calls.isEmpty())
        assertTrue(p.thumbnail.all { it == 0.toByte() })
    }

    @Test
    fun aCancelAfterTheUploadWhileClosedDiscardsNothing() = runTest {
        ops.sendFailures += VaultFailure(FailureKind.NO_RESPONSE)
        val p = m.begin("c1", "", photo())
        runCatching { m.send(p) }
        ops.calls.clear()
        closedNow = true
        m.cancel(p)
        assertTrue(ops.calls.isEmpty())
        assertTrue(p.thumbnail.all { it == 0.toByte() })
    }

    @Test
    fun closingDuringTheUploadSendsNoMessageAndNoDiscard() = runTest {
        val m = AttachmentManager(ops, onSent = { sent += it }, vaultClosed = { closedNow }, scope = this)
        val p = m.begin("c1", "", photo())
        ops.duringUpload = { closedNow = true }
        closedRefusal(unavailable { m.send(p) })
        settleLaunched()
        assertEquals(listOf("upload"), ops.calls)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aLockDuringTheUploadLaunchesNoDiscard() = runTest {
        // A lock both clears and closes: the best-effort discard of the upload is not sent then.
        val m = AttachmentManager(ops, onSent = { sent += it }, vaultClosed = { closedNow }, scope = this)
        val p = m.begin("c1", "", photo())
        ops.duringUpload = {
            closedNow = true
            m.clear()
        }
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { m.send(p) }.code)
        settleLaunched()
        assertEquals(listOf("upload"), ops.calls)
    }

    @Test
    fun aClearDuringTheUploadOfAnOpenVaultStillDiscardsIt() = runTest {
        val m = AttachmentManager(ops, onSent = { sent += it }, vaultClosed = { closedNow }, scope = this)
        val p = m.begin("c1", "", photo())
        ops.duringUpload = { m.clear() }
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { m.send(p) }.code)
        settleLaunched()
        assertEquals(listOf("upload", "discard:blob1"), ops.calls)
    }

    @Test
    fun whileClosedNothingIsFetchedButMemoryIsStillRead() = runTest {
        m.thumbnail("c1", "M5")
        ops.calls.clear()
        closedNow = true
        assertArrayEquals("from memory, nothing sent", m.cachedThumbnail("c1", "M5"), m.thumbnail("c1", "M5"))
        closedRefusal(unavailable { m.thumbnail("c1", "M6") })
        ops.serve(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(10))
        closedRefusal(unavailable { m.photo("c1", "M7") })
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun closingAfterTheReferenceFetchesNoBlob() = runTest {
        ops.serve(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(10))
        ops.duringContent = { closedNow = true }
        closedRefusal(unavailable { m.photo("c1", "M7") })
        assertEquals("no fetch, and no delete while closed", listOf("content:M7"), ops.calls)
    }

    @Test
    fun closingDuringTheBlobFetchDeletesNothing() = runTest {
        ops.serve(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(10))
        ops.duringFetch = { closedNow = true }
        m.photo("c1", "M7")
        assertEquals(listOf("content:M7", "fetch:B9"), ops.calls)
    }

    /** A closed check that lets the first call through and has a lock land right after it. */
    private fun lockRightAfterTheFirstCheck(): AttachmentManager {
        var first = true
        lateinit var mm: AttachmentManager
        mm = AttachmentManager(ops, onSent = { sent += it }, vaultClosed = {
            if (first) {
                first = false
                mm.clear()
            }
            false
        })
        return mm
    }

    @Test
    fun aLockJustAfterTheOpenCheckVoidsAThumbnail() = runTest {
        val mm = lockRightAfterTheFirstCheck()
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { mm.thumbnail("c1", "M5") }.code)
        assertNull(mm.cachedThumbnail("c1", "M5"))
        assertTrue(ops.thumbsServed.single().all { it == 0.toByte() })
    }

    @Test
    fun aLockJustAfterTheOpenCheckVoidsAPhoto() = runTest {
        val mm = lockRightAfterTheFirstCheck()
        ops.serve(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(10))
        assertEquals(AttachmentManager.CODE_UNAVAILABLE, unavailable { mm.photo("c1", "M7") }.code)
        ops.calls.clear()
        ops.serve(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(10))
        mm.photo("c1", "M7")
        assertEquals("nothing was kept: fetched again", listOf("content:M7", "fetch:B9", "delete:B9"), ops.calls)
    }

    @Test
    fun aLockJustAfterTheOpenCheckVoidsAnInlineThumbnail() {
        val mm = lockRightAfterTheFirstCheck()
        mm.onEvent("message.new", messageNew("M4"))
        assertNull(mm.cachedThumbnail("c1", "M4"))
        mm.onEvent("message.new", messageNew("M4"))
        assertArrayEquals(thumb, mm.cachedThumbnail("c1", "M4"))
    }

    private fun messageNew(id: String) = kotlinx.serialization.json.Json.parseToJsonElement(
        """{"connection_id":"c1","message_id":"$id","direction":"in","text":"","sent_at":"2026-10-15T10:00:00Z",""" +
            """"attachment":{"att_id":"A4","kind":"photo","size":10,"width":320,"height":240,"state":"fetching",""" +
            """"thumb":"${Base64s.encodeStd(thumb)}"}}""",
    ).jsonObject

    @Test
    fun whileClosedAnInlineThumbnailIsNotKept() {
        closedNow = true
        m.onEvent("message.new", messageNew("M4"))
        assertNull(m.cachedThumbnail("c1", "M4"))
    }

    @Test
    fun unsupportedTypeIsAFailureLikeAnyOther() = runTest {
        ops.sendFailures += VaultOpException("message.send", "unsupported_type")
        val p = m.begin("c1", "", photo())
        val e = unavailable { m.send(p) }
        assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
        assertFalse(AttachmentManager.retryable(e))
        assertTrue("final: the photo ends", p.thumbnail.all { it == 0.toByte() })
        ops.thumbFailure = VaultOpException("message.attachment.get", "unsupported_type")
        assertEquals("unsupported_type", unavailable { m.thumbnail("c1", "M5") }.code)
        // Nothing is turned off: the next photo and the next thumbnail go as before.
        assertFalse(m.closed())
        m.send(m.begin("c1", "", photo()))
        m.thumbnail("c1", "M5")
        assertEquals(2, ops.calls.count { it.startsWith("send:") })
        assertEquals(2, ops.calls.count { it == "thumb:M5" })
    }
}
