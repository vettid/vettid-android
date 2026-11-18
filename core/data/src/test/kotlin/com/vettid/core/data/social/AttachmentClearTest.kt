package com.vettid.core.data.social

import com.vettid.core.media.ImageType
import com.vettid.core.media.PreparedPhoto
import com.vettid.core.media.SharedPhotos
import com.vettid.core.vault.FetchedAttachment
import com.vettid.core.vault.SentAttachment
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A lock's clear at the caller (#179 review F4): a pending photo's wipe that throws skips nothing. The share provider's
 * offers are withdrawn and zeroed, and the other pending photo is wiped. Under Robolectric: [SharedPhotos.offer] builds a
 * real content URI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentClearTest {
    private class Ops : AttachmentOps {
        var n = 0
        override fun newRequestId() = "REQ${++n}"
        override suspend fun upload(blob: ByteArray) = error("not used")
        override suspend fun send(connectionId: String, text: String?, attachment: JsonObject, requestId: String): SentAttachment =
            error("not used")
        override suspend fun discard(blobId: String) = Unit
        override suspend fun thumb(connectionId: String, messageId: String): ByteArray = error("not used")
        override suspend fun content(connectionId: String, messageId: String): FetchedAttachment = error("not used")
        override suspend fun fetchBlob(blobId: String): ByteArray = error("not used")
        override suspend fun deleteBlob(blobId: String) = Unit
    }

    private val m = AttachmentManager(Ops(), onSent = {}, vaultClosed = { false })

    @After
    fun tearDown() {
        m.testHook = null
        m.prepareHook = null
        SharedPhotos.clear()
    }

    private fun photo() = PreparedPhoto(ByteArray(64) { 1 }, ByteArray(16) { 2 }, 10, 10)

    @Test
    fun aPendingWipeThatThrowsStillClearsTheSharedPhotos() = runTest {
        val a = m.begin("c1", "", photo())
        val b = m.begin("c1", "", photo())
        val uri = SharedPhotos.offer("com.vettid.test", ByteArray(256) { 7 }, ImageType.JPEG)
        val token = uri.pathSegments.first()
        val offer = SharedPhotos.get(uri)!!
        // The first pending wipe fails (the set's order: either photo).
        val bug = IllegalStateException("wipe")
        var first = true
        m.testHook = { point ->
            if (point == AttachmentManager.HOOK_WIPE && first) {
                first = false
                throw bug
            }
        }
        val e = assertThrows(IllegalStateException::class.java) { m.clear() }
        assertSame("the wipe's failure is rethrown after the last step", bug, e)
        assertFalse("the share offer is withdrawn", SharedPhotos.live(token, offer))
        assertTrue("and zeroed", offer.bytes.all { it == 0.toByte() })
        assertEquals("the other pending photo is wiped", 1, listOf(a, b).count { it.photo.wiped })
        assertEquals("counted as a clear", 1L, m.cleared.value)
    }

    private fun picture(): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
            .also { it.eraseColor(android.graphics.Color.BLUE) }

    /** [AttachmentManager.prepare] whose caller is cancelled just as the photo is made: the photo comes back to nobody. */
    private suspend fun kotlinx.coroutines.CoroutineScope.cancelledAsMade(prepare: suspend () -> PreparedPhoto): PreparedPhoto {
        val made = java.util.concurrent.atomic.AtomicReference<PreparedPhoto?>()
        lateinit var job: kotlinx.coroutines.Job
        m.prepareHook = { p ->
            made.set(p)
            job.cancel()
        }
        job = launch { prepare() }
        job.join()
        assertTrue("the caller was cancelled", job.isCancelled)
        return made.get()!!
    }

    @Test
    fun aShotPreparedForACancelledCallerIsWiped() = runTest {
        val shot = picture()
        val made = cancelledAsMade { m.prepare(shot) }
        assertTrue("not dropped with its bytes", made.wiped)
    }

    @Test
    fun aPickPreparedForACancelledCallerIsWiped() = runTest {
        val out = java.io.ByteArrayOutputStream()
        picture().compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        val jpeg = out.toByteArray()
        val made = cancelledAsMade { m.prepare(jpeg) }
        assertTrue("not dropped with its bytes", made.wiped)
    }

    @Test
    fun aPhotoPreparedForAWaitingCallerIsHandedOverWhole() = runTest {
        val p = m.prepare(picture())
        assertFalse(p.wiped)
    }
}
