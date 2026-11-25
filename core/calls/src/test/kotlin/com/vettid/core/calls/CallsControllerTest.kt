@file:OptIn(MadeByCallApi::class)
@file:Suppress("MaxLineLength") // One record a line.

package com.vettid.core.calls

import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallOutcome
import com.vettid.core.data.calls.NoCallAccess
import com.vettid.core.vault.AcceptedCall
import com.vettid.core.vault.CallCandidate
import com.vettid.core.vault.CallEvent
import com.vettid.core.vault.CallKeys
import com.vettid.core.vault.CallRecord
import com.vettid.core.vault.CallShareFaults
import com.vettid.core.vault.IncomingCall
import com.vettid.core.vault.MadeByCallApi
import com.vettid.core.vault.OutgoingCall
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One class: every test shares the fakes and the helpers.
class CallsControllerTest {
    private class Gateway : CallGateway {
        val channel = Channel<CallEvent>(Channel.UNLIMITED)
        override val events: Flow<CallEvent> = channel.receiveAsFlow()
        val sent = mutableListOf<String>()
        var list: () -> List<CallRecord> = { emptyList() }

        /** The entries of `call.list` that did not read (GC #7718). */
        var skipped: List<com.vettid.core.vault.SkippedCall> = emptyList()

        /** (C2) The list's `v` and `now`: a C2 vault with both set. */
        var version: Int? = null
        var vaultNow: Instant? = null
        var listGate: suspend () -> Unit = {}

        /** When set, `call.start`'s answer comes only once the test lets it. */
        var startGate: Channel<Unit>? = null

        /** The signalling faults the controller gave this gateway (CALLS-PLAN §13.4 step 8), read as a message would. */
        var faults: () -> CallShareFaults = { CallShareFaults.NONE }

        override fun withFaults(faults: () -> CallShareFaults): CallGateway = also { this.faults = faults }

        override suspend fun start(connectionId: String, sdp: String, suites: Collection<MediaSuite>, peerIk: ByteArray?): OutgoingCall {
            sent += "start($connectionId)" + (if (peerIk != null) " form2" else "")
            startGate?.receive()
            val ice = CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList())
            return OutgoingCall(CALL_ID, connectionId, "audio", T0.plusSeconds(45), ice, listOf(1), KemPrivateKey.generate())
        }

        var incoming = false

        /** (C2) The members of a form 2 offer; null: the offer is form 1. */
        var incomingParties: CallWire.Parties? = null
        var endGate: Channel<Unit>? = null

        override suspend fun incoming(event: VaultMessage, pinnedFingerprint: String, suites: Collection<MediaSuite>): IncomingCall {
            check(incoming) { "unused" }
            val ice = CallWire.IceConfig(CALL_ID, T0.plusSeconds(600), emptyList())
            val n = com.vettid.core.crypto.call.CallMedia.Negotiated(MediaSuite.AES_128_GCM, listOf(1))
            return IncomingCall(
                CALL_ID, "conn-1", "audio", "v=0", T0.plusSeconds(45), Duration.ofSeconds(45), ice, n, ByteArray(1216), incomingParties,
            )
        }

        /** Answers and keep-alives, in the order they went out. */
        val order = mutableListOf<String>()

        val answeredWithKeepalive = mutableListOf<Boolean>()

        override suspend fun answer(call: IncomingCall, sdp: String, keepalive: Boolean): CallKeys {
            synchronized(order) { order += "answer" }
            answeredWithKeepalive += keepalive
            return CallKeys(MediaSuite.AES_128_GCM, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        }

        val keepalives = mutableListOf<String>()

        override suspend fun keepalive(callId: String) {
            synchronized(keepalives) { keepalives += callId }
            synchronized(order) { order += "keepalive" }
        }

        override suspend fun accept(call: OutgoingCall, event: VaultMessage, pinnedFingerprint: String): AcceptedCall =
            AcceptedCall(call.callId, "v=0", CallKeys(MediaSuite.AES_128_GCM, ByteArray(32) { 5 }, ByteArray(32) { 6 }))

        val ringings = mutableListOf<String>()

        override suspend fun ringing(callId: String) {
            ringings += callId
        }

        override suspend fun ice(callId: String, candidates: List<CallCandidate>) = Unit

        override suspend fun end(callId: String, reason: String) {
            endGate?.receive()
            sent += "end($callId, $reason)"
        }

        override suspend fun list(limit: Int): com.vettid.core.vault.CallList {
            sent += "list($limit)"
            listGate()
            return com.vettid.core.vault.CallList(list(), skipped, version, vaultNow)
        }
        /** (C2) What `call.permission.list` answers; [permissionFailure] is thrown instead when set. */
        var permissionList: List<com.vettid.core.vault.CallPermission> = emptyList()
        var permissionFailure: Exception? = null
        var permissionReads = 0
        val permissionSets = mutableListOf<Pair<String, Boolean>>()
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val permissionChanges: Flow<String> = changes

        var permissionGate: suspend () -> Unit = {}

        override suspend fun permissions(): List<com.vettid.core.vault.CallPermission> {
            permissionReads++
            val asItWas = permissionList // the vault's answer as of when the read began
            permissionGate()
            permissionFailure?.let { throw it }
            return asItWas
        }

        var setGate: suspend () -> Unit = {}

        override suspend fun setPermission(connectionId: String, allowed: Boolean) {
            setGate()
            permissionFailure?.let { throw it }
            permissionSets += connectionId to allowed
        }
    }

    private class Vault : CallVault {
        override val unlocked = MutableStateFlow(false)

        /** The phase as a lock sets it before its hooks run, before the collector of [unlocked] has seen anything. */
        var phaseClosed = false

        override val unlockedNow: Boolean get() = unlocked.value && !phaseClosed
        override val vaultLeft = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override var updateShowing = false
        val gatings = MutableStateFlow(false)
        override var ownerCheckGated: Boolean
            get() = gatings.value
            set(v) {
                gatings.value = v
            }
        override val ownerCheckGatings: Flow<Boolean> = gatings
        override val gateway = Gateway()
        override val droppedForTime = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val recheck = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        var refreshed = 0
        var signer: String? = "vettid"
        var signerGate: suspend () -> Unit = {}

        override suspend fun signerClass(): String? {
            signerGate()
            return signer
        }

        override suspend fun refreshConnections() {
            refreshed++
        }

        override suspend fun fingerprint(connectionId: String): String? = "aaaa bbbb"

        var ik: ByteArray? = ByteArray(32) { 9 }

        override suspend fun identityKey(connectionId: String): ByteArray? = ik

        var me: String? = "dev-me"

        override suspend fun deviceId(): String? = me

        var showNames = true

        override fun name(connectionId: String): String? = "Ada Lovelace"

        override fun notificationName(connectionId: String): String? = if (showNames) name(connectionId) else null
    }

    private class Audio : CallAudio, CallTones {
        override val state: StateFlow<AudioState> = MutableStateFlow(AudioState())
        var on = false
        var ringBack = false
        var ringtone = false
        var chosen: AudioRoute? = null

        override fun start() {
            on = true
        }

        override fun setRoute(route: AudioRoute) {
            chosen = route
        }

        override fun stop() {
            on = false
        }

        override fun ringBack(on: Boolean) {
            ringBack = on
        }

        override fun ringtone(on: Boolean) {
            ringtone = on
        }
    }

    private val vault = Vault()
    private val engines = FakeEngines()
    private val audio = Audio()
    private var microphone = true
    private var services = 0

    private fun TestScope.controller(config: MediaConfig = MediaConfig(), testBuild: Boolean = false) = CallsController(
        vault, engines, backgroundScope, audio, audio, { microphone }, { services++ }, config, testBuild,
        now = { T0.plusMillis(testScheduler.currentTime) },
    )

    private fun record(id: String, state: String, direction: String = "out", started: Instant = T0, reason: String? = null, answered: Instant? = null, ended: Instant? = null) =
        CallRecord(id, "conn-1", direction, "audio", state, reason, started.toString(), answered?.toString(), ended?.toString())

    /** The controller's machine's private list [name] of calls known to have ended. */
    @Suppress("UNCHECKED_CAST")
    private fun CallsController.endedList(name: String): ArrayDeque<String> {
        val m = CallsController::class.java.getDeclaredField("machine").apply { isAccessible = true }.get(this)
        return CallMachine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(m) as ArrayDeque<String>
    }

