package com.vettid.core.calls

import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.vault.AcceptedCall
import com.vettid.core.vault.CallCandidate
import com.vettid.core.vault.CallEvent
import com.vettid.core.vault.CallKeys
import com.vettid.core.vault.CallList
import com.vettid.core.vault.CallPermission
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
import com.vettid.core.vault.callKeepalive
import com.vettid.core.vault.callList
import com.vettid.core.vault.callPermissionChange
import com.vettid.core.vault.callPermissionList
import com.vettid.core.vault.callPermissionSet
import com.vettid.core.vault.callRinging
import com.vettid.core.vault.callStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull

/**
 * `CallApi` as an interface, one function for one function, so that [VaultCallSignalling] can be tested without a
 * vault. [VaultCallGateway] is the only implementation outside tests and adds nothing: every check is in `CallApi`
 * ([callIncoming], [callAccept]), and which checks are asked for is decided in [VaultCallSignalling].
 */
interface CallGateway {
    /** Every call event, in the vault's order; offers and answers unchecked. */
    val events: Flow<CallEvent>

    /** [peerIk]: (C2) the connection's identity key, for a form 2 start with `keepalive`; null for form 1 only. */
    suspend fun start(connectionId: String, sdp: String, suites: Collection<MediaSuite>, peerIk: ByteArray? = null): OutgoingCall

    /** Verifies an offer against the connection's identity and this device's suites, or throws. */
    suspend fun incoming(event: VaultMessage, pinnedFingerprint: String, suites: Collection<MediaSuite>): IncomingCall

    /** [keepalive]: (C2) the answer says this device sends `call.keepalive`. */
    suspend fun answer(call: IncomingCall, sdp: String, keepalive: Boolean = false): CallKeys

    /** (C2) `call.keepalive` for the active call [callId]. */
    suspend fun keepalive(callId: String) {}

    /** Verifies an answer against the connection's identity, or throws. */
    suspend fun accept(call: OutgoingCall, event: VaultMessage, pinnedFingerprint: String): AcceptedCall

    suspend fun ringing(callId: String)

    suspend fun ice(callId: String, candidates: List<CallCandidate>)

    suspend fun end(callId: String, reason: String)

    suspend fun list(limit: Int): CallList

    /** (C2) `call.permission.set`. */
    suspend fun setPermission(connectionId: String, allowed: Boolean) {}

    /** (C2) `call.permission.list`: the connections that differ from the default. */
    suspend fun permissions(): List<CallPermission> = emptyList()

    /** (C2) The connections a `sync.event{kind: "call.permission"}` names, as they come. */
    val permissionChanges: Flow<String> get() = emptyFlow()
}

/**
 * [CallGateway] on the vault: nothing but the calls. [api] is asked each time, and refuses while the vault is
 * locked on this phone; [sendEnd] is the one way a `call.end` still goes out then (the hang-up of the call a lock
 * ends); [events] are the vault device's events (a new flow after the device was set up again).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultCallGateway(
    private val api: suspend () -> VaultApi,
    events: Flow<Flow<VaultMessage>>,
    private val sendEnd: suspend (callId: String, reason: String) -> Unit = { id, reason -> api().callEnd(id, reason) },
) : CallGateway {
    override val events: Flow<CallEvent> = events.flatMapLatest { callEventsIn(it) }

    override val permissionChanges: Flow<String> = events.flatMapLatest { f -> f.mapNotNull { callPermissionChange(it) } }

    override suspend fun start(connectionId: String, sdp: String, suites: Collection<MediaSuite>, peerIk: ByteArray?): OutgoingCall =
        api().callStart(connectionId, sdp, suites, peerIk = peerIk)

    override suspend fun incoming(event: VaultMessage, pinnedFingerprint: String, suites: Collection<MediaSuite>): IncomingCall =
        api().callIncoming(event, pinnedFingerprint, suites)

    override suspend fun answer(call: IncomingCall, sdp: String, keepalive: Boolean): CallKeys = api().callAnswer(call, sdp, keepalive)

    override suspend fun keepalive(callId: String) = api().callKeepalive(callId)

    override suspend fun accept(call: OutgoingCall, event: VaultMessage, pinnedFingerprint: String): AcceptedCall =
        api().callAccept(call, event, pinnedFingerprint)

    override suspend fun ringing(callId: String) = api().callRinging(callId)

    override suspend fun ice(callId: String, candidates: List<CallCandidate>) = api().callIce(callId, candidates)

    override suspend fun end(callId: String, reason: String) = sendEnd(callId, reason)

    override suspend fun list(limit: Int): CallList = api().callList(limit)

    override suspend fun setPermission(connectionId: String, allowed: Boolean) = api().callPermissionSet(connectionId, allowed)

    override suspend fun permissions(): List<CallPermission> = api().callPermissionList()
}
