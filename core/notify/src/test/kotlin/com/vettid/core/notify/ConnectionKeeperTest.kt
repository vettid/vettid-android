package com.vettid.core.notify

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.BackgroundVault
import com.vettid.core.data.vault.FailureKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The on-phone service's connection (ANDROID-PLAN 0.1.23, Notification modes 3 and 11), with a fake clock and network. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionKeeperTest {
    private class Vault : BackgroundVault {
        override val phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
        val calls = mutableListOf<String>()
        var held = false
        var onRefresh: () -> Unit = {}

        override fun held() = held

        override suspend fun refresh() {
            calls += "refresh"
            onRefresh()
        }

        override fun pauseRelay() {
            calls += "pause"
        }

        override fun resumeRelay() {
            calls += "resume"
        }

        override fun reconnectNow() {
            calls += "reconnect"
        }
    }

    private val network = MutableStateFlow(true)

    @Test
    fun lockedPausesTheRelayAndReadsTheStatusHourly() = runTest {
        val v = Vault().apply { phase.value = AppPhase.Locked }
        val k = ConnectionKeeper(v, network, ReconnectBackoff(random = Random(1)))
        val job = launch { k.run() }
        runCurrent()
        assertEquals(KeeperStatus.LOCKED, k.status.value)
        assertEquals(listOf("pause"), v.calls)
        advanceTimeBy(ConnectionKeeper.STATUS_EVERY_MS + 1)
        assertTrue("refresh" in v.calls)
        // Unlocked in the app: the relay connection comes back.
        v.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals("resume", v.calls.last())
        assertEquals(KeeperStatus.CONNECTED, k.status.value)
        job.cancel()
    }

    @Test
    fun noTryWithoutANetworkAndAValidatedOneReconnectsAtOnce() = runTest {
        val v = Vault().apply { phase.value = AppPhase.Unlocked }
        val k = ConnectionKeeper(v, network, ReconnectBackoff(random = Random(1)))
        network.value = false
        val job = launch { k.run() }
        runCurrent()
        assertEquals(KeeperStatus.WAITING_FOR_NETWORK, k.status.value)
        advanceTimeBy(10 * 60_000L)
        assertTrue("no attempt while there is no network", v.calls.none { it == "refresh" })
        network.value = true
        runCurrent()
        assertTrue("reconnect" in v.calls)
        assertEquals(KeeperStatus.CONNECTED, k.status.value)
        job.cancel()
    }

    @Test
    fun anUnreachableVaultIsTriedAgainWithBackoffAndNoVaultEndsTheService() = runTest {
        val v = Vault().apply { phase.value = AppPhase.Unreachable(FailureKind.NETWORK) }
        var refreshes = 0
        v.onRefresh = {
            refreshes++
            if (refreshes == 3) v.phase.value = AppPhase.SignedOut
        }
        val k = ConnectionKeeper(v, network, ReconnectBackoff(random = Random(1)))
        val job = launch { k.run() }
        runCurrent()
        assertEquals(KeeperStatus.CONNECTING, k.status.value)
        advanceTimeBy(ReconnectBackoff.MAX_MS * 3)
        runCurrent()
        assertEquals(3, refreshes)
        assertTrue("run ended: no vault", job.isCompleted)
        assertEquals("resume", v.calls.last())
    }

    @Test
    fun statusLine() {
        assertEquals(KeeperStatus.CHECK_DUE, ConnectionKeeper.statusOf(AppPhase.Unlocked, network = true, held = true))
        assertEquals(KeeperStatus.WAITING_FOR_NETWORK, ConnectionKeeper.statusOf(AppPhase.Locked, network = false, held = false))
    }
}
