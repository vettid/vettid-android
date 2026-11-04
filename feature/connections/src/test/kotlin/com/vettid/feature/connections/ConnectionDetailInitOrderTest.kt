package com.vettid.feature.connections

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.testing.FakePresence
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A connection's page opened with the connection already cached, on an immediate main dispatcher (as `viewModelScope`'s
 * `Main.immediate`): the presence query starts inside the constructor, so the visibility flow it reads must already be set.
 * Before the fix this was the same NullPointerException as the conversation's (release 27's canary).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectionDetailInitOrderTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun aCachedPersonPageOpensAndAsksPresence() {
        val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c1", "Ada", last = "Lovelace"))) }
        val presence = FakePresence()
        val route = SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1"))
        val vm = ConnectionDetailViewModel(route, social, FakeSharing(), presence = presence)
        assertEquals("c1", vm.uiState.value.connection?.id)
        assertEquals(listOf("c1"), presence.queries.distinct())
    }
}
