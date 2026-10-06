package com.vettid.core.relay

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Whether the device has a network the app may use right now (Android: a default network that is not blocked for
 * the app, as after Doze until the system lifts the app's restrictions). [ALWAYS] where nothing tells.
 */
fun interface NetworkGate {
    fun usable(): Boolean

    companion object {
        val ALWAYS = NetworkGate { true }
    }
}

/**
 * Retries a call that failed in transport (no HTTP status: DNS, connect, reset, end of stream) while the device
 * wakes up: the first request after Doze races the system lifting the app's network restrictions and may meet a
 * pooled connection that died meanwhile. An answer with an HTTP status (4xx, 5xx: `token_revoked`, `unauthorized`,
 * ...) is never touched; it is the caller's.
 *
 * Which failures are retried depends on [Policy], set as the request's tag ([Request.Builder.tag]):
 * - [Policy.IDEMPOTENT] (the default for GET and HEAD): any transport failure except a timeout after the request
 *   was sent (a slow server is not a waking phone) and TLS/protocol errors.
 * - [Policy.UNSENT_ONLY] (the default for every other method, and what the relay client sets on its signed requests,
 *   whose signatures the relay accepts once, RELAY-PROTOCOL §4.1): only a failure before any byte of the request
 *   was written (DNS, connect, a socket the system refused), so the server cannot have seen it.
 * - [Policy.NEVER]: not retried (a relay claim fetch, §6.9).
 *
 * Before each attempt, while [gate] says no network is usable, it waits for one (at most [networkWaitMs] per call
 * in all); a retry waits [backoffMs] for its attempt first. At most [maxRetries] retries. A cancelled call stops
 * waiting at once.
 */
class TransportRetry(
    private val gate: NetworkGate = NetworkGate.ALWAYS,
    private val maxRetries: Int = DEFAULT_RETRIES,
    private val networkWaitMs: Long = DEFAULT_NETWORK_WAIT_MS,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    enum class Policy { IDEMPOTENT, UNSENT_ONLY, NEVER }

    /** Whether the current attempt started writing the request (tracked per call through its event listener). */
    private class Sent : EventListener() {
        @Volatile
        var started = false

        override fun requestHeadersStart(call: Call) {
            started = true
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        val req = chain.request()
        val policy = policyOf(req)
        val sent = Sent().also { call.addEventListener(it) }
        var networkBudget = networkWaitMs
        var attempt = 0
        while (true) {
            networkBudget -= awaitNetwork(call, networkBudget)
            sent.started = false
            try {
                return chain.proceed(req)
            } catch (e: IOException) {
                if (call.isCanceled() || attempt >= maxRetries || !retryable(e, policy, sent.started)) throw e
            }
            pause(call, backoffMs.getOrElse(attempt) { backoffMs.lastOrNull() ?: 0L })
            attempt++
        }
    }

    /** Waits while no network is usable, at most [budget] ms; returns how long it waited. */
    private fun awaitNetwork(call: Call, budget: Long): Long {
        var waited = 0L
        while (waited < budget && !gate.usable()) {
            if (call.isCanceled()) throw IOException("Canceled")
            val step = minOf(POLL_MS, budget - waited)
            sleep(step)
            waited += step
        }
        return waited
    }

    private fun pause(call: Call, ms: Long) {
        var left = ms
        while (left > 0) {
            if (call.isCanceled()) throw IOException("Canceled")
            val step = minOf(POLL_MS, left)
            sleep(step)
            left -= step
        }
        if (call.isCanceled()) throw IOException("Canceled")
    }

    companion object {
        const val DEFAULT_RETRIES = 2
        const val DEFAULT_NETWORK_WAIT_MS = 8_000L
        val DEFAULT_BACKOFF_MS = listOf(250L, 1_000L)
        private const val POLL_MS = 100L

        fun policyOf(r: Request): Policy = r.tag(Policy::class.java)
            ?: if (r.method == "GET" || r.method == "HEAD") Policy.IDEMPOTENT else Policy.UNSENT_ONLY

        /** Whether a transport failure [e] may be retried under [policy], [sent] telling whether the request started going out. */
        internal fun retryable(e: IOException, policy: Policy, sent: Boolean): Boolean = when {
            policy == Policy.NEVER -> false
            // Not a passing state of the network: a certificate or pin that does not match, a bad answer, cleartext refused.
            e is SSLPeerUnverifiedException || e is SSLHandshakeException && e.cause is java.security.cert.CertificateException -> false
            e is ProtocolException || e is UnknownServiceException -> false
            !sent -> true
            policy == Policy.UNSENT_ONLY -> false
            else -> e !is InterruptedIOException
        }
    }
}
