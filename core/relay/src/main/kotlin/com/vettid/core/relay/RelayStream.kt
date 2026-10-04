package com.vettid.core.relay

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.StrictJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/**
 * A WebSocket collect session (RELAY-PROTOCOL §6.4): server frames are
 * messages with the same lease as long-poll; the client acks with
 * `{"ack": msg_id}`. [incoming] closes with a [RelayException] when the
 * session ends (`mailbox_unknown` for close code 4404), or an IOException
 * when the connection fails; the caller falls back to long-poll.
 */
class RelayStream private constructor() {
    private val channel = Channel<RelayMessage>(Channel.UNLIMITED)
    private lateinit var socket: WebSocket

    val incoming: ReceiveChannel<RelayMessage> get() = channel

    /** Acks a message over the socket; false if the socket is closed. */
    fun ack(msgId: String): Boolean = socket.send(JsonBuilder().string("ack", msgId).build())

    fun close() {
        socket.close(NORMAL_CLOSURE, null)
        channel.close()
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = try {
                RelayMessage.parse(StrictJson.parseObject(text))
            } catch (_: CryptoException) {
                return // a malformed frame is dropped; the lease brings the message back
            }
            channel.trySend(m)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            channel.close(closeError(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            channel.close(closeError(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val e = if (response != null && response.code >= HTTP_ERROR) {
                RelayException(response.code, "http_${response.code}", t.message ?: "")
            } else {
                t as? IOException ?: IOException(t)
            }
            channel.close(e)
        }
    }

    companion object {
        const val NORMAL_CLOSURE = 1000
        const val CLOSE_MAILBOX_UNKNOWN = 4404
        private const val HTTP_ERROR = 400

        internal fun closeError(code: Int, reason: String): Throwable? = when (code) {
            NORMAL_CLOSURE -> null
            CLOSE_MAILBOX_UNKNOWN -> RelayException(404, RelayException.MAILBOX_UNKNOWN, reason)
            else -> IOException("websocket closed: $code")
        }

        internal fun open(http: OkHttpClient, request: Request): RelayStream {
            val s = RelayStream()
            s.socket = http.newWebSocket(request, s.Listener())
            return s
        }
    }
}
