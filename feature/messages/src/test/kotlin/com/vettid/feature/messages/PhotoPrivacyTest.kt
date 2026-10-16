// Fake vault answers stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.feature.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.ClaimBlob
import com.vettid.core.data.social.AttachmentManager
import com.vettid.core.data.social.AttachmentOps
import com.vettid.core.data.social.FetchedPhoto
import com.vettid.core.media.ImageType
import com.vettid.core.media.PhotoShareProvider
import com.vettid.core.media.Photos
import com.vettid.core.media.SharedPhotos
import com.vettid.core.vault.BlobRef
import com.vettid.core.vault.FetchedAttachment
import com.vettid.core.vault.SentAttachment
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException

/**
 * ANDROID-PLAN 0.1.39 "Privacy, data directory scan" (the in-process part; the phone run is with the owner): a photo
 * prepared, sent, fetched back, viewed and shared, then the vault locked; nothing of it in the app's directories, and
 * the share provider refuses every read after the lock. Also: the viewer's window has `FLAG_SECURE`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoPrivacyTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A vault that keeps what its device uploads and serves it back as a fresh blob, as §10.5.1 says. */
    private class LoopbackVault : AttachmentOps {
        val mailbox = mutableMapOf<String, ByteArray>()
        var stored: ByteArray? = null
        val ids = mutableListOf<String>()
        val keys = mutableListOf<ByteArray>()
        override suspend fun supported() = true
        override fun newRequestId() = "01JB2Z6V9K3M4N5P6Q7R8S9T70"
        override suspend fun upload(blob: ByteArray): String = "BLOBUP${mailbox.size}".also { mailbox[it] = blob.copyOf(); ids += it }
        override suspend fun send(connectionId: String, text: String?, attachment: JsonObject, requestId: String): SentAttachment {
            val b = attachment["blob"]!!.jsonObject
            val key = Base64s.decodeStd(b["key"]!!.jsonPrimitive.content).also { keys += it }
            stored = ClaimBlob.open(mailbox.remove(b["id"]!!.jsonPrimitive.content)!!, key, Base64s.decodeStd(b["sha256"]!!.jsonPrimitive.content), b["size"]!!.jsonPrimitive.content.toLong())
            return SentAttachment("01JB2Z6V9K3M4N5P6Q7R8S9T71", "2026-10-15T10:00:00Z", "01JB2Z6V9K3M4N5P6Q7R8S9T72")
        }
        override suspend fun discard(blobId: String) {
            mailbox.remove(blobId)
        }
        override suspend fun thumb(connectionId: String, messageId: String) = error("from memory")
        override suspend fun content(connectionId: String, messageId: String): FetchedAttachment {
            val s = ClaimBlob.seal(stored!!)
            val id = "BLOBDOWN${mailbox.size}".also { ids += it }
            mailbox[id] = s.blob
            keys += s.key.copyOf()
            return FetchedAttachment(BlobRef(id, Base64s.encodeStd(s.key), Base64s.encodeStd(s.sha256), stored!!.size.toLong(), "image/jpeg"))
        }
        override suspend fun fetchBlob(blobId: String) = mailbox.getValue(blobId).copyOf()
        override suspend fun deleteBlob(blobId: String) {
            mailbox.remove(blobId)
        }
    }

    private fun picture(): Bitmap = Bitmap.createBitmap(1_200, 900, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it)
        c.drawColor(Color.rgb(20, 120, 200))
        c.drawCircle(600f, 450f, 300f, Paint().apply { color = Color.rgb(250, 200, 20) })
    }

    private fun dirs(): List<File> = listOfNotNull(
        context.dataDir, context.filesDir, context.cacheDir, context.noBackupFilesDir, context.codeCacheDir,
        context.getDatabasePath("x").parentFile, context.externalCacheDir, context.getExternalFilesDir(null),
    ).distinct()

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || hay.size < needle.size) return false
        return (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }
    }

    private fun hasImageSignature(b: ByteArray): Boolean = (0 until b.size - 12).any { i ->
        (b[i] == 0xFF.toByte() && b[i + 1] == 0xD8.toByte() && b[i + 2] == 0xFF.toByte()) ||
            (b[i] == 0x89.toByte() && b[i + 1] == 0x50.toByte() && b[i + 2] == 0x4E.toByte() && b[i + 3] == 0x47.toByte()) ||
            (b[i] == 'R'.code.toByte() && b[i + 1] == 'I'.code.toByte() && b[i + 2] == 'F'.code.toByte() && b[i + 3] == 'F'.code.toByte() &&
                b[i + 8] == 'W'.code.toByte() && b[i + 9] == 'E'.code.toByte() && b[i + 10] == 'B'.code.toByte() && b[i + 11] == 'P'.code.toByte())
    }

    @Test
    fun afterSendingViewingSharingAndALockNothingOfThePhotoIsOnDisk() = runTest {
        val before = dirs().flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }.toSet()
        val vault = LoopbackVault()
        val m = AttachmentManager(vault, onSent = {})
        val prepared = Photos.prepare(picture())
        val jpeg = prepared.jpeg.copyOf()
        val thumb = prepared.thumb.copyOf()
        m.send(m.begin("c1", "caption", prepared))
        val fetched: FetchedPhoto = m.photo("c1", "01JB2Z6V9K3M4N5P6Q7R8S9T71")
        Photos.decodeReceived(fetched.bytes, 1_080) // viewed
        Photos.decodeThumbnail(m.cachedThumbnail("c1", "01JB2Z6V9K3M4N5P6Q7R8S9T71")!!) // the bubble
        val provider = Robolectric.setupContentProvider(PhotoShareProvider::class.java, SharedPhotos.authority(context.packageName))
        val uri: Uri = SharedPhotos.offer(context.packageName, fetched.bytes, ImageType.JPEG)
        // Readable while the vault is open (the bytes through Robolectric's pipe are not checked: its pipes differ by host).
        assertEquals("image/jpeg", provider.getType(uri))
        provider.openFile(uri, "r").close()

        m.clear() // the lock
        assertThrows("the provider refuses every read after the lock", FileNotFoundException::class.java) { provider.openFile(uri, "r") }

        val needles = listOf(jpeg.copyOfRange(jpeg.size / 2, jpeg.size / 2 + 64), thumb.copyOfRange(thumb.size / 2, thumb.size / 2 + 32)) +
            vault.ids.map { it.toByteArray() } + listOf("01JB2Z6V9K3M4N5P6Q7R8S9T72".toByteArray()) + vault.keys + vault.keys.map { Base64s.encodeStd(it).toByteArray() }
        val files = dirs().flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }
        assertTrue("no file was added: ${files.toSet() - before}", (files.toSet() - before).isEmpty())
        for (f in files) {
            val b = f.readBytes()
            assertTrue("image signature in $f", !hasImageSignature(b))
            needles.forEach { n -> assertTrue("photo, thumbnail, blob id, att_id or key in $f", !contains(b, n)) }
        }
        assertNotEquals(0, needles.size)
    }

    @Test
    fun theViewerKeepsItsWindowSecure() {
        compose.setContent {
            PhotoViewerScreen(PhotoViewerUiState(loading = true), onBack = {}, onRetry = {})
        }
        compose.waitForIdle()
        val flags = compose.activity.window.attributes.flags
        assertTrue("FLAG_SECURE", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun aClearTakesTheOldThumbnailOffTheBubble() {
        val jpeg = Photos.prepare(picture()).thumb
        var generation by androidx.compose.runtime.mutableLongStateOf(0L)
        var available = true
        val msg = com.vettid.core.data.social.MessageInfo(
            "c1", "m1", false, "", java.time.Instant.EPOCH, delivered = true, read = true,
            attachment = com.vettid.core.data.social.AttachmentInfo("a1", com.vettid.core.data.social.AttachmentState.STORED, width = 320, height = 240),
        )
        compose.setContent {
            PhotoBubble(msg, "Sam", PhotoActions(thumbnail = { if (available) jpeg.copyOf() else null }, generation = generation), {})
        }
        fun shown() = compose.onAllNodesWithTag("photo_m1").fetchSemanticsNodes().singleOrNull()?.config?.getOrElseNullable(ThumbnailShown) { null } == true
        compose.waitUntil(10_000) { shown() }
        available = false
        generation = 1 // the clear
        compose.waitForIdle()
        compose.waitUntil(10_000) { !shown() }
        assertTrue(!shown())
    }

    @Test
    fun aClearWithdrawsEveryShareOffer() {
        val uri = SharedPhotos.offer(context.packageName, Photos.jpeg(picture(), 80), ImageType.JPEG)
        val token = uri.pathSegments.first()
        val offer = SharedPhotos.get(uri)!!
        assertTrue(SharedPhotos.live(token, offer))
        SharedPhotos.clear()
        assertTrue(!SharedPhotos.live(token, offer))
        assertTrue(offer.bytes.all { it == 0.toByte() })
    }
}
