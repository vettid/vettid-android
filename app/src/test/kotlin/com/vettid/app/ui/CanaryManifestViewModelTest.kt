package com.vettid.app.ui

import com.vettid.core.data.vault.CanaryManifestInbox
import com.vettid.core.data.vault.CanaryManifestRepository
import com.vettid.core.data.vault.CanaryManifestView
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The shared document reaches the dialog of the activity the member sees: a share sheet may start a second
 * MainActivity in a task of its own, and the first one's ViewModel, left in the background, must not take it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanaryManifestViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val doc = "{\"served\":7}".encodeToByteArray()
    private val view = CanaryManifestView(serial = 7, keyId = "e9b3a403423120ac", sha256 = "00", releases = emptyList())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aBackgroundInstanceDoesNotTakeTheDocument() = runTest(dispatcher) {
        val inbox = CanaryManifestInbox()
        val background = CanaryManifestViewModel(inbox, FakeRepo(view))
        val shown = CanaryManifestViewModel(inbox, FakeRepo(view))
        // Only the started activity's UI receives; the stopped one does not (VettIdApp's repeatOnLifecycle).
        val job = launch { shown.receive() }
        inbox.offer(doc)
        advanceUntilIdle()
        assertEquals(CanaryManifestPrompt.Confirm(view), shown.prompt.value)
        assertNull(background.prompt.value)
        assertNull(inbox.document.value)
        job.cancel()
    }

    @Test
    fun aDocumentSharedBeforeTheUiStartsIsKeptForIt() = runTest(dispatcher) {
        val inbox = CanaryManifestInbox()
        val vm = CanaryManifestViewModel(inbox, FakeRepo(view))
        inbox.offer(doc)
        advanceUntilIdle()
        assertNull(vm.prompt.value)
        val job = launch { vm.receive() }
        advanceUntilIdle()
        assertEquals(CanaryManifestPrompt.Confirm(view), vm.prompt.value)
        job.cancel()
    }

    @Test
    fun aCheckOutlivesTheUiStopping() = runTest(dispatcher) {
        val inbox = CanaryManifestInbox()
        val vm = CanaryManifestViewModel(inbox, FakeRepo(view))
        val job = launch { vm.receive() }
        inbox.offer(doc)
        dispatcher.scheduler.runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertEquals(CanaryManifestPrompt.Confirm(view), vm.prompt.value)
    }

    @Test
    fun aRefusalIsShown() = runTest(dispatcher) {
        val inbox = CanaryManifestInbox()
        val vm = CanaryManifestViewModel(inbox, FakeRepo(null))
        val job = launch { vm.receive() }
        inbox.offer(doc)
        advanceUntilIdle()
        assertEquals(CanaryManifestPrompt.Refused(CanaryManifestRepository.CODE_SIGNATURE), vm.prompt.value)
        job.cancel()
    }

    /** Verifies every document as [result], or refuses it (bad signature) when null. */
    private class FakeRepo(private val result: CanaryManifestView?) : CanaryManifestRepository {
        override val canaryManifest: StateFlow<CanaryManifestView?> = MutableStateFlow(null)

        override suspend fun checkCanaryManifest(served: ByteArray): CanaryManifestView =
            result ?: throw VaultFailure(FailureKind.MANIFEST, CanaryManifestRepository.CODE_SIGNATURE)

        override suspend fun installCanaryManifest(served: ByteArray): CanaryManifestView = checkCanaryManifest(served)

        override suspend fun removeCanaryManifest() = Unit
    }
}
