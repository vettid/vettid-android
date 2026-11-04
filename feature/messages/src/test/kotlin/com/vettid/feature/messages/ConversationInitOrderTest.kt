package com.vettid.feature.messages

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.testing.FakeAttachments
import com.vettid.core.testing.FakePresence
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
 * A conversation opened with its connection already cached, on an immediate main dispatcher (as `viewModelScope`'s
 * `Main.immediate`): the presence query starts inside the constructor, so every field it reads must already be set. Before
 * the fix this crashed with a NullPointerException in `ConversationViewModel.<init>` (release 27's canary).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationInitOrderTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun aCachedPersonConversationOpensAndAsksPresenceOnce() {
        val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c1", "Sam"))) }
        val presence = FakePresence()
        val route = SavedStateHandle(mapOf(ConversationRoute.ARG to "c1"))
        val vm = ConversationViewModel(route, social, social, FakeAttachments(), presence = presence)
        assertEquals("c1", vm.uiState.value.connection?.id)
        assertEquals(listOf("c1"), presence.queries)
        // The screen's visibility reaches the same flow the query reads.
        vm.setVisible(false)
        vm.setVisible(true)
        assertEquals(listOf("c1", "c1"), presence.queries)
    }
}
