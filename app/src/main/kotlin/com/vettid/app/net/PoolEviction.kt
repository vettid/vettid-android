package com.vettid.app.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool

/**
 * Empties the OkHttp connection pool off the caller's thread. Closing a live TLS connection writes its close_notify,
 * which is network I/O: on the main thread (the app coming to the foreground, `Application.ActivityLifecycleCallbacks`)
 * Android throws NetworkOnMainThreadException. Since the on-phone notification service (ANDROID-PLAN 0.1.23) keeps
 * connections open in the background, the pool is never idle when the app comes back, and every open crashed.
 * [evict] returns at once; the pool is emptied on [dispatcher] (IO).
 */
class PoolEviction(
    private val evictAll: () -> Unit,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(pool: ConnectionPool, scope: CoroutineScope) : this({ pool.evictAll() }, scope)

    fun evict() {
        scope.launch(dispatcher) {
            runCatching { evictAll() }.onFailure {
                android.util.Log.w("VettID", "connection pool not emptied: ${it.javaClass.simpleName}")
            }
        }
    }
}
