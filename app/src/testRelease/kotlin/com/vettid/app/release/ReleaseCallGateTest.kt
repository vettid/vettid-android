package com.vettid.app.release

import androidx.lifecycle.SavedStateHandle
import com.vettid.app.BuildConfig
import com.vettid.app.calls.CallsModule
import com.vettid.core.calls.CallsController
import com.vettid.core.calls.MediaConfig
import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallPermissions
import com.vettid.core.testing.FakeAttachments
import com.vettid.core.testing.FakeCalls
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import com.vettid.feature.connections.ConnectionDetailRoute
import com.vettid.feature.connections.ConnectionDetailViewModel
import com.vettid.feature.messages.ConversationRoute
import com.vettid.feature.messages.ConversationViewModel
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
import org.junit.Before
import org.junit.Test

/**
 * The release's own configuration (owner decision 2026-11-14: calls in every build, release included): its BuildConfig
 * and its classpath, before R8 (the APK itself is `checkReleaseApk`'s). Only this variant compiles it
 * (`src/testRelease`), and CI runs it (`:app:testFullReleaseUnitTest`):
 * - the release binds `:core:calls`' controller (the reference below does not compile without `src/calls`);
 * - nothing of the call tests is on: `CALLS_TEST` is false and the media config is the known-good default;
 * - what a member sees is gated by the vault's answer to the `call.list` probe alone: the conversation's button, Call
 *   and the calls row show only for READY (and, during a call, IN_CALL); a vault without calls shows nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseCallGateTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun thisIsTheReleaseAndItHasCallsWithNothingOfTheTests() {
        assertEquals("release", BuildConfig.BUILD_TYPE)
        assertFalse(BuildConfig.DEBUG)
        assertFalse(BuildConfig.CALLS_TEST)
        // The release's CallAccess is the controller (src/calls), not NoCallAccess: this compiles only where it is.
        @Suppress("UNUSED_VARIABLE")
        val binding: (CallsController) -> CallAccess = CallsModule::callAccess
        assertFalse(MediaConfig().dtlsPqcTrial)
    }

    @Test
    fun theCallUiShowsOnlyOnceTheVaultSaidItHasCalls() = runTest(main) {
        val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c1", "Ada Lovelace")), emptyMap()) }
        val calls = FakeCalls(CallAvailability.UNKNOWN)
        calls.permissions.value = CallPermissions(supported = true, known = true)
        val conversation = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c1")), social, social, FakeAttachments(), calls)
        val detail = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing(), calls = calls)
        val seen = CallAvailability.entries.associateWith { a ->
            calls.availability.value = a
            advanceUntilIdle()
            Triple(conversation.uiState.value.callShown, detail.uiState.value.canCall, detail.uiState.value.calls != null)
        }
        assertEquals(
            mapOf(
                CallAvailability.NOT_IN_BUILD to Triple(false, false, false),
                CallAvailability.UNKNOWN to Triple(false, false, false),
                CallAvailability.NOT_IN_VAULT to Triple(false, false, false),
                CallAvailability.READY to Triple(true, true, true),
                CallAvailability.IN_CALL to Triple(true, false, true),
            ),
            seen,
        )
    }
}
