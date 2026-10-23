package com.vettid.core.calls

import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.vault.CallCandidate
import com.vettid.core.vault.CallEvent
import com.vettid.core.vault.CallKeys
import com.vettid.core.vault.CallMessageException
import com.vettid.core.vault.IncomingCall
import com.vettid.core.vault.OutgoingCall
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * [CallSignalling] on the vault. **This is where a call's checks are asked for**: [CallMachine] trusts what comes
 * out of [events], so nothing reaches it that has not been through [CallGateway.incoming] or [CallGateway.accept]
 * (the signature, identity, SDP and suite checks of `CallApi`). The rules, each one line below:
 *
 * 1. An offer rings only if its connection is one the app knows, and it verifies against **that connection's
 *    identity as the app shows it** ([pinned]) and against the suites this device can do. Otherwise it is dropped:
 *    no ring, nothing sent ([refused] counts it).
 * 2. An answer is used only if it is for a call this device placed and verifies against the identity of the
 *    connection **that call was placed to**. Otherwise the call is over: [SignalEvent.AnswerRejected].
 * 3. Key material is this object's to destroy: an [OutgoingCall]'s ephemeral key when its call ends in any way,
 *    [CallKeys] as soon as their copies are on their way to the engine.
 * 4. **Nothing that goes wrong with one event ends the listening.** Whatever a check or the vault client throws
 *    (not only a failed check: a locked vault, a relay error), the event is refused and counted, and the next one
 *    is handled.
 *
 * The only implementation of [CallSignalling] outside tests (the interface is internal to this module).
 */
