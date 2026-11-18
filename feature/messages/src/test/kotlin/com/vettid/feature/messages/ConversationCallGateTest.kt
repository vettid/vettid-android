package com.vettid.feature.messages

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallPermissions
import com.vettid.core.testing.FakeAttachments
import com.vettid.core.testing.FakeCalls
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Calls are in every build, release included (owner decision 2026-11-14), and every vault the app opens has them, so
 * the button follows whether the vault is open alone (READY, or a call going on). A closed vault and a build without
 * calls show nothing. (`:app:testFullReleaseUnitTest` checks the same
 * table in the release's configuration: ReleaseCallGateTest.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationCallGateTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c2", "Alex")), emptyMap()) }

    @Test
    fun theButtonShowsOnlyWhileTheVaultIsOpen() = runTest {
        val calls = FakeCalls(CallAvailability.CLOSED)
        calls.permissions.value = CallPermissions(supported = true, known = true)
        val vm = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c2")), social, social, FakeAttachments(), calls)
        val shown = CallAvailability.entries.associateWith { a ->
            calls.availability.value = a
            advanceUntilIdle()
            vm.call()
            advanceUntilIdle()
            vm.uiState.value.callShown
        }
        assertEquals(
            mapOf(
                CallAvailability.NOT_IN_BUILD to false,
                CallAvailability.CLOSED to false,
                CallAvailability.READY to true,
                CallAvailability.IN_CALL to true,
            ),
            shown,
        )
        // Only the READY tap calls (IN_CALL's button is disabled).
        assertEquals(listOf("c2"), calls.called)
    }
}
