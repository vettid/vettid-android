package com.vettid.core.wallet

import com.vettid.core.chain.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The wallets follow the vault (ANDROID-PLAN §4 "Local state"): read while open, forgotten at lock, hold and wipe. */
class WalletLifecycleTest {
    private val ops = FakeWalletOps()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flags = WalletFlags(object : WalletFlagsFile {
        override fun load(): ByteArray? = null

        override fun save(bytes: ByteArray) = Unit
    })

    private fun manager() = WalletsManager(scope, { ops }, FakeChain(), flags, { it == Network.TESTNET4 })

    private suspend fun eventually(what: String, f: () -> Boolean) {
        repeat(100) {
            if (f()) return
            delay(20)
        }
        throw AssertionError(what)
    }

    @Test
    fun openReadsAndClosedForgets() = runBlocking {
        val w = WalletFixtures.wallet()
        ops.wallets[w.id] = w
        val open = MutableStateFlow<Int?>(null)
        val m = manager()
        m.follow(open, MutableSharedFlow())
        open.value = 1
        eventually("read when open") { m.wallets.value.size == 1 }
        assertEquals(WalletAvailability.AVAILABLE, m.availability.value)
        // Locked or held.
        open.value = null
        eventually("forgotten when closed") { m.wallets.value.isEmpty() }
        assertEquals(WalletAvailability.UNKNOWN, m.availability.value)
        scope.cancel()
    }

    @Test
    fun walletEventsReadTheListAgain() = runBlocking {
        val open = MutableStateFlow<Int?>(1)
        val events = MutableSharedFlow<Pair<String, JsonObject>>()
        val m = manager()
        m.follow(open, events)
        eventually("first read") { m.availability.value == WalletAvailability.AVAILABLE }
        val w = WalletFixtures.wallet()
        ops.wallets[w.id] = w
        events.emit("sync.event" to syncEvent("wallet.changed", w.id))
        eventually("read again after the event") { m.wallets.value.size == 1 }
        // Not a wallet event: nothing.
        val reads = ops.calls.count { it == "list" }
        events.emit("sync.event" to syncEvent("item.changed", w.id))
        delay(WalletsManager.EVENT_DEBOUNCE_MS * 2)
        assertEquals(reads, ops.calls.count { it == "list" })
        scope.cancel()
    }

    @Test
    fun aLockConflatedAwayStillForgetsTheSessionBefore() = runBlocking {
        val w = WalletFixtures.wallet()
        ops.wallets[w.id] = w
        val open = MutableStateFlow<Int?>(1)
        val closed = AtomicInteger()
        val m = manager()
        m.follow(open, MutableSharedFlow(), onClosed = { closed.incrementAndGet() })
        eventually("first read") { m.wallets.value.size == 1 }
        // A wallet the first read didn't have: seen only once the new session's read has answered (the call is recorded
        // when it starts, before its answer is in the manager).
        val second = WalletFixtures.wallet(id = "01WALLET0000000000000000AB")
        ops.wallets[second.id] = second
        // Locked and opened again so fast that the closed value never reached the follower: a new session all the same.
        open.value = 2
        eventually("closed and read again") { closed.get() == 1 && m.wallets.value.size == 2 }
        scope.cancel()
    }

    @Test
    fun aWipeMovesTheGenerationAndRunsEveryClearEvenIfOneThrows() {
        val wipe = WalletWipe()
        var cleared = 0
        wipe.register { error("first fails") }
        wipe.register { cleared++ }
        val e = runCatching { wipe.onWipe() }.exceptionOrNull()
        assertEquals("first fails", e?.message)
        assertEquals(1, cleared)
        assertEquals(1L, wipe.generation)
        val m = manager()
        wipe.register(m::clear)
        runCatching { wipe.onWipe() }
        assertEquals(2L, wipe.generation)
        assertTrue(m.wallets.value.isEmpty())
    }
    @Test
    fun aCloseIsNotHeldBackByAReadInFlightAndRunsItsHook() = runBlocking {
        val w = WalletFixtures.wallet()
        ops.wallets[w.id] = w
        ops.listGate = CompletableDeferred()
        val open = MutableStateFlow<Int?>(1)
        val closed = AtomicInteger()
        val m = manager()
        m.follow(open, MutableSharedFlow(), onClosed = { closed.incrementAndGet() })
        eventually("the read started") { ops.calls.contains("list") }
        // The vault closes while `wallet.list` is unanswered: the close runs at once, and the late answer is dropped.
        open.value = null
        eventually("closed") { closed.get() == 1 }
        ops.listGate!!.complete(Unit)
        delay(100)
        assertTrue(m.wallets.value.isEmpty())
        scope.cancel()
    }

    @Test
    fun anErrorInAClearIsNotRethrownAndTheOtherClearsRun() {
        val wipe = WalletWipe()
        var cleared = 0
        wipe.register { throw StackOverflowError("an Error") }
        wipe.register { cleared++ }
        wipe.onWipe()
        assertEquals(1, cleared)
    }

    @Test
    fun theFenceWaitsForAWriteInProgress() = runBlocking {
        val inSave = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved = false
        val wipe = WalletWipe()
        val f = WalletFlags(object : WalletFlagsFile {
            override fun load(): ByteArray? = null

            override fun save(bytes: ByteArray) {
                inSave.complete(Unit)
                runBlocking { release.await() }
                saved = true
            }
        }) { wipe.generation }
        val writing = async(Dispatchers.Default) { f.setBackedUp("W1") }
        inSave.await()
        // The wipe's hook: the generation moves, then the fence waits for the write already inside `save`.
        wipe.onWipe()
        val fence = async(Dispatchers.Default) { f.fence() }
        delay(100)
        assertFalse(fence.isCompleted)
        release.complete(Unit)
        fence.await()
        assertTrue(saved)
        writing.await()
        // A write that starts after the generation moved is refused.
        assertFalse(WalletFlags(object : WalletFlagsFile {
            override fun load(): ByteArray? {
                wipe.onWipe()
                return null
            }

            override fun save(bytes: ByteArray) = error("must not save")
        }) { wipe.generation }.setBackedUp("W2"))
    }
}
