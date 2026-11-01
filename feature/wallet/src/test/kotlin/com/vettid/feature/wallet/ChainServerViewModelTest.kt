package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.chain.ChainDefaults
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainServerUrl
import com.vettid.core.chain.Network
import com.vettid.core.chain.SaveResult
import com.vettid.core.chain.UrlCheck
import com.vettid.core.chain.UrlProblem
import com.vettid.core.wallet.ChainServerSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Chain server screen's ViewModel (ANDROID-PLAN §4 "The chain source"), over a fake setting: no HTTP. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChainServerViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class FakeSettings : ChainServerSettings {
        var server: ChainServer = ChainServer.Default(ChainDefaults.TESTNET4)
        var result: SaveResult? = null
        var gate: CompletableDeferred<Unit>? = null
        var resetOk = true
        val saved = mutableListOf<Pair<Network, String>>()

        override suspend fun server(network: Network) = server

        override suspend fun save(network: Network, text: String): SaveResult {
            saved += network to text
            gate?.await()
            val r = result ?: error("no result set")
            if (r is SaveResult.Saved) server = ChainServer.Own(r.url)
            return r
        }

        override suspend fun reset(network: Network): Boolean {
            if (resetOk) server = ChainServer.Default(ChainDefaults.TESTNET4)
            return resetOk
        }
    }

    private val settings = FakeSettings()
    private val own = (ChainServerUrl.parse("https://esplora.example.org/api") as UrlCheck.Ok).url

    private fun vm(network: String = "testnet4") = ChainServerViewModel(SavedStateHandle(mapOf("network" to network)), settings)

    @Test
    fun theCurrentSettingIsRead() = runTest {
        val vm = vm()
        testScheduler.advanceUntilIdle()
        assertEquals(Network.TESTNET4, vm.state.value.network)
        assertEquals(ChainServer.Default(ChainDefaults.TESTNET4), vm.state.value.server)
    }

    @Test
    fun aNetworkTheAppDoesNotKnowIsNotOffered() = runTest {
        val vm = vm("bogus")
        testScheduler.advanceUntilIdle()
        assertEquals(ChainServer.NotOffered, vm.state.value.server)
        vm.setText("https://a.example.org")
        vm.save()
        assertTrue(settings.saved.isEmpty())
    }

    @Test
    fun saveShowsCheckingWhileTheTestRunsThenTheNewServer() = runTest {
        settings.result = SaveResult.Saved(own)
        settings.gate = CompletableDeferred()
        val vm = vm()
        testScheduler.advanceUntilIdle()
        vm.setText("https://esplora.example.org/api")
        vm.save()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.checking)
        // A second tap while it runs asks nothing more.
        vm.save()
        testScheduler.advanceUntilIdle()
        assertEquals(1, settings.saved.size)
        settings.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.checking)
        assertTrue(s.saved)
        assertEquals("", s.text)
        assertEquals(ChainServer.Own(own), s.server)
    }

    @Test
    fun eachRefusalHasItsOwnProblemAndKeepsWhatWasTyped() = runTest {
        val vm = vm()
        testScheduler.advanceUntilIdle()
        val cases = mapOf(
            SaveResult.BadUrl(UrlProblem.NOT_HTTPS) to ServerProblem.BadUrl(UrlProblem.NOT_HTTPS),
            SaveResult.Failed(ChainError.Unreachable()) to ServerProblem.Failed,
            SaveResult.WrongNetwork(Network.MAINNET) to ServerProblem.WrongNetwork,
            SaveResult.NotWritten to ServerProblem.NotWritten,
            SaveResult.NotOffered to ServerProblem.NotOffered,
        )
        for ((result, problem) in cases) {
            settings.result = result
            vm.setText("http://x.example.org")
            vm.save()
            testScheduler.advanceUntilIdle()
            assertEquals(problem, vm.state.value.problem)
            assertEquals("http://x.example.org", vm.state.value.text)
            assertFalse(vm.state.value.saved)
        }
        // Typing again clears the problem.
        vm.setText("https://y.example.org")
        assertEquals(null, vm.state.value.problem)
    }

    @Test
    fun resetGivesTheDefaultBackOrSaysItFailed() = runTest {
        settings.server = ChainServer.Own(own)
        val vm = vm()
        testScheduler.advanceUntilIdle()
        settings.resetOk = false
        vm.reset()
        testScheduler.advanceUntilIdle()
        assertEquals(ServerProblem.ResetFailed, vm.state.value.problem)
        settings.resetOk = true
        vm.reset()
        testScheduler.advanceUntilIdle()
        assertEquals(ChainServer.Default(ChainDefaults.TESTNET4), vm.state.value.server)
        assertEquals(null, vm.state.value.problem)
    }

    @Test
    fun theStatePrintsNoServer() = runTest {
        settings.server = ChainServer.Own(own)
        val vm = vm()
        testScheduler.advanceUntilIdle()
        vm.setText("https://secret.example.org")
        assertFalse(vm.state.value.toString().contains("example.org"))
    }
}
