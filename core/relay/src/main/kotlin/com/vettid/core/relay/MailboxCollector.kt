package com.vettid.core.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Collects one mailbox until cancelled (RELAY-PROTOCOL §6.3, §6.4;
 * CLIENT-NOTES §5): each message goes to the handler, which processes and
 * persists it, and is acked only after the handler returns (at-least-once;
 * the handler dedupes by msg_id). A long-poll is re-issued immediately after
 * every response. In [Mode.WEBSOCKET] the collector streams and falls back
 * to long-poll for [fallback] after a socket failure.
 *
 * A handler that throws [LeaveUnacked] leaves that message unacked: the relay
 * delivers it again after its lease (§6.3), and the next ones are handled.
 *
 * Transport errors, 429/5xx and a stale or replayed signature
 * (`timestamp_stale`, `replay_detected`: a request signed before the phone
 * froze in the background; every attempt is signed afresh) back off with full
 * jitter (0.25 s × 2ⁿ, capped at 30 s). Terminal relay errors end [run]:
 * `mailbox_unknown` (the mailbox is gone) and every other client error. The
 * owner of the collector restarts it ([run] ended is not the end of the app).
 */
class MailboxCollector(
    private val client: RelayClient,
    private val mode: Mode = Mode.LONG_POLL,
    private val wait: Duration = Duration.ofSeconds(RelayClient.DEFAULT_WAIT_S),
    private val fallback: Duration = Duration.ofMinutes(5),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
    /**
     * Called with `true` when a collect request goes out (a long-poll round, or a stream opened) and with `false`
     * when it ended (its messages handled and acked, or it failed). A long poll answers at once when messages wait,
     * so a request that has been out for a while found the mailbox empty: what a push wake waits for.
     */
    private val onRequest: (Boolean) -> Unit = {},
    /**
     * Called with the [System.nanoTime] at which a collect request went out, once that request reached the relay and
     * everything it brought was handled: a long-poll round that returned, or a stream that delivered a message or
     * closed normally. Proof that the mailbox was read after that time (A6 audit N3).
     */
    private val onReached: (Long) -> Unit = {},
) {
    enum class Mode { LONG_POLL, WEBSOCKET }

    /** Runs until cancelled or a terminal relay error. */
    suspend fun run(handle: suspend (RelayMessage) -> Unit) {
        var failures = 0
        var pollUntil: Instant = Instant.MIN
        while (true) {
            currentCoroutineContext().ensureActive()
            val useSocket = mode == Mode.WEBSOCKET && Instant.now(clock).isAfter(pollUntil)
            try {
                round(useSocket, handle)
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                if (!e.retryable) throw e
                failures++
                sleep(retryDelay(failures, e.retryAfterSeconds))
            } catch (_: IOException) {
                if (useSocket) pollUntil = Instant.now(clock).plus(fallback)
                failures++
                sleep(retryDelay(failures, 0))
            }
        }
    }

    /** One collect request (a stream until it closes, or a long-poll round), announced to [onRequest]. */
    private suspend fun round(useSocket: Boolean, handle: suspend (RelayMessage) -> Unit) {
        val started = System.nanoTime()
        onRequest(true)
        try {
            if (useSocket) {
                stream(handle) { onReached(started) }
            } else {
                pollOnce(handle)
                onReached(started)
            }
        } finally {
            onRequest(false)
        }
    }

    /** One long-poll round: collects, handles and acks each message. Returns how many arrived. */
    suspend fun pollOnce(handle: suspend (RelayMessage) -> Unit): Int {
        val msgs = client.collect(wait)
        for (m in msgs) {
            if (handled(m, handle)) client.ack(m.msgId)
        }
        return msgs.size
    }

    private suspend fun stream(handle: suspend (RelayMessage) -> Unit, reached: () -> Unit) {
        val s = client.openStream()
        try {
            while (true) {
                val m = try {
                    s.incoming.receive()
                } catch (_: ClosedReceiveChannelException) {
                    reached()
                    return // closed normally: reconnect
                }
                if (handled(m, handle) && !s.ack(m.msgId)) client.ack(m.msgId)
                reached()
            }
        } finally {
            s.close()
        }
    }

    /** Hands [m] to [handle]; false when the handler left it for redelivery ([LeaveUnacked]). */
    private suspend fun handled(m: RelayMessage, handle: suspend (RelayMessage) -> Unit): Boolean = try {
        handle(m)
        true
    } catch (_: LeaveUnacked) {
        false
    }

    private fun retryDelay(failures: Int, retryAfterSeconds: Int): Long {
        if (retryAfterSeconds > 0) return retryAfterSeconds * MS_PER_S + random.nextLong(JITTER_MS)
        val cap = minOf(BASE_MS shl minOf(failures, MAX_SHIFT), MAX_DELAY_MS)
        return random.nextLong(cap / 2, cap + 1)
    }

    private companion object {
        const val BASE_MS = 250L
        const val MAX_SHIFT = 7
        const val MAX_DELAY_MS = 30_000L
        const val JITTER_MS = 250L
        const val MS_PER_S = 1000L
    }
}

/**
 * Thrown by a [MailboxCollector] handler for a message it cannot process yet but must not lose (a message of an
 * epoch whose `hs.fin` has not arrived, VAULT-MESSAGING §6.3): the collector does not ack it, so the relay delivers
 * it again after its lease.
 */
class LeaveUnacked(reason: String) : Exception(reason)
