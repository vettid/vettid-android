package com.vettid.core.media

import android.os.ParcelFileDescriptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [SharedPhotos.clear] fails secure (a lock, the viewer closed): a stream that ends meanwhile, a pipe that cannot be
 * closed and an offer made at the same instant never leave an offer live or unzeroed. Races are driven by
 * [SharedPhotos.testHook], not by timing.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPhotosTest {
    private val pkg = "com.vettid.test"

    @After
    fun tearDown() {
        SharedPhotos.testHook = null
        SharedPhotos.clear()
    }

    private fun bytes() = ByteArray(1024) { (it % 251 + 1).toByte() }

    private fun token(uri: android.net.Uri) = uri.pathSegments.first()

    /** An offer with a registered pipe (the provider's write end); returns the offer, its token and the pipe's two ends. */
    private fun offeredWithPipe(): Triple<Pair<String, SharedPhotos.Offer>, ParcelFileDescriptor, ParcelFileDescriptor> {
        val uri = SharedPhotos.offer(pkg, bytes(), ImageType.JPEG)
        val offer = SharedPhotos.get(uri)!!
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        assertTrue(SharedPhotos.opened(token(uri), offer, write))
        return Triple(token(uri) to offer, read, write)
    }

    private fun assertWithdrawnAndZeroed(token: String, offer: SharedPhotos.Offer) {
        assertFalse("withdrawn", SharedPhotos.live(token, offer))
        assertTrue("zeroed", offer.bytes.all { it == 0.toByte() })
    }

    @Test
    fun streamsEndingWhileAClearClosesThePipesNeverStopTheClear() {
        val (a, _, writeA) = offeredWithPipe()
        val (b, _, writeB) = offeredWithPipe()
        // At the first pipe, both streams end on their own thread (PhotoShareProvider's `closed` at stream end): the set
        // is empty between the clear's check and its read of the next pipe, the window the old `toList` broke in.
        var first = true
        SharedPhotos.testHook = { point ->
            if (point == SharedPhotos.HOOK_NEXT_PIPE && first) {
                first = false
                val t = Thread {
                    SharedPhotos.closed(writeA)
                    SharedPhotos.closed(writeB)
                }
                t.start()
                t.join()
            }
        }
        SharedPhotos.clear()
        assertFalse("the hook ran", first)
        assertWithdrawnAndZeroed(a.first, a.second)
        assertWithdrawnAndZeroed(b.first, b.second)
    }

    @Test
    fun offersAreWithdrawnAndZeroedEvenIfClosingAPipeThrows() {
        val (a, _, _) = offeredWithPipe()
        val (b, _, _) = offeredWithPipe()
        SharedPhotos.testHook = { point -> if (point == SharedPhotos.HOOK_NEXT_PIPE) error("pipe") }
        val e = assertThrows(IllegalStateException::class.java) { SharedPhotos.clear() }
        assertEquals("pipe", e.message)
        assertWithdrawnAndZeroed(a.first, a.second)
        assertWithdrawnAndZeroed(b.first, b.second)
        // The lock was let go: a later clear and a later offer work.
        SharedPhotos.testHook = null
        SharedPhotos.clear()
        val uri = SharedPhotos.offer(pkg, bytes(), ImageType.JPEG)
        assertTrue(SharedPhotos.live(token(uri), SharedPhotos.get(uri)!!))
    }

    @Test
    fun anOfferMadeAtTheSameInstantAsAClearIsWithdrawnByIt() {
        val clearer = AtomicReference<Thread>()
        val inOffer = CountDownLatch(1)
        // Inside the offer's step, a clear starts on another thread; the offer finishes only once that clear is waiting
        // for the lock (or, without the lock, has already run past it).
        SharedPhotos.testHook = { point ->
            if (point == SharedPhotos.HOOK_OFFERED) {
                val t = Thread { SharedPhotos.clear() }.apply { start() }
                clearer.set(t)
                inOffer.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
                while (t.state != Thread.State.BLOCKED && t.isAlive && System.nanoTime() < deadline) Thread.yield()
            }
        }
        val uri = SharedPhotos.offer(pkg, bytes(), ImageType.JPEG)
        val offer = SharedPhotos.get(uri)
        assertTrue("the hook ran", inOffer.await(WAIT_S, TimeUnit.SECONDS))
        clearer.get().join(TimeUnit.SECONDS.toMillis(WAIT_S))
        assertFalse("the clear finished", clearer.get().isAlive)
        // The clear came after the offer was made: nothing of it is left live.
        assertNull(SharedPhotos.get(uri))
        if (offer != null) assertWithdrawnAndZeroed(token(uri), offer)
    }

    @Test
    fun aPipeForAnOfferAClearWithdrewIsRefused() {
        val uri = SharedPhotos.offer(pkg, bytes(), ImageType.JPEG)
        val offer = SharedPhotos.get(uri)!!
        SharedPhotos.clear()
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        assertFalse(SharedPhotos.opened(token(uri), offer, write))
        read.close()
        write.close()
    }

    private companion object {
        const val WAIT_S = 5L
    }
}
