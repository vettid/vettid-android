package com.vettid.app.ui

import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.presence.PresenceState
import com.vettid.core.data.social.InviteLinkInbox
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.wallet.NoWalletAccess
import com.vettid.core.notify.NotificationOpenInbox
import com.vettid.core.notify.NotifyVisibility
import com.vettid.core.testing.FakeFeed
import com.vettid.core.testing.FakePresence
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import com.vettid.core.testing.FakeVotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The avatar sheet's status pick (ANDROID-PLAN §4 "Presence"): a refused pick is not silent. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellPresenceTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val presence = FakePresence()

    private fun vm(): ShellViewModel {
        val social = FakeSocial()
        return ShellViewModel(
            social, social, InviteLinkInbox(), FakeVault(), FakeFeed(), InMemoryPreferencesRepository(), NotificationOpenInbox(),
            NotifyVisibility(), FakeVotes(), NoWalletAccess, presence,
        )
    }

    @Test
    fun aRefusedPickKeepsTheStateAndSurfacesTheFailureUntilTheNextPick() = runTest {
        val vm = vm()
        assertEquals(PresenceState.AVAILABLE, vm.presenceState.value)
        presence.fail = VaultFailure(FailureKind.NETWORK)
        vm.setPresenceState(PresenceState.INVISIBLE)
        assertEquals(PresenceState.AVAILABLE, vm.presenceState.value)
        assertEquals(FailureKind.NETWORK, vm.presenceError.value?.kind)
        // The next pick clears it and, when it works, changes the state.
        vm.setPresenceState(PresenceState.INVISIBLE)
        assertNull(vm.presenceError.value)
        assertEquals(PresenceState.INVISIBLE, vm.presenceState.value)
    }
}
