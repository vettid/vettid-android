package com.vettid.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import okhttp3.ConnectionPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The connection pool is emptied off the caller's thread (the #100 regression: `evictAll` on the main thread, from
 * the app's foreground callback, closed the notification service's live TLS connections and threw
 * NetworkOnMainThreadException at every open).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoolEvictionTest {
    @Test
    fun evictReturnsAtOnceAndEmptiesThePoolOnTheDispatcher() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var evicted = 0
        val e = PoolEviction({ evicted++ }, scope, dispatcher)
        e.evict()
        assertEquals("nothing runs on the caller's thread", 0, evicted)
        scope.advanceUntilIdle()
        assertEquals(1, evicted)
    }

    @Test
    fun theRealPoolIsEmptiedOnAnIoThreadNotTheCaller() {
        val scope = CoroutineScope(SupervisorJob())
        val caller = Thread.currentThread()
        val done = CountDownLatch(1)
        var thread: Thread? = null
        PoolEviction({
            thread = Thread.currentThread()
            done.countDown()
        }, scope, Dispatchers.IO).evict()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNotEquals(caller, thread)
        // And with OkHttp's own pool (no connections: nothing to close, no failure).
        PoolEviction(ConnectionPool(), scope).evict()
        scope.cancel()
    }
}
