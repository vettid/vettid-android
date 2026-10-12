package com.vettid.core.calls

import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.vault.AcceptedCall
import com.vettid.core.vault.CallCandidate
import com.vettid.core.vault.CallEvent
import com.vettid.core.vault.CallKeys
import com.vettid.core.vault.CallRecord
import com.vettid.core.vault.IncomingCall
import com.vettid.core.vault.OutgoingCall
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.callAccept
import com.vettid.core.vault.callAnswer
import com.vettid.core.vault.callEnd
import com.vettid.core.vault.callEventsIn
import com.vettid.core.vault.callIce
import com.vettid.core.vault.callIncoming
import com.vettid.core.vault.callList
import com.vettid.core.vault.callRinging
import com.vettid.core.vault.callStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * `CallApi` as an interface, one function for one function, so that [VaultCallSignalling] can be tested without a
 * vault. [VaultCallGateway] is the only implementation outside tests and adds nothing: every check is in `CallApi`
 * ([callIncoming], [callAccept]), and which checks are asked for is decided in [VaultCallSignalling].
 */
interface CallGateway {
    /** Every call event, in the vault's order; offers and answers unchecked. */
    val events: Flow<CallEvent>

    suspend fun start(connectionId: String, sdp: String, suites: Collection<MediaSuite>): OutgoingCall

    /** Verifies an offer against the connection's identity and this device's suites, or throws. */
    suspend fun incoming(event: VaultMessage, pinnedFingerprint: String, suites: Collection<MediaSuite>): IncomingCall

    suspend fun answer(call: IncomingCall, sdp: String): CallKeys

    /** Verifies an answer against the connection's identity, or throws. */
    suspend fun accept(call: OutgoingCall, event: VaultMessage, pinnedFingerprint: String): AcceptedCall

    suspend fun ringing(callId: String)

    suspend fun ice(callId: String, candidates: List<CallCandidate>)

    suspend fun end(callId: String, reason: String)

    suspend fun list(limit: Int): List<CallRecord>
}

/**
 * [CallGateway] on the vault: nothing but the calls. [api] is asked each time, and refuses while the vault is
 * locked on this phone; [endApi] is the one way a `call.end` still goes out then (the hang-up of the call a lock
 * ends); [events] are the vault device's events (a new flow after the device was set up again).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultCallGateway(
    private val api: suspend () -> VaultApi,
    events: Flow<Flow<VaultMessage>>,
    private val endApi: suspend () -> VaultApi = api,
) : CallGateway {
    override val events: Flow<CallEvent> = events.flatMapLatest { callEventsIn(it) }

    override suspend fun start(connectionId: String, sdp: String, suites: Collection<MediaSuite>): OutgoingCall =
        api().callStart(connectionId, sdp, suites)

    override suspend fun incoming(event: VaultMessage, pinnedFingerprint: String, suites: Collection<MediaSuite>): IncomingCall =
        api().callIncoming(event, pinnedFingerprint, suites)

    override suspend fun answer(call: IncomingCall, sdp: String): CallKeys = api().callAnswer(call, sdp)

    override suspend fun accept(call: OutgoingCall, event: VaultMessage, pinnedFingerprint: String): AcceptedCall =
        api().callAccept(call, event, pinnedFingerprint)

    override suspend fun ringing(callId: String) = api().callRinging(callId)

    override suspend fun ice(callId: String, candidates: List<CallCandidate>) = api().callIce(callId, candidates)

    override suspend fun end(callId: String, reason: String) = endApi().callEnd(callId, reason)

    override suspend fun list(limit: Int): List<CallRecord> = api().callList(limit)
}
