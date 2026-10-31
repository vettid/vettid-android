package com.vettid.core.wallet

import com.vettid.core.chain.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val open = MutableStateFlow(false)
        val m = manager()
        m.follow(open, MutableSharedFlow())
        open.value = true
        eventually("read when open") { m.wallets.value.size == 1 }
        assertEquals(WalletAvailability.AVAILABLE, m.availability.value)
        // Locked or held.
        open.value = false
        eventually("forgotten when closed") { m.wallets.value.isEmpty() }
        assertEquals(WalletAvailability.UNKNOWN, m.availability.value)
        scope.cancel()
    }

    @Test
    fun walletEventsReadTheListAgain() = runBlocking {
        val open = MutableStateFlow(true)
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
}