    /** The calls known to have ended are kept across a lock and an unlock, and forgotten when the phone leaves its vault. */
    @Test
    fun theEndedCallListsAreKeptAcrossALockAndForgottenWhenThePhoneLeavesItsVault() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.endedList("endedWinners").addLast(GLARE_ID)
        c.endedList("endedOthers").addLast(OTHER_ID)
        vault.unlocked.value = false
        runCurrent()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(listOf(GLARE_ID), c.endedList("endedWinners").toList())
        assertEquals(listOf(OTHER_ID), c.endedList("endedOthers").toList())
        vault.unlocked.value = false
        runCurrent()
        vault.vaultLeft.emit(Unit)
        runCurrent()
        assertTrue(c.endedList("endedWinners").isEmpty() && c.endedList("endedOthers").isEmpty())
    }

    @Test
    fun aBuildWithoutCallsShowsNoButton() {
        assertEquals(CallAvailability.NOT_IN_BUILD, NoCallAccess.availability.value)
    }

    /**
     * Every vault the app opens has calls (Rule 3: the app requires the vault's current release, and the release gate
     * keeps [CallVault.unlocked] false until it is known current): calls are available as soon as the vault is open,
     * before the after-unlock `call.list` read has answered, and not while it is closed.
     */
    @Test
    fun callsAreAvailableOnceTheVaultIsOpen() = runTest {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        vault.gateway.listGate = { gate.receive() }
        val c = controller()
        runCurrent()
        assertEquals(CallAvailability.CLOSED, c.availability.value)
        assertTrue("a locked vault is not asked", vault.gateway.sent.isEmpty())
        c.call("conn-1")
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
        assertTrue("a closed vault has no history here", c.history("conn-1").let { it.entries.isEmpty() && !it.failed })
        assertTrue("and none is read", vault.gateway.sent.isEmpty())

        vault.unlocked.value = true
        runCurrent()
        assertEquals("the read is out, unanswered", listOf("list(200)"), vault.gateway.sent)
        assertEquals(CallAvailability.READY, c.availability.value)
        c.call("conn-1")
        runCurrent()
        assertEquals(CallPhase.CALLING, (c.state.value as CallState.Live).phase)
        assertEquals(CallAvailability.IN_CALL, c.availability.value)

        vault.unlocked.value = false
        runCurrent()
        assertEquals(CallAvailability.CLOSED, c.availability.value)
        assertEquals(CallState.Idle, c.state.value)
    }

    /** What the after-unlock read answers decides nothing of whether calls are shown: a refusal of any kind leaves them. */
    @Test
    fun aRefusedListReadLeavesCallsAvailable() = runTest {
        for (code in listOf("unsupported_type", "owner_check_required")) {
            val v = Vault()
            v.gateway.list = { throw VaultOpException("call.list", code) }
            val c = CallsController(v, FakeEngines(), backgroundScope, audio, audio, { true }, { })
            v.unlocked.value = true
            runCurrent()
            assertEquals(code, CallAvailability.READY, c.availability.value)
            assertTrue(code, c.history("conn-1").failed)
            c.call("conn-1")
            runCurrent()
            assertTrue(code, c.state.value is CallState.Live)
        }
    }

    /**
     * (C2) A call placed before the after-unlock read has said what the vault is goes in form 2 (fail closed: the current
     * vault is C2, and form 1 alone would leave a relay by a shared connection undetected), so it needs the connection's
     * identity key: without it no call is placed.
     */
    @Test
    fun aCallPlacedBeforeTheListReadIsInFormTwo() = runTest {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        vault.gateway.listGate = { gate.receive() }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue(vault.gateway.sent.toString(), vault.gateway.sent.contains("start(conn-1) form2"))
        c.hangUp()
        runCurrent()
        c.dismiss()
        vault.ik = null
        val starts = vault.gateway.sent.count { it.startsWith("start") }
        c.call("conn-1")
        runCurrent()
        assertEquals("no key, no call placed", starts, vault.gateway.sent.count { it.startsWith("start") })
        assertTrue(c.state.value !is CallState.Live)
    }

    @Test
    fun anUnreachableVaultIsReadAgainAndCallsStayAvailable() = runTest {
        var failures = 2
        vault.gateway.list = { if (failures-- > 0) throw java.io.IOException("relay") else emptyList() }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallAvailability.READY, c.availability.value)
        assertEquals(1, vault.gateway.sent.count { it.startsWith("list") })
        advanceTimeBy(7_000)
        assertEquals("tried again until it read", 3, vault.gateway.sent.count { it.startsWith("list") })
        assertEquals(CallAvailability.READY, c.availability.value)
    }

    @Test
    fun callsLeftLiveInTheVaultByAnEarlierRunAreEndedAtUnlock() = runTest {
        vault.gateway.list = {
            listOf(
                record("A", "active", "in"), // the app died during this call: the vault would stay busy for 12 hours
                record("B", "ringing", "out", T0.minusSeconds(300)), // an outgoing call nobody timed out
                record("C", "ringing", "in", T0.minusSeconds(300)),
                record("D", "ringing", "in", T0.minusSeconds(10)), // may be ringing right now: left alone
                record("E", "ended", "out", reason = "hangup"),
            )
        }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(listOf("list(200)", "end(A, failed)", "end(B, timeout)", "end(C, failed)"), vault.gateway.sent)
        assertEquals(3, c.diagnostics.value.staleEnded)
    }

    @Test
    fun noCallWithoutTheMicrophone() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        var asked = 0
        backgroundScope.launch { c.microphoneNeeded.collect { asked++ } }
        runCurrent()
        microphone = false
        c.call("conn-1")
        runCurrent()
        assertEquals(1, asked)
        assertEquals(CallState.Idle, c.state.value)
        assertTrue(engines.created.isEmpty())
        microphone = true
        c.call("conn-1")
        runCurrent()
        assertEquals(CallPhase.CALLING, (c.state.value as CallState.Live).phase)
    }

    @Test
    fun aroundACallTheAudioTheServiceAndTheButton() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue(audio.on)
        assertTrue(services > 0)
        assertEquals(CallAvailability.IN_CALL, c.availability.value)
        assertEquals("Ada Lovelace", c.displayName("conn-1"))
        // "Show in notifications: nothing": the notification has no name; the call screen still does.
        vault.showNames = false
        assertNull(c.displayName("conn-1"))
        assertEquals("Ada Lovelace", c.screenName("conn-1"))
        vault.gateway.channel.send(CallEvent.Ringing(CALL_ID))
        runCurrent()
        assertTrue(audio.ringBack)
        c.setRoute(AudioRoute.SPEAKER)
        assertEquals(AudioRoute.SPEAKER, audio.chosen)
        // A second tap does not start a second call.
        c.call("conn-1")
        runCurrent()
        assertEquals(1, vault.gateway.sent.count { it.startsWith("start") })

        // Locking the vault ends the call.
        vault.unlocked.value = false
        runCurrent()
        // Review P1 of #128: and nothing of it is kept for the next unlock (no ended line from before the lock).
        assertEquals(CallState.Idle, c.state.value)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
        assertTrue(!audio.on && !audio.ringBack)
    }

    /** Review P1 of #128: a call that had ended, its line still on the screen, when the vault locks. */
    @Test
    fun anEndedCallIsNotShownAgainAfterTheNextUnlock() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        c.hangUp()
        runCurrent()
        assertTrue(c.state.value is CallState.Ended)
        vault.unlocked.value = false
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
    }

    /**
     * (C2, VAULT-MESSAGING §10.10 "Liveness") Against a C2 vault the sweep ends only this device's own calls, judged by
     * `exp` and the vault's `now`, never this phone's clock: an active one `failed`, its own outgoing call still ringing
     * past `exp` `timeout`. Another device's calls, and its own ringing call not yet past `exp`, are left alone.
     */
    @Test
    fun aC2VaultIsSweptByDeviceIdAndTheVaultsClock() = runTest {
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0.plusSeconds(3600) // the phone's clock says T0: only the vault's counts
        fun rec(id: String, state: String, dir: String, device: String?, exp: Instant? = null) =
            CallRecord(id, "conn-1", dir, "audio", state, null, T0.toString(), exp = exp?.toString(), deviceId = device)
        vault.gateway.list = {
            listOf(
                rec("A", "active", "in", "dev-me"),
                rec("B", "ringing", "out", "dev-me", exp = T0.plusSeconds(3000)), // past exp by the vault's clock
                rec("C", "ringing", "out", "dev-me", exp = T0.plusSeconds(3700)), // still ringing
                rec("D", "active", "out", "dev-other"), // another device's call
                rec("E", "ringing", "in", null), // an incoming call ringing has no device
            )
        }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(listOf("list(200)", "end(A, failed)", "end(B, timeout)"), vault.gateway.sent)
        assertEquals(2, c.diagnostics.value.staleEnded)
    }

    /**
     * (C2, VAULT-MESSAGING §10.10 "Permission and limits") A C2 vault's `call.permission.list` is read after the unlock and
     * again on each `sync.event{call.permission}`; an older vault has none (no switch); a lock forgets it.
     */
    @Test
    fun thePermissionIsReadAfterTheUnlockAndOnEachChange() = runTest {
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        vault.gateway.permissionList = listOf(com.vettid.core.vault.CallPermission("conn-1", allowed = false, limited = false))
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(1, vault.gateway.permissionReads)
        assertTrue(c.permissions.value.supported && c.permissions.value.known)
        assertEquals(com.vettid.core.data.calls.ConnectionCalls(allowed = false), c.permissions.value.of("conn-1"))
        assertEquals(com.vettid.core.data.calls.ConnectionCalls(), c.permissions.value.of("conn-2"))

        vault.gateway.permissionList = listOf(com.vettid.core.vault.CallPermission("conn-2", allowed = true, limited = true))
        vault.gateway.changes.emit("conn-2")
        runCurrent()
        assertEquals(2, vault.gateway.permissionReads)
        assertEquals(com.vettid.core.data.calls.ConnectionCalls(), c.permissions.value.of("conn-1"))
        assertTrue(c.permissions.value.of("conn-2").limited)

        // A read that fails keeps what was read.
        vault.gateway.permissionFailure = java.io.IOException("relay")
        vault.gateway.changes.emit("conn-2")
        runCurrent()
        assertTrue(c.permissions.value.of("conn-2").limited)
        vault.gateway.permissionFailure = null

        vault.unlocked.value = false
        runCurrent()
        assertEquals(com.vettid.core.data.calls.CallPermissions(), c.permissions.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("nothing written after the lock", com.vettid.core.data.calls.CallPermissions(), c.permissions.value)
    }

    /** A read in flight across a lock and an unlock writes nothing: the read after the unlock does. */
    @Test
    fun aReadBegunBeforeALockWritesNothingAfterIt() = runTest {
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        vault.gateway.permissionGate = { gate.await() }
        vault.gateway.permissionList = listOf(com.vettid.core.vault.CallPermission("conn-1", allowed = false, limited = false))
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(1, vault.gateway.permissionReads)
        vault.unlocked.value = false
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("the read from before the lock wrote nothing", com.vettid.core.data.calls.CallPermissions(), c.permissions.value)
    }

    /** A change interrupted by a lock writes nothing after it. */
    @Test
    fun aChangeInterruptedByALockWritesNothing() = runTest {
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        vault.gateway.setGate = { gate.await() }
        val set = backgroundScope.launch { runCatching { c.setAllowed("conn-1", false) } }
        runCurrent()
        vault.unlocked.value = false
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        set.join()
        assertEquals(com.vettid.core.data.calls.CallPermissions(), c.permissions.value)
    }

    /** A read that began before the member's own change does not flip the switch back; the read after it does count. */
    @Test
    fun aReadOlderThanTheMembersChangeIsDropped() = runTest {
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        vault.gateway.permissionGate = { gate.await() }
        vault.gateway.permissionList = emptyList() // the old read answers: allowed
        vault.gateway.changes.emit("conn-1") // a read starts, and waits
        runCurrent()
        val reads = vault.gateway.permissionReads
        c.setAllowed("conn-1", false)
        assertFalse(c.permissions.value.of("conn-1").allowed)
        vault.gateway.permissionList = listOf(com.vettid.core.vault.CallPermission("conn-1", allowed = false, limited = false))
        // The read the change asks for is held too, so that only the old read can have written in between.
        val next = kotlinx.coroutines.CompletableDeferred<Unit>()
        vault.gateway.permissionGate = { next.await() }
        gate.complete(Unit)
        runCurrent()
        assertFalse("the old read did not flip it back", c.permissions.value.of("conn-1").allowed)
        assertTrue("the read the change asked for followed", vault.gateway.permissionReads > reads)
        next.complete(Unit)
        runCurrent()
        assertFalse(c.permissions.value.of("conn-1").allowed)
    }

    @Test
    fun aVaultBeforeC2HasNoPermission() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallAvailability.READY, c.availability.value)
        assertEquals(0, vault.gateway.permissionReads)
        assertFalse(c.permissions.value.supported)
        vault.gateway.changes.emit("conn-1")
        runCurrent()
        assertEquals(0, vault.gateway.permissionReads)
        assertThrows(com.vettid.core.data.vault.VaultFailure::class.java) { kotlinx.coroutines.runBlocking { c.setAllowed("conn-1", false) } }
        assertTrue(vault.gateway.permissionSets.isEmpty())
    }

    /** Turning calls off is sent, shown at once, and read again from the vault; a refusal is a VaultFailure and changes nothing. */
    @Test
    fun turningCallsOffIsSentAndShown() = runTest {
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.setAllowed("conn-1", false)
        assertEquals(listOf("conn-1" to false), vault.gateway.permissionSets)
        assertFalse(c.permissions.value.of("conn-1").allowed)
        runCurrent()
        assertEquals("read again", 2, vault.gateway.permissionReads)

        vault.gateway.permissionFailure = com.vettid.core.vault.VaultOpException("call.permission.set", "not_found")
        val e = runCatching { c.setAllowed("conn-2", false) }.exceptionOrNull()
        assertTrue(e is com.vettid.core.data.vault.VaultFailure)
        assertTrue(c.permissions.value.of("conn-2").allowed)
    }

    /** (C2) The call's device sends `call.keepalive` once the answer is in, then every 30 s, and stops with the call. */
    @Test
    fun keepAlivesWhileTheCallLastsOnAC2Vault() = runTest {
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue("the start names the connection's key", vault.gateway.sent.contains("start(conn-1) form2"))
        assertTrue("none while it rings", vault.gateway.keepalives.isEmpty())
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        val answer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T61", type = "call.answer", ts = T0, body = """{"call_id":"$CALL_ID"}""".toByteArray())
        vault.gateway.channel.send(CallEvent.Answer(VaultMessage(answer)))
        runCurrent()
        assertEquals(listOf(CALL_ID), vault.gateway.keepalives)
        advanceTimeBy(30_001)
        assertEquals(2, vault.gateway.keepalives.size)
        c.hangUp()
        runCurrent()
        advanceTimeBy(90_000)
        assertEquals(2, vault.gateway.keepalives.size)
    }

    /**
     * The answering device's first keep-alive goes only after its `call.answer` is with the vault: one before it is
     * answered `call.end{unavailable}` by a vault that still has the call ringing, which would end the call. The
     * engine takes its time over the offer, as the real one does.
     */
    @Test
    fun theCalleesKeepAlivesFollowItsAnswer() = runTest {
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.incoming = true
        val offer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T62", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1","call_id":"$CALL_ID"}""".toByteArray())
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(offer)))
        runCurrent()
        engines.onCreate = { (it as FakeEngine).acceptOfferMs = 500 }
        c.answer()
        runCurrent()
        advanceTimeBy(100)
        assertTrue("nothing before the answer", vault.gateway.keepalives.isEmpty())
        advanceTimeBy(1_000)
        assertEquals(listOf("answer", "keepalive"), vault.gateway.order)
        assertEquals(listOf(CALL_ID), vault.gateway.keepalives)
    }

    /**
     * (C2) An offer in form 2 proves the vault C2 for that call, whatever the after-unlock read found (here an S10
     * answer): the callee answers with `keepalive` and its keep-alives go.
     */
    @Test
    fun aFormTwoOfferStartsKeepAlivesWhateverTheListReadFound() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.incoming = true
        vault.gateway.incomingParties = CallWire.Parties(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val offer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T62", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1","call_id":"$CALL_ID"}""".toByteArray())
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(offer)))
        runCurrent()
        c.answer()
        runCurrent()
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        runCurrent()
        assertTrue(c.state.value.toString(), c.state.value is CallState.Live)
        advanceTimeBy(30_001)
        assertEquals(listOf(CALL_ID, CALL_ID), vault.gateway.keepalives)
        assertEquals(listOf(true), vault.gateway.answeredWithKeepalive)
    }

    /** What the read found is forgotten at the lock: the next unlock's read decides again. */
    @Test
    fun theVaultsLevelIsForgottenAtLock() = runTest {
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.unlocked.value = false
        runCurrent()
        vault.gateway.version = null
        vault.gateway.vaultNow = null
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue(vault.gateway.sent.toString(), vault.gateway.sent.contains("start(conn-1)"))
    }

    /** The C2 sweep leaves alone a call that is live on this phone, even one the vault lists as this device's. */
    @Test
    fun theC2SweepLeavesALiveCallAlone() = runTest {
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        var asks = 0
        vault.gateway.list = {
            if (asks++ == 0) emptyList() else listOf(CallRecord(CALL_ID, "conn-1", "out", "audio", "active", null, T0.toString(), deviceId = "dev-me"))
        }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        c.forgetListReadForTest()
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals(2, asks)
        assertTrue(vault.gateway.sent.none { it.startsWith("end(") })
    }

    /** Against an S10 vault: form 1 and no keep-alive (it would answer an unknown type). */
    @Test
    fun noKeepAliveToAnS10Vault() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue(vault.gateway.sent.contains("start(conn-1)"))
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        val answer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T61", type = "call.answer", ts = T0, body = """{"call_id":"$CALL_ID"}""".toByteArray())
        vault.gateway.channel.send(CallEvent.Answer(VaultMessage(answer)))
        runCurrent()
        advanceTimeBy(60_000)
        assertTrue(vault.gateway.keepalives.isEmpty())
    }

    /** (C5) Each call that connected is counted once, direct or relayed by the stack's last report; one that never did is not. */
    @Test
    fun eachConnectedCallIsCountedDirectOrRelayed() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        suspend fun connectedCall(relayed: Boolean?) {
            c.call("conn-1")
            runCurrent()
            engines.last.relayed = relayed
            engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
            val answer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T61", type = "call.answer", ts = T0, body = """{"call_id":"$CALL_ID"}""".toByteArray())
            vault.gateway.channel.send(CallEvent.Answer(VaultMessage(answer)))
            runCurrent()
            engines.last.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
            runCurrent()
            advanceTimeBy(1_500) // a diagnostics round
            c.hangUp()
            runCurrent()
            c.dismiss()
            runCurrent()
        }
        connectedCall(relayed = true)
        connectedCall(relayed = false)
        connectedCall(relayed = null)
        // A call that never connected is not counted.
        c.call("conn-1")
        runCurrent()
        c.hangUp()
        runCurrent()
        assertEquals(CallPaths(direct = 1, relayed = 1, unknown = 1), c.callPaths.value)

        // A call the vault's lock ends (its hang-up and the dismiss handled back to back) is counted too, and by the
        // last path the stack reported even if the last report said nothing.
        c.call("conn-1")
        runCurrent()
        engines.last.relayed = true
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        val answer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T61", type = "call.answer", ts = T0, body = """{"call_id":"$CALL_ID"}""".toByteArray())
        vault.gateway.channel.send(CallEvent.Answer(VaultMessage(answer)))
        runCurrent()
        engines.last.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
        runCurrent()
        advanceTimeBy(1_500)
        engines.last.relayed = null
        advanceTimeBy(1_000)
        vault.unlocked.value = false
        runCurrent()
        assertEquals(CallPaths(direct = 1, relayed = 2, unknown = 1), c.callPaths.value)
    }

    @Test
    fun aCallEndsTwelveHoursAfterItWasAnswered() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        val answer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T61", type = "call.answer", ts = T0, body = """{"call_id":"$CALL_ID"}""".toByteArray())
        vault.gateway.channel.send(CallEvent.Answer(VaultMessage(answer)))
        runCurrent()
        engines.last.emit(MediaEvent.Connection(MediaConnection.CONNECTED))
        runCurrent()
        assertEquals(CallPhase.ACTIVE, (c.state.value as CallState.Live).phase)
        advanceTimeBy(Duration.ofHours(12).toMillis() - 1_000)
        assertEquals(CallPhase.ACTIVE, (c.state.value as CallState.Live).phase)
        advanceTimeBy(2_000)
        // The vault stops counting the call as live then, and the frame cipher's nonce must not wrap.
        assertEquals(CallEnd.HUNG_UP, (c.state.value as CallState.Ended).reason)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
    }

    @Test
    fun thePostQuantumTrialCanBeAskedForInADebugBuildOnly() = runTest {
        val faulty = MediaConfig(faults = MediaFaults(wrongSendKey = true))
        // Never what the app starts with, in any build.
        assertThrows(IllegalStateException::class.java) { controller(MediaConfig(dtlsPqcTrial = true), testBuild = true) }
        assertThrows(IllegalStateException::class.java) { controller(faulty, testBuild = true) }

        // A test build whose vault says "development": refused until the vault has said so, allowed after.
        vault.signer = "development"
        val test = controller(testBuild = true)
        runCurrent()
        assertTrue(!test.testsAllowed.value)
        assertThrows(IllegalStateException::class.java) { test.setMediaConfig(faulty) }
        vault.unlocked.value = true
        runCurrent()
        assertTrue(test.testsAllowed.value)
        test.setMediaConfig(faulty)
        test.call("conn-1")
        runCurrent()
        assertEquals("the next call's engine gets it", faulty, engines.config)
        // A lock: not known any more, so nothing of it applies until the vault has said so again.
        vault.unlocked.value = false
        runCurrent()
        assertTrue(!test.testsAllowed.value)
        assertEquals(MediaConfig(), test.mediaConfig)
        assertThrows(IllegalStateException::class.java) { test.setMediaConfig(MediaConfig(dtlsPqcTrial = true)) }
        // (C5) Relay only is a test option too.
        assertThrows(IllegalStateException::class.java) { test.setMediaConfig(MediaConfig(relayOnly = true)) }
        assertThrows(IllegalStateException::class.java) { controller(MediaConfig(relayOnly = true), testBuild = true) }
        test.setMediaConfig(MediaConfig()) // the plain configuration can always be set

        // (Step 8) The signalling faults reach the gateway only while the tests are allowed: a lock turns them off for
        // the next message even though the switch was left on.
        vault.unlocked.value = true
        runCurrent()
        val share = CallShareFaults(wrongConfirm = true)
        test.setMediaConfig(MediaConfig(share = share))
        assertEquals(share, vault.gateway.faults())
        vault.unlocked.value = false
        runCurrent()
        assertEquals(CallShareFaults.NONE, vault.gateway.faults())
    }

    @Test
    fun aTestBuildSignedWithAnyOtherKeyAndAnyBuildThatIsNotATestBuildRefuseTheFaults() = runTest {
        val faulty = MediaConfig(faults = MediaFaults(keepRemoteSsrc = true))
        for (signer in listOf("vettid", "google_play", null, "something new")) {
            val v = Vault().apply { this.signer = signer }
            val c = CallsController(v, engines, backgroundScope, audio, audio, { true }, { }, testBuild = true)
            v.unlocked.value = true
            runCurrent()
            assertTrue("$signer", !c.testsAllowed.value)
            assertThrows(IllegalStateException::class.java) { c.setMediaConfig(faulty) }
            assertEquals(MediaConfig(), c.mediaConfig)
        }
        // Not a test build: whatever the vault says.
        val v = Vault().apply { signer = "development" }
        val c = CallsController(v, engines, backgroundScope, audio, audio, { true }, { }, testBuild = false)
        v.unlocked.value = true
        runCurrent()
        assertTrue(!c.testsAllowed.value)
        assertThrows(IllegalStateException::class.java) { c.setMediaConfig(faulty) }
        assertThrows(IllegalStateException::class.java) { c.setMediaConfig(MediaConfig(dtlsPqcTrial = true)) }
        assertThrows(IllegalStateException::class.java) { c.setMediaConfig(MediaConfig(relayOnly = true)) }
        for (share in listOf(CallShareFaults(wrongConfirm = true), CallShareFaults(suitesAfterSigning = true))) {
            assertThrows("$share", IllegalStateException::class.java) { c.setMediaConfig(MediaConfig(share = share)) }
        }
    }

    /**
     * Review D3: the vault's events are listened to from before the unlock is complete, so an offer can arrive in
     * the moments before the app shows the open vault. It rings once it does, never over the unlock screen; and
     * not at all if no unlock comes.
     */
    @Test
    fun anOfferRingsOnlyOnceTheVaultIsOpenOnThisPhone() = runTest {
        val c = controller()
        runCurrent()
        vault.gateway.incoming = true
        fun offer() = CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T63", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray())))
        vault.gateway.channel.send(offer())
        advanceTimeBy(3_000)
        assertEquals("not yet: the unlock screen is still in front", CallState.Idle, c.state.value)
        assertTrue(!audio.ringtone)
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallPhase.INCOMING, (c.state.value as CallState.Live).phase)
        c.decline()
        runCurrent()
        c.dismiss()
        vault.unlocked.value = false
        runCurrent()

        // No unlock within the wait: dropped and counted, and the events behind it are not held for ever.
        vault.gateway.channel.send(offer())
        advanceTimeBy(10_001)
        assertEquals(CallState.Idle, c.state.value)
        assertEquals(1, c.diagnostics.value.refused)
    }

    /**
     * An offer rings once the vault is open, whatever the after-unlock read answered (a refusal, or no answer yet): it
     * does not wait for that read.
     */
    @Test
    fun anOfferRingsWithoutWaitingForTheListRead() = runTest {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        vault.gateway.listGate = { gate.receive() }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.incoming = true
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T72", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        runCurrent()
        assertEquals("the read is still out", 1, vault.gateway.sent.count { it.startsWith("list") })
        assertEquals(CallPhase.INCOMING, (c.state.value as CallState.Live).phase)
        assertEquals(listOf(CALL_ID), vault.gateway.ringings)
        // (C2) Answered before the read says what the vault is: with keep-alives (form 2 unless found older).
        c.answer()
        runCurrent()
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        runCurrent()
        assertEquals(listOf(true), vault.gateway.answeredWithKeepalive)
        advanceTimeBy(30_001)
        assertEquals("and its keep-alives go", listOf(CALL_ID, CALL_ID), vault.gateway.keepalives)
    }

    /** A refused after-unlock read (the daily check due) holds back no ring: the vault decides what it delivers then. */
    @Test
    fun anOfferRingsAfterARefusedListRead() = runTest {
        vault.gateway.list = { throw VaultOpException("call.list", "owner_check_required") }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.incoming = true
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T71", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        runCurrent()
        assertEquals(CallPhase.INCOMING, (c.state.value as CallState.Live).phase)
        assertEquals(0, c.diagnostics.value.refused)
    }

    /**
     * The call events are handled in order, also while an offer waits for an unlock that is completing: a hang-up that
     * arrives during the wait is handled after its offer, so the call does not ring on after the caller gave up. (Nothing
     * else is held up that matters: no call can be live on this phone while the vault is closed.)
     */
    @Test
    fun aHangUpThatArrivesWhileItsOfferWaitsEndsTheRing() = runTest {
        val c = controller()
        runCurrent()
        vault.gateway.incoming = true
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T73", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        advanceTimeBy(1_000)
        vault.gateway.channel.send(CallEvent.Ended(CALL_ID, "hangup"))
        advanceTimeBy(1_000)
        assertEquals(CallState.Idle, c.state.value)
        vault.unlocked.value = true
        runCurrent()
        // It rang (the offer was let through), then the hang-up ended it: Idle would mean it never rang.
        assertTrue("the hang-up followed its offer: ${c.state.value}", c.state.value is CallState.Ended)
        assertEquals(0, c.diagnostics.value.refused)
        // Its `call.ringing` goes from the call's session (CallMachine.ring), which the hang-up ends in the same pass
        // here, before it is sent (on a phone it may go just before the hang-up is handled).
        assertEquals(emptyList<String>(), vault.gateway.ringings)
        assertTrue(!audio.ringtone)
    }

    /** The update screen is looked at after the wait: one that came up while the offer waited for the unlock is not rung over. */
    @Test
    fun anOfferDoesNotRingOverAnUpdateScreenThatCameUpWhileItWaited() = runTest {
        val c = controller()
        runCurrent()
        vault.gateway.incoming = true
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T75", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        advanceTimeBy(1_000)
        vault.updateShowing = true
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
        assertTrue(vault.gateway.ringings.isEmpty())
        assertEquals(1, c.diagnostics.value.refused)
    }

    /**
     * During a live call an offer does not wait (the phone, busy in another call, drops it and the vault rings the
     * member's other devices): that call's own events are never held behind it.
     */
    @Test
    fun anOfferDuringALiveCallHoldsNothingUp() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue(c.state.value is CallState.Live)
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T74", type = "call.offer", ts = T0, body = """{"connection_id":"conn-2"}""".toByteArray()))))
        vault.gateway.channel.send(CallEvent.Ended(CALL_ID, "hangup"))
        runCurrent()
        assertTrue("the live call's hang-up was not held up: ${c.state.value}", c.state.value !is CallState.Live)
        // The second offer did not ring (this fake's gateway refuses every offer's check here).
        assertTrue(vault.gateway.ringings.isEmpty())
        assertEquals(1, c.diagnostics.value.refused)
    }

    /** Review of #128: while the release-update screen covers the app there is no screen to answer on: no ring. */
    @Test
    fun anOfferDoesNotRingWhileTheReleaseUpdateScreenShows() = runTest {
        val c = controller()
        vault.unlocked.value = true
        vault.updateShowing = true
        runCurrent()
        vault.gateway.incoming = true
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T64", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
        assertTrue(!audio.ringtone)
        assertEquals(1, c.diagnostics.value.refused)
    }

    /** Review D3: a read that `recheck` starts does not run beside the one the unlock started. */
    @Test
    fun oneListReadAtATime() = runTest {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        var running = 0
        var most = 0
        vault.gateway.listGate = {
            running++
            most = maxOf(most, running)
            gate.receive()
            running--
        }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.recheck.emit(Unit)
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals(1, most)
        gate.send(Unit)
        runCurrent()
        assertEquals(CallAvailability.READY, c.availability.value)
        // The rechecks that waited find the read done and do not read again.
        assertEquals(1, vault.gateway.sent.count { it.startsWith("list") })
    }

    /** Review N5: removing the gate on Answer used to fail no test. */
    @Test
    fun noAnswerWithoutTheMicrophone() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        var asked = 0
        backgroundScope.launch { c.microphoneNeeded.collect { asked++ } }
        vault.gateway.incoming = true
        val offer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T62", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray())
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(offer)))
        runCurrent()
        assertEquals(CallPhase.INCOMING, (c.state.value as CallState.Live).phase)
        assertTrue("the phone rings", audio.ringtone)
        microphone = false
        c.answer()
        runCurrent()
        assertEquals(1, asked)
        assertEquals("still ringing: nothing was answered", CallPhase.INCOMING, (c.state.value as CallState.Live).phase)
        assertTrue(engines.created.isEmpty())
        microphone = true
        c.answer()
        runCurrent()
        assertEquals(CallPhase.CONNECTING, (c.state.value as CallState.Live).phase)
    }

    /** Review N5: the sweep must never end the call that is going on. */
    @Test
    fun theSweepLeavesALiveCallAlone() = runTest {
        var asks = 0
        vault.gateway.list = {
            // The first unlock finds nothing; the vault is asked again later, during a call it holds as active.
            if (asks++ == 0) emptyList() else listOf(record(CALL_ID, "active", "out"))
        }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue(c.state.value is CallState.Live)
        // Asked again (the daily check was done, say) while the call is live.
        c.forgetListReadForTest()
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals(2, asks)
        assertTrue("the live call was not swept", vault.gateway.sent.none { it.startsWith("end(") })
        assertTrue(c.state.value is CallState.Live)
    }

    /** Review N5: a refused after-unlock read is made again when something changes, until it goes through. */
    @Test
    fun aRefusedListReadIsRepeatedWhenTheVaultMayAnswer() = runTest {
        vault.gateway.list = { throw VaultOpException("call.list", "owner_check_required") }
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(1, vault.gateway.sent.count { it.startsWith("list") })
        assertEquals(0, vault.gateway.permissionReads)
        vault.gateway.list = { emptyList() }
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals(2, vault.gateway.sent.count { it.startsWith("list") })
        assertEquals("what the read starts follows it", 1, vault.gateway.permissionReads)
        // Once read, it is not read again for every change.
        val asked = vault.gateway.sent.count { it.startsWith("list") }
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals(asked, vault.gateway.sent.count { it.startsWith("list") })
    }

    /** Review N5: the lock waits for the hang-up to be with the relay; a locked vault would not collect it. */
    @Test
    fun theCallIsEndedAndItsHangUpDeliveredBeforeTheVaultLocks() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.phaseClosed = true // the lock sets the phase before its hooks run
        c.endCallBeforeLock() // nothing live: returns at once
        // That lock ran its course; the vault is open again, so calls are placed again.
        vault.unlocked.value = false
        runCurrent()
        vault.phaseClosed = false
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        val gate = Channel<Unit>(1)
        vault.gateway.endGate = gate
        var done = false
        vault.phaseClosed = true // the lock sets the phase before its hooks run
        backgroundScope.launch {
            c.endCallBeforeLock()
            done = true
        }
        runCurrent()
        assertEquals(CallEnd.LOCKED, (c.state.value as CallState.Ended).reason)
        assertTrue("the lock still waits: the hang-up is not with the relay yet", !done)
        gate.send(Unit)
        runCurrent()
        assertTrue(done)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
    }

    /**
     * "Lock it now" whose lock an unlock here overtook before its hooks ran: the vault is open, so the hook ends nothing
     * and refuses nothing, and calls are placed as before (nothing would clear the guard otherwise).
     */
    @Test
    fun aLockOvertakenByAnUnlockLeavesCallsAlone() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        c.endCallBeforeLock()
        runCurrent()
        assertTrue("the live call goes on", c.state.value is CallState.Live)
        assertTrue(vault.gateway.sent.none { it.startsWith("end") })
        c.hangUp()
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue("and calls are placed", c.state.value is CallState.Live)
        assertEquals(2, vault.gateway.sent.count { it.startsWith("start") })
    }

    /**
     * A lock and an unlock here that the collector of `unlocked` never saw (the open phase conflated over the closed one):
     * nothing is left set, so calls are placed as soon as the phase is open again.
     */
    @Test
    fun aLockAndUnlockTheCollectorNeverSawLeaveCallsWorking() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.phaseClosed = true // the lock sets the phase before its hooks run
        c.endCallBeforeLock()
        vault.phaseClosed = false // an unlock here: the collector sees "open" throughout
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue(c.state.value is CallState.Live)
        assertTrue(vault.gateway.sent.any { it.startsWith("start") })
    }

    /** The lock's hook and the collector both see the lock (the usual case): the call is ended and its hang-up sent once. */
    @Test
    fun aLockSeenByTheHookAndTheCollectorEndsTheCallOnce() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        val gate = Channel<Unit>(1)
        vault.gateway.endGate = gate
        vault.phaseClosed = true // the lock sets the phase before its hooks run
        var done = false
        backgroundScope.launch {
            c.endCallBeforeLock()
            done = true
        }
        runCurrent()
        vault.unlocked.value = false // and the collector sees it while the hook waits
        runCurrent()
        assertTrue("the lock still waits for the hang-up", !done)
        gate.send(Unit)
        runCurrent()
        assertTrue(done)
        assertEquals(1, vault.gateway.sent.count { it == "end($CALL_ID, hangup)" })
    }

    /**
     * A call the member asked for as the lock begins (still in the machine's inbox when the lock's hook
     * finds no call) is not placed: no audio, no service, no `call.start`, though the vault still counts as open here.
     */
    @Test
    fun aCallAskedForAsTheLockBeginsIsNotPlaced() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        vault.phaseClosed = true // the lock sets the phase before its hooks run
        c.endCallBeforeLock()
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
        assertTrue(vault.gateway.sent.none { it.startsWith("start") })
    }

    /**
     * A lock while `call.start` is out: the lock waits for its answer and for the hang-up of the call it placed, so that
     * hang-up is with the relay before the vault locks (as iOS #285).
     */
    @Test
    fun aLockWaitsForACallStartStillOutAndItsHangUp() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        val start = Channel<Unit>(1)
        vault.gateway.startGate = start
        c.call("conn-1")
        runCurrent()
        assertEquals("start(conn-1)", vault.gateway.sent.last())
        val end = Channel<Unit>(1)
        vault.gateway.endGate = end
        var done = false
        vault.phaseClosed = true // the lock sets the phase before its hooks run
        backgroundScope.launch {
            c.endCallBeforeLock()
            done = true
        }
        runCurrent()
        assertEquals(CallEnd.LOCKED, (c.state.value as CallState.Ended).reason)
        assertTrue("the lock waits for call.start's answer", !done)
        start.send(Unit)
        runCurrent()
        assertTrue("then for the hang-up of the call it placed", !done)
        end.send(Unit)
        runCurrent()
        assertTrue(done)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
    }

    /** Hung up while `call.start` is out, then locked: the lock still waits for its answer and the hang-up it needs. */
    @Test
    fun aHangUpWhileTheStartIsOutThenALockWaitsForIt() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        val start = Channel<Unit>(1)
        vault.gateway.startGate = start
        c.call("conn-1")
        runCurrent()
        c.hangUp()
        runCurrent()
        assertTrue("the call is over on this phone", c.state.value !is CallState.Live)
        var done = false
        vault.phaseClosed = true // the lock sets the phase before its hooks run
        backgroundScope.launch {
            c.endCallBeforeLock()
            done = true
        }
        runCurrent()
        assertTrue("the lock waits for call.start's answer", !done)
        start.send(Unit)
        runCurrent()
        assertTrue(done)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
    }

    /** Review L8 of #127: hung up, then locked at once. The call is over here, its hang-up is not with the relay yet. */
    @Test
    fun aHangUpStillOnItsWayIsWaitedForBeforeTheVaultLocks() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        val gate = Channel<Unit>(1)
        vault.gateway.endGate = gate
        c.hangUp()
        runCurrent()
        assertTrue("the call is over on this phone", c.state.value !is CallState.Live)
        var done = false
        vault.phaseClosed = true // the lock sets the phase before its hooks run
        backgroundScope.launch {
            c.endCallBeforeLock()
            done = true
        }
        runCurrent()
        assertTrue("the lock waits for the hang-up that is still being sent", !done)
        gate.send(Unit)
        runCurrent()
        assertTrue(done)
        assertEquals("end($CALL_ID, hangup)", vault.gateway.sent.last())
    }

    @Test
    fun aWrongClockShowsInTheDiagnostics() = runTest {
        val c = controller()
        runCurrent()
        vault.droppedForTime.emit("call.offer")
        vault.droppedForTime.emit("message.new")
        runCurrent()
        assertEquals(1, c.diagnostics.value.droppedForTime)
    }

    @Test
    fun theVaultsCallListAsTheConversationShowsIt() {
        val now = T0.plusSeconds(3600)
        fun outcome(r: CallRecord) = CallsController.entry(r, now)!!
        val talked = outcome(record("A", "ended", "out", reason = "hangup", answered = T0.plusSeconds(5), ended = T0.plusSeconds(257)))
        assertEquals(CallOutcome.TALKED, talked.outcome)
        assertEquals(Duration.ofSeconds(252), talked.duration)
        assertTrue(talked.outgoing)
        val expected = listOf(
            record("B", "ended", "out", reason = "decline") to CallOutcome.DECLINED,
            record("C", "ended", "out", reason = "busy") to CallOutcome.BUSY,
            record("D", "ended", "out", reason = "timeout") to CallOutcome.NOT_ANSWERED,
            record("E", "ended", "out", reason = "failed") to CallOutcome.FAILED,
            record("F", "ended", "in", reason = "timeout") to CallOutcome.MISSED,
            record("G", "ended", "in", reason = "busy") to CallOutcome.MISSED,
            record("H", "ended", "in", reason = "decline") to CallOutcome.DECLINED_HERE,
            record("I", "ended", "in", reason = "answered_elsewhere") to CallOutcome.ANSWERED_ELSEWHERE,
            // The vault of S10 never times a ringing call out by itself: an old one is not ringing any more.
            record("J", "ringing", "in") to CallOutcome.MISSED,
            record("K", "ringing", "out") to CallOutcome.NOT_ANSWERED,
            record("L", "ringing", "in", now.minusSeconds(5)) to CallOutcome.ONGOING,
            record("M", "active", "in", answered = T0.plusSeconds(3)) to CallOutcome.ONGOING,
        )
        for ((r, want) in expected) assertEquals(r.callId, want, outcome(r).outcome)
        assertNull(CallsController.entry(record("N", "ended").copy(startedAt = "yesterday"), now))
    }

    /**
     * (GC #7718) A conversation's calls when `call.list` had entries that did not read: the good ones shown and the line
     * said (one skipped for this connection, or whose connection did not read, or one of its own without a start); none
     * skipped, no line.
     */
    @Test
    fun skippedEntriesLeaveTheRestAndSaySomeCouldNotBeShown() {
        val now = T0.plusSeconds(3600)
        val good = listOf(record("A", "ended", reason = "hangup"), record("B", "ended", reason = "busy"))
        fun h(skipped: List<com.vettid.core.vault.SkippedCall>, calls: List<CallRecord> = good) =
            CallsController.historyOf(com.vettid.core.vault.CallList(calls, skipped), "conn-1", now)
        val clean = h(emptyList())
        assertEquals(listOf("A", "B"), clean.entries.map { it.callId })
        assertFalse("none bad: no line", clean.someNotShown)
        val one = h(listOf(com.vettid.core.vault.SkippedCall("01JXBAD0000000000000000000", "conn-1")))
        assertEquals("the good ones shown", listOf("A", "B"), one.entries.map { it.callId })
        assertTrue("the line", one.someNotShown)
        assertTrue("its connection not read", h(listOf(com.vettid.core.vault.SkippedCall(null, null))).someNotShown)
        assertFalse("another connection's", h(listOf(com.vettid.core.vault.SkippedCall(null, "conn-2"))).someNotShown)
        val all = h(listOf(com.vettid.core.vault.SkippedCall(null, "conn-1"), com.vettid.core.vault.SkippedCall(null, "conn-1")), emptyList())
        assertTrue("every entry bad", all.entries.isEmpty() && all.someNotShown)
        val noStart = h(emptyList(), good + record("N", "ended").copy(startedAt = "yesterday"))
        assertEquals(listOf("A", "B"), noStart.entries.map { it.callId })
        assertTrue("one of its own without a start", noStart.someNotShown)
    }

    /** A vault with calls whose list does not read this time: the history says it failed, not that it is empty. */
    @Test
    fun aFailedHistoryReadIsNotAnEmptyHistory() = runTest {
        vault.gateway.list = { listOf(record("A", "ended", reason = "hangup")) }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallAvailability.READY, c.availability.value)
        assertFalse(c.history("conn-1").failed)
        vault.gateway.list = { throw VaultOpException("call.list", "internal") }
        val h = c.history("conn-1")
        assertTrue(h.failed)
        assertTrue(h.entries.isEmpty())
        assertFalse(h.someNotShown)
        assertEquals("counted in the diagnostics", 1, c.diagnostics.value.historyFailed)
    }

    /** (GC #7718) A list with a skipped entry still means calls, and the skipped ids are in the diagnostics only. */
    @Test
    fun aListWithASkippedEntryStillMeansCallsAndIsAuditedLocally() = runTest {
        vault.gateway.list = { listOf(record("A", "ended", reason = "hangup")) }
        vault.gateway.skipped = listOf(com.vettid.core.vault.SkippedCall("01JXBAD0000000000000000000", "conn-1"), com.vettid.core.vault.SkippedCall(null, null))
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallAvailability.READY, c.availability.value)
        assertEquals(2, c.diagnostics.value.listSkipped)
        assertEquals(listOf("01JXBAD0000000000000000000"), c.diagnostics.value.listSkippedIds)
        val h = c.history("conn-1")
        assertEquals(listOf("A"), h.entries.map { it.callId })
        assertTrue(h.someNotShown)
    }

    /**
     * (Review of 0470e984, L1 and L3) History's read updates the diagnostics too, at most [CallsController.SKIPPED_IDS_KEPT]
     * ids are kept, the diagnostics print no id, and a lock clears them.
     */
    @Test
    fun skippedIdsAreCappedUpdatedByHistoryAndClearedAtLock() = runTest {
        vault.gateway.list = { listOf(record("A", "ended", reason = "hangup")) }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertEquals(0, c.diagnostics.value.listSkipped)
        val ids = (10..34).map { "01JXBAD00000000000000000$it" }
        vault.gateway.skipped = ids.map { com.vettid.core.vault.SkippedCall(it, "conn-1") }
        c.history("conn-1")
        assertEquals("history's read is noted", 25, c.diagnostics.value.listSkipped)
        assertEquals("at most 20 ids", ids.take(20), c.diagnostics.value.listSkippedIds)
        assertFalse("no id printed", c.diagnostics.value.toString().contains("01JXBAD"))
        vault.unlocked.value = false
        runCurrent()
        assertEquals(0, c.diagnostics.value.listSkipped)
        assertEquals(emptyList<String>(), c.diagnostics.value.listSkippedIds)
    }

    /** (C2) The calls permission is not set before the after-unlock read has found the vault C2. */
    @Test
    fun noPermissionIsSetBeforeTheListReadFoundC2() = runTest {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        vault.gateway.listGate = { gate.receive() }
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        assertThrows(com.vettid.core.data.vault.VaultFailure::class.java) { kotlinx.coroutines.runBlocking { c.setAllowed("conn-1", false) } }
        assertTrue(vault.gateway.permissionSets.isEmpty())
        gate.send(Unit)
        runCurrent()
        c.setAllowed("conn-1", false)
        assertEquals(listOf("conn-1" to false), vault.gateway.permissionSets)
    }

    /** A history read that a lock overtook notes nothing, even once the vault is open again (the next opening's read does). */
    @Test
    fun aHistoryReadOvertakenByALockNotesNothing() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        val gate = Channel<Unit>(Channel.UNLIMITED)
        var reads = 0
        vault.gateway.listGate = { if (reads++ == 0) gate.receive() }
        backgroundScope.launch { c.history("conn-1") }
        runCurrent()
        vault.unlocked.value = false
        runCurrent()
        vault.gateway.skipped = listOf(com.vettid.core.vault.SkippedCall("01JXBAD0000000000000000001", "conn-1"))
        vault.unlocked.value = true
        runCurrent()
        assertEquals("the new opening's read", 1, c.diagnostics.value.listSkipped)
        vault.gateway.skipped = List(3) { com.vettid.core.vault.SkippedCall(null, "conn-1") }
        gate.send(Unit)
        runCurrent()
        assertEquals("the old history read noted nothing", 1, c.diagnostics.value.listSkipped)
    }

    /**
     * A read a recheck started is not cancelled by a lock: when it answers after the lock it applies nothing (no skipped
     * entries kept while locked, no sweep, no signer class, no C2 flag), so the next opening reads the list again.
     */
    @Test
    fun aListReadOvertakenByALockAppliesNothing() = runTest {
        vault.signer = "development"
        vault.gateway.list = { throw VaultOpException("call.list", "owner_check_required") }
        val c = controller(testBuild = true)
        vault.unlocked.value = true
        runCurrent()
        assertEquals(1, vault.gateway.sent.count { it.startsWith("list") })
        val gate = Channel<Unit>(Channel.UNLIMITED)
        var reads = 0
        vault.gateway.listGate = { if (reads++ == 0) gate.receive() }
        vault.gateway.list = { listOf(record("A", "active", "in")) }
        vault.gateway.skipped = listOf(com.vettid.core.vault.SkippedCall("01JXBAD0000000000000000002", "conn-1"))
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals("the recheck's read is out", 2, vault.gateway.sent.count { it.startsWith("list") })
        vault.unlocked.value = false
        runCurrent()
        gate.send(Unit)
        runCurrent()
        assertEquals("nothing of it kept while locked", emptyList<String>(), c.diagnostics.value.listSkippedIds)
        assertTrue("no sweep after the lock", vault.gateway.sent.none { it.startsWith("end(") })
        assertFalse(c.testsAllowed.value)
        vault.unlocked.value = true
        runCurrent()
        assertEquals("the next opening reads again", 3, vault.gateway.sent.count { it.startsWith("list") })
        assertTrue("and its own read applies", c.testsAllowed.value)
    }

    /**
     * (C2) The read answers during a live call placed before it (in form 2): the call's keep-alives go on, and the sweep
     * leaves the live call alone although the vault lists it as this device's active call.
     */
    @Test
    fun aListReadThatAnswersDuringALiveCallLeavesItAlone() = runTest {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        vault.gateway.listGate = { gate.receive() }
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        vault.gateway.list = { listOf(CallRecord(CALL_ID, "conn-1", "out", "audio", "active", null, T0.toString(), deviceId = "dev-me")) }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        c.call("conn-1")
        runCurrent()
        assertTrue(vault.gateway.sent.contains("start(conn-1) form2"))
        engines.last.emit(MediaEvent.LocalCandidate(candidate(1)))
        val answer = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T61", type = "call.answer", ts = T0, body = """{"call_id":"$CALL_ID"}""".toByteArray())
        vault.gateway.channel.send(CallEvent.Answer(VaultMessage(answer)))
        runCurrent()
        assertEquals("keep-alives before the read answers", listOf(CALL_ID), vault.gateway.keepalives)
        gate.send(Unit)
        runCurrent()
        advanceTimeBy(30_001)
        assertEquals("and after it", listOf(CALL_ID, CALL_ID), vault.gateway.keepalives)
        assertTrue("the live call was not swept", vault.gateway.sent.none { it.startsWith("end(") })
        assertTrue(c.state.value is CallState.Live)
    }

    /**
     * Owner decision a, 2026-11-17: no ring while the daily owner check is due or held. The offer is refused as any offer
     * that cannot ring (counted, no `call.ringing`, no ringtone); once the check is done an offer rings again.
     */
    @Test
    fun anOfferDoesNotRingWhileTheOwnerCheckIsDueOrHeld() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.incoming = true
        vault.ownerCheckGated = true
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T80", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
        assertTrue(vault.gateway.ringings.isEmpty())
        assertTrue(!audio.ringtone)
        assertEquals("held back, not refused", 0, c.diagnostics.value.refused)

        vault.ownerCheckGated = false // the check was done
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T81", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        runCurrent()
        assertEquals(CallPhase.INCOMING, (c.state.value as CallState.Live).phase)
        assertEquals(listOf(CALL_ID), vault.gateway.ringings)
    }

    /** A check that comes due while an offer waits for the unlock: the owner check is looked at again after the wait. */
    @Test
    fun anOfferDoesNotRingWhenTheOwnerCheckComesDueWhileItWaits() = runTest {
        val c = controller()
        runCurrent()
        vault.gateway.incoming = true
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T82", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        advanceTimeBy(1_000)
        vault.ownerCheckGated = true
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
        assertTrue(vault.gateway.ringings.isEmpty())
        assertEquals("held back, not refused", 0, c.diagnostics.value.refused)
    }

    /** An offer that comes while the check is due does not wait for the unlock at all: held back at once, nothing held up. */
    @Test
    fun anOfferWhileTheOwnerCheckIsDueIsRefusedAtOnce() = runTest {
        val c = controller()
        runCurrent()
        vault.gateway.incoming = true
        vault.ownerCheckGated = true
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T83", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        runCurrent()
        assertEquals("held back, not refused", 0, c.diagnostics.value.refused)
        vault.ownerCheckGated = false
        vault.unlocked.value = true
        runCurrent()
        assertEquals(CallState.Idle, c.state.value)
    }

    /** The recheck's read retries after a failure: not once a lock came meanwhile. */
    @Test
    fun aRetryAfterALockSendsNothing() = runTest {
        vault.gateway.list = { throw VaultOpException("call.list", "owner_check_required") }
        controller()
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.list = { throw java.io.IOException("relay") }
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals(2, vault.gateway.sent.count { it.startsWith("list") })
        vault.unlocked.value = false
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals("no read while locked", 2, vault.gateway.sent.count { it.startsWith("list") })
    }

    /** A lock during the sweep of a read a recheck started: the calls after it are not ended (S10 and C2 alike). */
    @Test
    fun aLockStopsTheSweep() = runTest {
        for (c2 in listOf(false, true)) {
            val v = Vault()
            v.gateway.list = { throw VaultOpException("call.list", "owner_check_required") }
            CallsController(v, FakeEngines(), backgroundScope, audio, audio, { true }, { }, now = { T0 })
            v.unlocked.value = true
            runCurrent()
            if (c2) {
                v.gateway.version = 2
                v.gateway.vaultNow = T0
            }
            fun rec(id: String) = CallRecord(id, "conn-1", "in", "audio", "active", null, T0.toString(), deviceId = "dev-me")
            v.gateway.list = { listOf(rec("A"), rec("B")) }
            val gate = Channel<Unit>(Channel.UNLIMITED)
            v.gateway.endGate = gate
            v.recheck.emit(Unit)
            runCurrent()
            v.unlocked.value = false
            runCurrent()
            gate.send(Unit)
            gate.send(Unit)
            runCurrent()
            assertEquals("c2=$c2", 1, v.gateway.sent.count { it.startsWith("end(") })
        }
    }

    /** A lock while the test build asks for the signer class: what it answers is not applied after the lock. */
    @Test
    fun theSignerClassIsNotAppliedAfterALock() = runTest {
        vault.signer = "development"
        vault.gateway.list = { throw VaultOpException("call.list", "owner_check_required") }
        val c = controller(testBuild = true)
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.list = { emptyList() }
        val gate = Channel<Unit>(Channel.UNLIMITED)
        vault.signerGate = { gate.receive() }
        vault.recheck.emit(Unit)
        runCurrent()
        vault.unlocked.value = false
        runCurrent()
        gate.send(Unit)
        runCurrent()
        assertFalse(c.testsAllowed.value)
    }

    /**
     * Owner decision a, 2026-11-17: a call already ringing stops ringing here when the check comes due (or the hold
     * begins). Nothing is sent, so another device may still answer; it shows as missed here.
     */
    @Test
    fun aRingingCallStopsRingingHereWhenTheOwnerCheckComesDue() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.incoming = true
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T84", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        runCurrent()
        assertEquals(CallPhase.INCOMING, (c.state.value as CallState.Live).phase)
        assertTrue(audio.ringtone)
        val sent = vault.gateway.sent.size
        vault.ownerCheckGated = true
        runCurrent()
        assertEquals(CallEnd.MISSED, (c.state.value as CallState.Ended).reason)
        assertTrue(!audio.ringtone)
        assertEquals("nothing sent: another device may answer", sent, vault.gateway.sent.size)
    }

    /** A call answered before the check came due goes on: only a ringing one stops. */
    @Test
    fun aCallAlreadyAnsweredGoesOnWhenTheOwnerCheckComesDue() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.incoming = true
        vault.gateway.channel.send(CallEvent.Offer(VaultMessage(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T85", type = "call.offer", ts = T0, body = """{"connection_id":"conn-1"}""".toByteArray()))))
        runCurrent()
        c.answer()
        runCurrent()
        vault.ownerCheckGated = true
        runCurrent()
        assertTrue(c.state.value.toString(), c.state.value is CallState.Live)
    }

    /**
     * A lock and an unlock while a read a recheck started is out: the old read must not apply to the new opening (its
     * skipped entries, its C2 flag, so that the new opening reads for itself).
     */
    @Test
    fun aReadOfAnEarlierOpeningDoesNotApplyToTheNextOne() = runTest {
        vault.gateway.list = { throw VaultOpException("call.list", "owner_check_required") }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        val oldGate = Channel<Unit>(Channel.UNLIMITED)
        val newGate = Channel<Unit>(Channel.UNLIMITED)
        var reads = 0
        vault.gateway.listGate = { if (reads++ == 0) oldGate.receive() else newGate.receive() }
        vault.gateway.version = 2
        vault.gateway.vaultNow = T0
        vault.gateway.list = { emptyList() }
        vault.gateway.skipped = listOf(com.vettid.core.vault.SkippedCall("01JXBAD0000000000000000003", "conn-1"))
        vault.recheck.emit(Unit)
        runCurrent()
        vault.unlocked.value = false
        runCurrent()
        vault.unlocked.value = true
        runCurrent()
        // The new opening waits for the old read (one read at a time); the old read answers first, as of the old opening.
        val old = vault.gateway.sent.count { it.startsWith("list") }
        oldGate.send(Unit)
        runCurrent()
        assertEquals("the old read applied nothing", emptyList<String>(), c.diagnostics.value.listSkippedIds)
        assertEquals("the new opening reads for itself", old + 1, vault.gateway.sent.count { it.startsWith("list") })
        vault.gateway.skipped = emptyList()
        newGate.send(Unit)
        runCurrent()
        assertEquals("the new read's permission read alone", 1, vault.gateway.permissionReads)
    }

    /**
     * A lock's resets cannot fall between a read's check and its writes (they share one monitor): a lock made on another
     * thread at that moment waits, and resets after the writes, so the next read is not skipped and nothing of the old
     * read is kept.
     */
    @Test
    fun aLockCannotFallBetweenAReadsCheckAndItsWrites() = runTest {
        vault.gateway.list = { throw VaultOpException("call.list", "owner_check_required") }
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.list = { emptyList() }
        vault.gateway.skipped = listOf(com.vettid.core.vault.SkippedCall("01JXBAD0000000000000000004", "conn-1"))
        var other: Thread? = null
        c.beforeApplyForTest = {
            other = Thread { c.locksResetForTest() }.also { it.start() }
            other!!.join(300) // blocked by the monitor: it cannot reset before the writes
        }
        vault.recheck.emit(Unit)
        runCurrent()
        other!!.join(5_000)
        c.beforeApplyForTest = {}
        assertEquals("the lock reset after the writes", emptyList<String>(), c.diagnostics.value.listSkippedIds)
        val reads = vault.gateway.sent.count { it.startsWith("list") }
        vault.recheck.emit(Unit)
        runCurrent()
        assertEquals("the C2 flag was reset: read again", reads + 1, vault.gateway.sent.count { it.startsWith("list") })
    }

    /** The same for a history read: its skipped entries are noted before a lock's reset, never after it. */
    @Test
    fun aLockCannotFallBetweenAHistoryReadsCheckAndItsWrite() = runTest {
        val c = controller()
        vault.unlocked.value = true
        runCurrent()
        vault.gateway.skipped = listOf(com.vettid.core.vault.SkippedCall("01JXBAD0000000000000000005", "conn-1"))
        var other: Thread? = null
        c.beforeApplyForTest = {
            other = Thread { c.locksResetForTest() }.also { it.start() }
            other!!.join(300)
        }
        c.history("conn-1")
        other!!.join(5_000)
        assertEquals(emptyList<String>(), c.diagnostics.value.listSkippedIds)
    }
}
