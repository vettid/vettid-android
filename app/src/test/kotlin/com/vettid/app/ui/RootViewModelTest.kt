package com.vettid.app.ui

import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.lock.AppLockKeys
import com.vettid.core.data.lock.WrappedKeyFile
import com.vettid.core.data.prefs.AppLockMethod
import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.testing.FakeReleaseUpdates
import com.vettid.core.data.wipe.AppDataClear
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.Cipher

/**
 * The root's two confirmed ways out of a dead end (A6 audit N6, P4) and the notice of reset preferences: each "Start
 * over" does what its screen said and nothing else; a phase that offers none starts nothing over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val noKeys = object : AppLockKeys {
        override fun create(method: AppLockMethod) = Unit
        override fun exists() = false
        override fun encryptCipher(): Cipher = error("unused")
        override fun decryptCipher(iv: ByteArray): Cipher = error("unused")
        override fun delete() = Unit
    }
    private val noFile = object : WrappedKeyFile {
        override fun read(): ByteArray? = null
        override fun write(b: ByteArray) = Unit
        override fun delete() = Unit
    }

    /** What "Erase VettID's data on this phone" did, in order; [cleared] is the system's answer. */
    private val dataSteps = mutableListOf<String>()
    private var cleared = true
    private val clearData = AppDataClear(
        keyAliases = { listOf("k") },
        deleteKey = { dataSteps += "delete $it" },
        clearAppData = {
            dataSteps += "clear"
            cleared
        },
    )

    private fun root(vault: FakeVault, prefs: InMemoryPreferencesRepository = InMemoryPreferencesRepository()) =
        RootViewModel(vault, vault, vault, AppLock(prefs, noKeys, noFile), FakeReleaseUpdates(), prefs, clearData)

    @Test
    fun afterAWipeThatDidNotFinishTheWayOutErasesTheDataAndNothingElse() = runTest(dispatcher) {
        val vault = FakeVault(AppPhase.Unreachable(FailureKind.ERASE_FAILED))
        val vm = root(vault)
        vm.startOver()
        advanceUntilIdle()
        // Local only: the keys, then the system's clear; no session, nothing asked of the vault.
        assertEquals(listOf("delete k", "clear"), dataSteps)
        assertEquals(emptyList<String>(), vault.calls)
        assertFalse(vm.clearDataFailed.value)
    }

    @Test
    fun aRefusedClearIsSaidWithTheWayToDoItInSettings() = runTest(dispatcher) {
        cleared = false
        val vault = FakeVault(AppPhase.Unreachable(FailureKind.ERASE_FAILED))
        val vm = root(vault)
        vm.startOver()
        advanceUntilIdle()
        assertTrue(vm.clearDataFailed.value)
        assertEquals(emptyList<String>(), vault.calls)
        assertFalse(vm.retrying.value)
    }

    @Test
    fun startOverWithoutAVaultAsksTheRepositoryToForgetItAndNothingIsErased() = runTest(dispatcher) {
        val vault = FakeVault(AppPhase.Unreachable(FailureKind.NO_VAULT))
        val vm = root(vault)
        vm.startOver()
        advanceUntilIdle()
        assertEquals(listOf("forgetMissingVault"), vault.calls)
        assertEquals(AppPhase.SignedOut, vault.phase.value)
        assertFalse(vm.retrying.value)
    }

    /** Release R: the vault confirmed this phone is still its app after 7 days: told once, until acknowledged. */
    @Test
    fun aMoveThatDidNotHappenIsToldUntilAcknowledged() = runTest(dispatcher) {
        val vault = FakeVault(AppPhase.Unlocked)
        val vm = root(vault)
        assertFalse(vm.moveNotDone.value)
        vault.moveNotDone.value = true
        assertTrue(vm.moveNotDone.value)
        vm.acknowledgeMoveNotDone()
        assertFalse(vm.moveNotDone.value)
        assertTrue("acknowledgeMoveNotDone" in vault.calls)
    }

    @Test
    fun startOverWithUnreadableKeysErasesThisPhone() = runTest(dispatcher) {
        val vault = FakeVault(AppPhase.Unreachable(FailureKind.LOCAL_STATE))
        root(vault).startOver()
        advanceUntilIdle()
        assertEquals(listOf("eraseThisPhone"), vault.calls)
        assertEquals("the wipe, not the system's clear of the data", emptyList<String>(), dataSteps)
    }

    @Test
    fun noOtherPhaseStartsAnythingOver() = runTest(dispatcher) {
        listOf(
            AppPhase.Unreachable(FailureKind.NETWORK),
            AppPhase.Unreachable(FailureKind.CLOCK),
            AppPhase.Unreachable(FailureKind.UNAUTHORIZED),
            AppPhase.Locked,
            AppPhase.Unlocked,
        ).forEach { phase ->
            val vault = FakeVault(phase)
            root(vault).startOver()
            advanceUntilIdle()
            assertEquals(phase.toString(), emptyList<String>(), vault.calls)
            assertEquals(phase, vault.phase.value)
        }
        assertEquals("the data is erased only after a wipe that did not finish", emptyList<String>(), dataSteps)
    }

    @Test
    fun tryAgainReadsThePhaseOnceAtATime() = runTest(dispatcher) {
        val vault = FakeVault(AppPhase.Unreachable(FailureKind.NETWORK))
        val vm = root(vault)
        vm.retry()
        vm.retry() // a second tap while the first read runs
        assertTrue(vm.retrying.value)
        advanceUntilIdle()
        assertEquals(listOf("refresh"), vault.calls)
        assertFalse(vm.retrying.value)
    }

    @Test
    fun resetPreferencesAreToldUntilAcknowledged() = runTest(dispatcher) {
        val prefs = InMemoryPreferencesRepository(AppPreferences(wasReset = true))
        val vm = root(FakeVault(AppPhase.Locked), prefs)
        advanceUntilIdle()
        assertTrue(vm.preferencesReset.value)
        vm.acknowledgePreferencesReset()
        advanceUntilIdle()
        assertFalse(vm.preferencesReset.value)
        assertFalse(prefs.current.value.wasReset)
    }
}
