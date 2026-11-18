// Expected call lists stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.feature.messages

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeAttachments
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * (Rule 3) Photos with no availability probe: every vault the app talks to has them. While the vault is closed (not open,
 * the owner check due or held, the release gate checking or pausing) the conversation opens no camera, reads no pick and
 * begins no send; `unsupported_type` is a failure like any other. Camera shots: [PhotoShotClosedTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationPhotosClosedTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val attachments = FakeAttachments()
    private val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c1", "Sam"))) }

    private fun conversation() = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c1")), social, social, attachments)

    private fun picks(vararg b: ByteArray): List<suspend () -> ByteArray?> = b.map { x -> suspend { x } }

    @Test
    fun anOpenConversationAsksNothingAboutPhotos() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        assertTrue("no probe, no call at all", attachments.calls.isEmpty())
        vm.onPicked(picks(byteArrayOf(1)))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.photoDraft!!.photos.size)
    }

    @Test
    fun whileClosedTheCameraDoesNotOpenAndNoPickIsRead() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        attachments.closedNow = true
        vm.startCapture()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.capturing)
        var read = false
        vm.onPicked(listOf(suspend { read = true; byteArrayOf(1) }))
        advanceUntilIdle()
        assertFalse("the pick is not read", read)
        assertNull(vm.uiState.value.photoDraft)
        assertTrue(attachments.calls.isEmpty())
        // Open again: as before.
        attachments.closedNow = false
        vm.startCapture()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.capturing)
    }

    @Test
    fun whileClosedSendBeginsNothingAndKeepsTheDraftAsItIs() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(1)))
        advanceUntilIdle()
        attachments.fail["send"] = VaultFailure(FailureKind.NO_RESPONSE)
        vm.sendPhotos()
        advanceUntilIdle()
        assertEquals(PhotoProblem.NO_ANSWER, vm.uiState.value.photoDraft!!.problem)
        attachments.calls.clear()
        attachments.closedNow = true
        vm.sendPhotos()
        advanceUntilIdle()
        assertTrue("nothing begun or sent", attachments.calls.isEmpty())
        val d = vm.uiState.value.photoDraft!!
        assertFalse(d.sending)
        assertEquals("the draft is left as it was", PhotoProblem.NO_ANSWER, d.problem)
        assertEquals(1, d.photos.size)
        attachments.closedNow = false
        vm.sendPhotos() // Retry once open: the same request
        advanceUntilIdle()
        assertEquals(listOf("send:REQ1"), attachments.calls)
        assertNull(vm.uiState.value.photoDraft)
    }

    @Test
    fun closingBetweenTwoPhotosBeginsNoMore() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(1), byteArrayOf(2)))
        advanceUntilIdle()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        attachments.sendGate = gate
        vm.sendPhotos()
        advanceUntilIdle()
        attachments.closedNow = true
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("begin:", "send:REQ1"), attachments.calls.filter { it.startsWith("begin") || it.startsWith("send") })
        val d = vm.uiState.value.photoDraft!!
        assertEquals("the second waits for Send", 1, d.photos.size)
        assertFalse(d.sending)
        assertFalse(d.inFlight)
    }

    @Test
    fun unsupportedTypeIsAFailureLikeAnyOther() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(1), byteArrayOf(2)))
        advanceUntilIdle()
        attachments.fail["send"] = VaultFailure(FailureKind.NOT_SUPPORTED, "unsupported_type")
        vm.sendPhotos()
        advanceUntilIdle()
        val d = vm.uiState.value.photoDraft!!
        assertEquals(PhotoProblem.NOT_SENT, d.problem)
        assertFalse(d.retry)
        assertEquals("that photo is dropped, the other waits", 1, d.photos.size)
        vm.sendPhotos()
        advanceUntilIdle()
        assertNull("the next one goes as before", vm.uiState.value.photoDraft)
    }

    @Test
    fun theBubblesAskAgainWhenTheVaultOpensAgain() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.photoGeneration)
        attachments.clear() // the hold's clear: the bubbles drop their thumbnails and are refused
        advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.photoGeneration)
        attachments.reopenedFlow.value++ // the check passed under its cover
        advanceUntilIdle()
        assertEquals("a new generation, never one already used", 2L, vm.uiState.value.photoGeneration)
        attachments.clear()
        advanceUntilIdle()
        assertEquals(3L, vm.uiState.value.photoGeneration)
    }

    @Test
    fun aSendCarriesTheMarkReadBeforeTheOpenCheck() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(1)))
        advanceUntilIdle()
        // A clear that lands right after the open check (a sign-out drops the session before the phase changes).
        attachments.duringClosedCheck = {
            attachments.duringClosedCheck = null
            attachments.clear()
        }
        vm.sendPhotos()
        advanceUntilIdle()
        assertEquals("the mark from before the clear, so the manager ends the photo", listOf(0L), attachments.beginMarks)
    }

    @Test
    fun aRefusalWhileAPhotoIsOnItsWayEndsThatPhoto() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(1), byteArrayOf(2)))
        advanceUntilIdle()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        attachments.sendGate = gate
        vm.sendPhotos()
        advanceUntilIdle()
        attachments.closedNow = true // the owner check comes due while the first photo is on its way
        gate.complete(Unit)
        advanceUntilIdle()
        val d = vm.uiState.value.photoDraft!!
        assertEquals(PhotoProblem.NOT_SENT, d.problem)
        assertFalse(d.retry)
        assertFalse(d.sending)
        assertEquals("that photo ends, the other waits", 1, d.photos.size)
        assertEquals(1, attachments.calls.count { it.startsWith("begin") })
    }

    @Test
    fun aPickCancelledWhilePreparedNeverBecomesADraft() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        attachments.duringPrepare = { vm.cancelPhotos() }
        vm.onPicked(picks(byteArrayOf(1)))
        advanceUntilIdle()
        assertNull(vm.uiState.value.photoDraft)
        assertTrue("wiped", attachments.prepared.single().wiped)
    }
}
