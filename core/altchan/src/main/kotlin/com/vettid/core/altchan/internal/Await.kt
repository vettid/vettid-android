package com.vettid.core.altchan.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs [call] asynchronously and reads the response with [read] (closing it); cancellation cancels the call. */
internal suspend fun <T> awaitCall(call: Call, read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    call.enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val r = try {
                    response.use(read)
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(r)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        },
    )
    cont.invokeOnCancellation { runCatching { call.cancel() } }
}