internal class VaultCallSignalling(
    private val gateway: CallGateway,
    /** The fingerprint of the connection's pinned identity key, as its details show it; null for no such connection. */
    private val pinned: suspend (connectionId: String) -> String?,
    /** What this device's media engines can do ([MediaEngineFactory.suites]). */
    private val suites: List<MediaSuite>,
    /**
     * Reads the app's connections again. The list is in memory and is filled after the vault opens: the offer that
     * was waiting in the mailbox at unlock can be handled before it. So a connection that is not found is looked
     * for once more after this.
     */
    private val refreshConnections: suspend () -> Unit = {},
) : CallSignalling {
    private val lock = Any()
    private val placed = LinkedHashMap<String, OutgoingCall>()
    private val ringing = LinkedHashMap<String, IncomingCall>()
    private val refusedFlow = MutableStateFlow(0)
    private val errorsFlow = MutableStateFlow(0)

    /** Calls being started whose id is not known here yet ([start] has not returned). */
    private var starting = 0

    /** Answers that arrived for no known call while one was being started: looked at again when it is. */
    private val heldAnswers = mutableListOf<CallEvent.Answer>()
    private val late = Channel<SignalEvent>(Channel.UNLIMITED)

    /** Offers and answers that did not pass their checks since the process started (diagnostics; no detail is kept). */
    val refused: StateFlow<Int> = refusedFlow.asStateFlow()

    /** Of those, the ones refused because something failed (the vault client, the relay), not because a check did. */
    val errors: StateFlow<Int> = errorsFlow.asStateFlow()

    override val events: Flow<SignalEvent> = merge(
        gateway.events.mapNotNull { e ->
            when (e) {
                is CallEvent.Offer -> offer(e)
                is CallEvent.Answer -> answer(e)
                is CallEvent.Ringing -> SignalEvent.Ringing(e.callId)
                is CallEvent.Ice -> SignalEvent.Ice(e.ice.callId, e.ice.candidates)
                is CallEvent.Ended -> {
                    forget(e.callId)
                    SignalEvent.End(e.callId, e.reason)
                }
            }
        },
        late.receiveAsFlow(),
    )

    /** The connection's identity, looked for a second time after the list has been read again (see [refreshConnections]). */
    private suspend fun identity(connectionId: String): String {
        pinned(connectionId)?.let { return it }
        refreshConnections()
        return pinned(connectionId) ?: throw CallMessageException("no such connection")
    }

    /** Rule 4: [e] is counted, and whether it was a check that refused or something that failed. */
    private fun count(e: Exception) {
        refusedFlow.update { it + 1 }
        if (e !is CallMessageException) errorsFlow.update { it + 1 }
    }

    /** Rule 1. */
    private suspend fun offer(e: CallEvent.Offer): SignalEvent.Offer? {
        val call = try {
            val connectionId = e.connectionId ?: throw CallMessageException("no connection named")
            gateway.incoming(e.message, identity(connectionId), suites)
        } catch (x: CancellationException) {
            throw x
        } catch (@Suppress("TooGenericExceptionCaught") x: Exception) {
            count(x)
            return null
        }
        synchronized(lock) {
            ringing[call.callId] = call
            while (ringing.size > MAX_KEPT) ringing.remove(ringing.keys.first())
        }
        return SignalEvent.Offer(call.callId, call.connectionId, call.sdp, call.exp, call.ringFor, call.ice.servers, call.negotiated.chosen)
    }

    /** Rule 2. */
    private suspend fun answer(e: CallEvent.Answer): SignalEvent? {
        val call = synchronized(lock) {
            placed[e.callId] ?: run {
                // The vault can pass the answer on before its answer to `call.start` has been read here.
                if (starting > 0 && heldAnswers.size < MAX_KEPT) heldAnswers += e
                null
            }
        } ?: return null
        return try {
            val accepted = gateway.accept(call, e.message, identity(call.connectionId))
            SignalEvent.Answer(accepted.callId, accepted.sdp, handOver(accepted.keys))
        } catch (x: CancellationException) {
            throw x
        } catch (@Suppress("TooGenericExceptionCaught") x: Exception) {
            count(x)
            SignalEvent.AnswerRejected(call.callId)
        } finally {
            // Answered or refused, the ephemeral key has done its work (rule 3).
            forget(call.callId)
        }
    }

    /** Rule 3: copies for the engine (which wipes them), and the originals destroyed here. */
    private fun handOver(keys: CallKeys): MediaKeys = try {
        MediaKeys(keys.suite, keys.send(), keys.receive())
    } finally {
        keys.destroy()
    }

    private fun forget(callId: String) {
        synchronized(lock) {
            placed.remove(callId)?.destroy()
            ringing.remove(callId)
        }
    }

    override suspend fun start(connectionId: String, sdp: String, suites: List<MediaSuite>): PlacedCall {
        synchronized(lock) { starting++ }
        var held: List<CallEvent.Answer> = emptyList()
        val call = try {
            gateway.start(connectionId, sdp, suites).also { c ->
                synchronized(lock) {
                    placed[c.callId] = c
                    while (placed.size > MAX_KEPT) placed.remove(placed.keys.first())?.destroy()
                }
            }
        } catch (e: VaultOpException) {
            throw CallRefusedException(e.code, e)
        } catch (e: CallMessageException) {
            throw CallSecurityException("call.start", e)
        } finally {
            synchronized(lock) {
                starting--
                held = heldAnswers.toList()
                if (starting == 0) heldAnswers.clear()
            }
        }
        // An answer that overtook the vault's answer to `call.start`: checked now, as any answer.
        for (e in held) if (e.callId == call.callId) answer(e)?.let { late.trySend(it) }
        return PlacedCall(call.callId, call.exp, call.ice.servers)
    }

    override suspend fun answer(callId: String, sdp: String, suites: List<MediaSuite>): MediaKeys {
        val call = synchronized(lock) { ringing.remove(callId) } ?: throw CallSecurityException("no verified offer for this call")
        // The suite was fixed when the offer was checked; an engine that cannot do it must not answer.
        if (call.negotiated.chosen !in suites) throw CallSecurityException("the engine cannot do the negotiated suite")
        return try {
            handOver(gateway.answer(call, sdp))
        } catch (e: CallMessageException) {
            throw CallSecurityException("call.answer", e)
        }
    }

    override suspend fun ringing(callId: String) = gateway.ringing(callId)

    override suspend fun ice(callId: String, candidates: List<CallCandidate>) = gateway.ice(callId, candidates)

    override suspend fun end(callId: String, reason: String) {
        forget(callId)
        gateway.end(callId, reason)
    }

    private companion object {
        /** One call at a time: anything beyond a few is left over from calls that ended without a word. */
        const val MAX_KEPT = 4
    }
}
