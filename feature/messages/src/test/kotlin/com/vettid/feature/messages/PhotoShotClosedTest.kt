package com.vettid.feature.messages

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.vettid.core.testing.FakeAttachments
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (Rule 3) No availability probe: while the vault is closed (not open, the owner check due or held, the release gate
 * checking or pausing) a camera shot is not taken in, and a shot prepared across a clear is not kept. On an immediate main
 * dispatcher, as `viewModelScope`'s `Main.immediate`. The button itself: [PhotoButtonScreenTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhotoShotClosedTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private fun conversation(attachments: FakeAttachments): ConversationViewModel {
        val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c1", "Sam"))) }
        return ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c1")), social, social, attachments)
    }

    @Test
    fun whileClosedAShotIsRecycledAndNotPrepared() {
        val attachments = FakeAttachments().apply { closedNow = true }
        val vm = conversation(attachments)
        val shot = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        vm.onShot(shot)
        assertTrue(shot.isRecycled)
        assertFalse(vm.uiState.value.capturing)
        assertNull(vm.uiState.value.photoDraft)
        assertTrue(attachments.calls.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test
    fun aShotPreparedAcrossAClearIsWipedAndNotKept() {
        val attachments = FakeAttachments()
        val vm = conversation(attachments)
        attachments.duringPrepare = { attachments.clear() } // a lock or the hold while it was prepared
        vm.onShot(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertNull(vm.uiState.value.photoDraft)
        assertTrue("wiped", attachments.prepared.single().wiped)
        // Without a clear it is kept, as before.
        attachments.duringPrepare = null
        vm.onShot(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertEquals(1, vm.uiState.value.photoDraft!!.photos.size)
        vm.viewModelScope.cancel()
    }

    @Test
    fun aClearRightAfterTheOpenCheckVoidsTheShot() {
        val attachments = FakeAttachments()
        val vm = conversation(attachments)
        // The generation is taken before the check: a clear that lands just after it still counts.
        attachments.duringClosedCheck = {
            attachments.duringClosedCheck = null
            attachments.clear()
        }
        vm.onShot(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertNull(vm.uiState.value.photoDraft)
        assertTrue(attachments.prepared.single().wiped)
        vm.viewModelScope.cancel()
    }

    @Test
    fun aShotCancelledWhilePreparedNeverBecomesADraft() {
        val attachments = FakeAttachments()
        val vm = conversation(attachments)
        attachments.duringPrepare = { vm.cancelPhotos() } // the X while it is prepared
        vm.onShot(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertNull(vm.uiState.value.photoDraft)
        assertTrue("wiped", attachments.prepared.single().wiped)
        // The next shot is not taken for cancelled.
        attachments.duringPrepare = null
        vm.onShot(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertEquals(1, vm.uiState.value.photoDraft!!.photos.size)
        vm.viewModelScope.cancel()
    }

    @Test
    fun cancelStopsAShotsPreparation() {
        val attachments = FakeAttachments()
        val vm = conversation(attachments)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        attachments.prepareGate = gate
        vm.onShot(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertTrue(vm.uiState.value.photoDraft!!.preparing)
        vm.cancelPhotos()
        gate.complete(Unit)
        assertNull(vm.uiState.value.photoDraft)
        assertTrue("the preparation was stopped, nothing made", attachments.prepared.isEmpty())
        vm.viewModelScope.cancel()
    }
}
