// Expected call lists stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.feature.messages

import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallHistoryEntry
import com.vettid.core.data.calls.CallOutcome
import com.vettid.core.testing.FakeCalls
import java.time.Duration
import java.time.Instant
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val attachments = com.vettid.core.testing.FakeAttachments()

    private val social = FakeSocial().apply {
        seed(
            listOf(FakeSocial.connection("c1", "Sam", favorite = true), FakeSocial.connection("c2", "Alex")),
            mapOf(
                "c1" to listOf(FakeSocial.message("c1", "m1", "hi", outgoing = false, read = true, minute = 1)),
                "c2" to listOf(FakeSocial.message("c2", "m2", "unread", outgoing = false, read = false, minute = 2)),
            ),
        )
    }

    @Test
    fun listsConversationsAndFiltersUnread() = runTest {
        val vm = MessagesViewModel(social, FakeVault())
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertFalse(s.noConnections)
        assertEquals(listOf("c2", "c1"), s.conversations.map { it.connection.id })
        vm.setUnreadOnly(true)
        advanceUntilIdle()
        assertEquals(listOf("c2"), vm.uiState.value.conversations.map { it.connection.id })
        assertTrue("refreshConversations" in social.calls)
    }

    @Test
    fun theSearchFiltersByNameAndTheLatestMessage() = runTest {
        // Owner request 2026-10-08: a local filter, by the connection's name and the latest message's text.
        val vm = MessagesViewModel(social, FakeVault())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.searchable)
        vm.setQuery("sam")
        advanceUntilIdle()
        assertEquals(listOf("c1"), vm.uiState.value.conversations.map { it.connection.id })
        vm.setQuery("UNREAD")
        advanceUntilIdle()
        assertEquals(listOf("c2"), vm.uiState.value.conversations.map { it.connection.id })
        vm.setQuery("nobody")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.conversations.isEmpty())
        assertTrue(vm.uiState.value.searchable)
        vm.setQuery("")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.conversations.size)
    }

    @Test
    fun aFailedRefreshShowsTheError() = runTest {
        social.fail["refreshConversations"] = VaultFailure(FailureKind.NO_RESPONSE)
        val vm = MessagesViewModel(social, FakeVault())
        advanceUntilIdle()
        assertEquals(FailureKind.NO_RESPONSE, vm.uiState.value.error)
    }

    private fun check(state: OwnerCheckState) = OwnerCheckView(state, null, 86_400, 0, hold = true, holdOffUntil = null)

    /**
     * Staging, release 19: the list loaded while the daily check was due ("Your daily check is due"); the member passed
     * the check and the error stayed until Try again. It reads again by itself when the vault leaves held or due.
     */
    @Test
    fun aListRefusedForTheDailyCheckReloadsWhenTheCheckPasses() = runTest {
        val vault = FakeVault().apply { ownerCheck.value = check(OwnerCheckState.HELD) }
        social.fail["refreshConversations"] = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        val vm = MessagesViewModel(social, vault)
        advanceUntilIdle()
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, vm.uiState.value.error)
        vault.ownerCheck.value = check(OwnerCheckState.OK)
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertEquals(2, social.calls.count { it == "refreshConversations" })
        assertEquals(listOf("c2", "c1"), vm.uiState.value.conversations.map { it.connection.id })
    }

    @Test
    fun aDueVaultWhoseHoldIsOffReloadsToo() = runTest {
        val vault = FakeVault().apply { ownerCheck.value = check(OwnerCheckState.DUE) }
        social.fail["refreshConversations"] = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        val vm = MessagesViewModel(social, vault)
        advanceUntilIdle()
        // Locked meanwhile (no view): nothing yet.
        vault.ownerCheck.value = null
        advanceUntilIdle()
        assertEquals(1, social.calls.count { it == "refreshConversations" })
        vault.ownerCheck.value = check(OwnerCheckState.OK)
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertEquals(2, social.calls.count { it == "refreshConversations" })
    }

    @Test
    fun otherFailuresAndOkToOkDoNotReload() = runTest {
        val vault = FakeVault().apply { ownerCheck.value = check(OwnerCheckState.OK) }
        social.fail["refreshConversations"] = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        val vm = MessagesViewModel(social, vault)
        advanceUntilIdle()
        // Still ok (a new deadline): no transition from held or due, no reload.
        vault.ownerCheck.value = check(OwnerCheckState.OK).copy(intervalSeconds = 3_600)
        advanceUntilIdle()
        assertEquals(1, social.calls.count { it == "refreshConversations" })
        // A failure that is not the daily check stays until Try again.
        social.fail["refreshConversations"] = VaultFailure(FailureKind.NO_RESPONSE)
        vm.refresh()
        advanceUntilIdle()
        vault.ownerCheck.value = check(OwnerCheckState.HELD)
        advanceUntilIdle()
        vault.ownerCheck.value = check(OwnerCheckState.OK)
        advanceUntilIdle()
        assertEquals(FailureKind.NO_RESPONSE, vm.uiState.value.error)
        assertEquals(2, social.calls.count { it == "refreshConversations" })
    }

    @Test
    fun conversationMarksReadSendsAndDeletes() = runTest {
        val vm = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c2")), social, social, attachments)
        advanceUntilIdle()
        assertEquals("Alex", vm.uiState.value.connection?.name)
        // Open: the unread incoming message is marked read.
        assertTrue("markRead" in social.calls)
        assertTrue(vm.uiState.value.messages.all { it.read })

        assertFalse(vm.uiState.value.canSend)
        vm.setDraft("  hello there  ")
        vm.send()
        advanceUntilIdle()
        assertEquals("hello there", social.lastText)
        assertEquals("", vm.uiState.value.draft)
        assertEquals(2, vm.uiState.value.messages.size)

        val sent = vm.uiState.value.messages.last()
        vm.askDelete(sent)
        advanceUntilIdle()
        assertEquals(sent, vm.uiState.value.deleting)
        vm.confirmDelete()
        advanceUntilIdle()
        assertNull(vm.uiState.value.deleting)
        assertEquals(1, vm.uiState.value.messages.size)
    }

    @Test
    fun aFailedSendKeepsTheDraftAndTooLongTextCannotBeSent() = runTest {
        val vm = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c1")), social, social, attachments)
        advanceUntilIdle()
        social.fail["send"] = VaultFailure(FailureKind.CONNECTION_UNAVAILABLE, "connection_unavailable")
        vm.setDraft("hello")
        vm.send()
        advanceUntilIdle()
        assertEquals("hello", vm.uiState.value.draft)
        assertEquals(FailureKind.CONNECTION_UNAVAILABLE, vm.uiState.value.error)
        vm.setDraft("x".repeat(16 * 1024 + 1))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.tooLong)
        assertFalse(vm.uiState.value.canSend)
    }

    @Test
    fun aStaleConnectionCannotBeWrittenTo() = runTest {
        social.seed(listOf(FakeSocial.connection("c3", "Jo", state = ConnectionState.STALE)))
        val vm = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c3")), social, social, attachments)
        advanceUntilIdle()
        vm.setDraft("hi")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.active)
        assertFalse(vm.uiState.value.canSend)
    }

    @Test
    fun newMessageListsActiveConnectionsFavouritesFirst() = runTest {
        social.seed(
            listOf(
                FakeSocial.connection("c1", "Zed", favorite = true),
                FakeSocial.connection("c2", "Amy"),
                FakeSocial.connection("c3", "Bo", state = ConnectionState.PENDING),
            ),
        )
        val vm = NewMessageViewModel(social)
        advanceUntilIdle()
        assertEquals(listOf("c1", "c2"), vm.uiState.value.connections.map { it.id })
    }

    // --- calls (CALLS-PLAN C1) ---

    private fun conversation(calls: FakeCalls, id: String = "c2") =
        ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to id)), social, social, attachments, calls)

    @Test
    fun theCallButtonFollowsWhatTheBuildAndTheVaultHave() = runTest {
        // A build without calls (every production build): no button, and the vault is never asked for its calls.
        val plain = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c2")), social, social, attachments)
        advanceUntilIdle()
        assertFalse(plain.uiState.value.callShown)
        plain.call()

        val calls = FakeCalls(CallAvailability.UNKNOWN)
        val vm = conversation(calls)
        advanceUntilIdle()
        assertFalse("not before the vault was asked", vm.uiState.value.callShown)
        assertEquals(0, calls.historyReads)

        calls.availability.value = CallAvailability.READY
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callShown && vm.uiState.value.callEnabled)
        vm.call()
        assertEquals(listOf("c2"), calls.called)

        // During a call the button is there and disabled; a tap does nothing.
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callShown && !vm.uiState.value.callEnabled)
        vm.call()
        assertEquals(1, calls.called.size)

        // A vault whose release has no calls: the tap says so and calls nobody.
        calls.availability.value = CallAvailability.NOT_IN_VAULT
        advanceUntilIdle()
        vm.call()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callsNotAvailable)
        assertEquals(1, calls.called.size)
        vm.dismissCallNotice()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.callsNotAvailable)
    }

    @Test
    fun theConversationListsItsCallsAndReadsThemAgainAfterACall() = runTest {
        val t = Instant.parse("2026-10-12T10:00:00Z")
        val calls = FakeCalls()
        calls.entries = listOf(
            CallHistoryEntry("k1", "c2", true, CallOutcome.TALKED, t, Duration.ofSeconds(252)),
            CallHistoryEntry("k2", "c1", false, CallOutcome.MISSED, t),
            CallHistoryEntry("k3", "c2", false, CallOutcome.ANSWERED_ELSEWHERE, t.plusSeconds(60)),
        )
        val vm = conversation(calls)
        advanceUntilIdle()
        assertEquals("this connection's calls only", listOf("k1", "k3"), vm.uiState.value.calls.map { it.callId })
        val rows = CallEntries.rows(vm.uiState.value.messages, vm.uiState.value.calls)
        assertEquals("a call answered elsewhere leaves no entry", 1, rows.count { it is ConversationRow.Call })
        assertEquals(rows.sortedBy { it.at }, rows)

        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        val unanswered = CallHistoryEntry("k4", "c2", true, CallOutcome.NOT_ANSWERED, t.plusSeconds(120))
        calls.entries = calls.entries + unanswered
        calls.availability.value = CallAvailability.READY
        advanceUntilIdle()
        assertEquals(listOf("k1", "k3", "k4"), vm.uiState.value.calls.map { it.callId })
    }

    /** Review P4 of #128: read at the moment a call ends, the vault's history may not have that ending yet. */
    @Test
    fun theCallsAreReadOnceMoreAMomentAfterACallEnded() = runTest {
        val t = Instant.parse("2026-10-12T10:00:00Z")
        val calls = FakeCalls()
        val vm = conversation(calls)
        advanceUntilIdle()
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        val before = calls.historyReads
        calls.availability.value = CallAvailability.READY
        runCurrent()
        assertEquals(before + 1, calls.historyReads)
        assertTrue("the vault had not taken the hang-up yet", vm.uiState.value.calls.isEmpty())
        calls.entries = listOf(CallHistoryEntry("k9", "c2", true, CallOutcome.TALKED, t, Duration.ofSeconds(30)))
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(before + 2, calls.historyReads)
        assertEquals(listOf("k9"), vm.uiState.value.calls.map { it.callId })
    }

    @Test
    fun aCallsLineAndItsDuration() {
        assertEquals(CallEntries.Parts(0, 4, 12), CallEntries.parts(Duration.ofSeconds(252)))
        assertEquals(R.string.messages_call_duration_min_s, CallEntries.durationFormat(CallEntries.parts(Duration.ofSeconds(252))))
        assertEquals(R.string.messages_call_duration_s, CallEntries.durationFormat(CallEntries.parts(Duration.ofSeconds(12))))
        assertEquals(R.string.messages_call_duration_h_min, CallEntries.durationFormat(CallEntries.parts(Duration.ofSeconds(3852))))
        for (o in CallOutcome.entries) {
            val none = o == CallOutcome.ANSWERED_ELSEWHERE || o == CallOutcome.ONGOING
            assertEquals(o.name, none, CallEntries.text(o) == null)
        }
    }

    @Test
    fun scrollingToTheTopLoadsOlderPagesUntilNoneRemain() = runTest {
        // VAULT-MESSAGING 0.32.0 §10.5.2: older pages one at a time, while the vault announces more.
        social.setOlder("c1", listOf(listOf(FakeSocial.message("c1", "m0", "older", outgoing = true, minute = 0))))
        val vm = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c1")), social, social, attachments)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.olderAvailable)
        vm.loadOlder()
        advanceUntilIdle()
        assertEquals(listOf("m0", "m1"), vm.uiState.value.messages.map { it.messageId })
        assertFalse(vm.uiState.value.olderAvailable)
        assertFalse(vm.uiState.value.loadingOlder)
        vm.loadOlder() // nothing older: no request
        advanceUntilIdle()
        assertEquals(1, social.calls.count { it == "loadOlder" })
    }

    @Test
    fun anOlderPageTooLargeIsShownAndKeepsTheList() = runTest {
        // §8.1: an answer that could not be sealed is `response_too_large`, never silence.
        social.setOlder("c1", listOf(listOf(FakeSocial.message("c1", "m0", "older", outgoing = true, minute = 0))))
        social.fail["loadOlder"] = VaultFailure(FailureKind.RESPONSE_TOO_LARGE, "response_too_large")
        val vm = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c1")), social, social, attachments)
        advanceUntilIdle()
        vm.loadOlder()
        advanceUntilIdle()
        assertEquals(FailureKind.RESPONSE_TOO_LARGE, vm.uiState.value.error)
        assertEquals(listOf("m1"), vm.uiState.value.messages.map { it.messageId })
        assertTrue(vm.uiState.value.olderAvailable)
        assertFalse(vm.uiState.value.loadingOlder)
    }
    private fun conversation() = ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c1")), social, social, attachments)

    @Test
    fun pickedPhotosAreSentOneByOneWithTheCaptionOnTheFirst() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.photosAvailable)
        vm.onPicked(picks(byteArrayOf(1), byteArrayOf(2)))
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.photoDraft!!.photos.size)
        vm.setPhotoCaption("  look  ")
        vm.sendPhotos()
        advanceUntilIdle()
        assertNull(vm.uiState.value.photoDraft)
        assertEquals(listOf("begin:look", "send:REQ1", "begin:", "send:REQ2"), attachments.calls.filter { it.startsWith("begin") || it.startsWith("send") })
    }

    @Test
    fun retryAfterALostAnswerReSendsTheSameRequest() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(1)))
        advanceUntilIdle()
        attachments.fail["send"] = VaultFailure(FailureKind.NO_RESPONSE)
        vm.sendPhotos()
        advanceUntilIdle()
        val d = vm.uiState.value.photoDraft!!
        assertTrue(d.retry)
        assertEquals(PhotoProblem.NO_ANSWER, d.problem)
        vm.sendPhotos() // Retry
        advanceUntilIdle()
        assertNull(vm.uiState.value.photoDraft)
        assertEquals(listOf("send:REQ1", "send:REQ1"), attachments.calls.filter { it.startsWith("send") })
        assertEquals(1, attachments.calls.count { it.startsWith("begin") })
    }

    @Test
    fun cancelAfterALostAnswerCancelsTheUpload() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(1)))
        advanceUntilIdle()
        attachments.fail["send"] = VaultFailure(FailureKind.NO_RESPONSE)
        vm.sendPhotos()
        advanceUntilIdle()
        vm.cancelPhotos()
        advanceUntilIdle()
        assertNull(vm.uiState.value.photoDraft)
        assertEquals("abandon:REQ1", attachments.calls.last()) // the discard runs outside the screen's scope
    }

    @Test
    fun aFullVaultAndAFullMailboxAreSaidApart() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(1)))
        advanceUntilIdle()
        attachments.fail["send"] = VaultFailure(FailureKind.LIMIT, com.vettid.core.data.social.AttachmentManager.CODE_QUOTA)
        vm.sendPhotos()
        advanceUntilIdle()
        assertEquals(PhotoProblem.MAILBOX_FULL, vm.uiState.value.photoDraft!!.problem)
        assertEquals("the photo stays for Send again", 1, vm.uiState.value.photoDraft!!.photos.size)
        attachments.fail["send"] = VaultFailure(FailureKind.LIMIT, "limit")
        vm.sendPhotos()
        advanceUntilIdle()
        assertEquals(PhotoProblem.VAULT_FULL, vm.uiState.value.photoDraft!!.problem)
    }

    @Test
    fun anUnreadablePictureIsSaid() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(ByteArray(0)))
        advanceUntilIdle()
        assertEquals(PhotoProblem.UNREADABLE, vm.uiState.value.photoDraft!!.problem)
        assertFalse(vm.uiState.value.photoDraft!!.canSend)
    }

    @Test
    fun withoutReleaseAThereIsNoPhotoButton() = runTest {
        attachments.availableFlow.value = false
        val vm = conversation()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.photosAvailable)
    }

    private fun picks(vararg b: ByteArray): List<suspend () -> ByteArray?> = b.map { x -> suspend { x } }

    @Test
    fun cancelWaitsWhileARequestIsOnItsWayAndNoFurtherPhotoIsBegun() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(1), byteArrayOf(2)))
        advanceUntilIdle()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        attachments.sendGate = gate
        vm.sendPhotos()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.photoDraft!!.inFlight)
        vm.cancelPhotos() // refused while the request is on its way
        assertTrue(vm.uiState.value.photoDraft != null)
        attachments.sendGate = null
        gate.complete(Unit)
        advanceUntilIdle()
        // The first photo was answered; the loop went on to the second (no cancel happened).
        assertNull(vm.uiState.value.photoDraft)
        assertEquals(2, attachments.calls.count { it.startsWith("begin") })
    }

    @Test
    fun aCancelBetweenPhotosStopsTheLoop() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))
        advanceUntilIdle()
        attachments.fail["send"] = VaultFailure(FailureKind.LIMIT, "limit") // the first is refused for good
        vm.sendPhotos()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.photoDraft!!.photos.size)
        val left = vm.uiState.value.photoDraft!!.photos
        vm.cancelPhotos()
        advanceUntilIdle()
        assertNull(vm.uiState.value.photoDraft)
        assertEquals(1, attachments.calls.count { it.startsWith("begin") })
        assertTrue(left.all { p -> p.jpeg.all { it == 0.toByte() } })
    }

    @Test
    fun aClearDropsTheDraftAndTheCamera() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(picks(byteArrayOf(5)))
        advanceUntilIdle()
        val draft = vm.uiState.value.photoDraft!!.photos
        attachments.clear() // a lock, the hold or due state
        advanceUntilIdle()
        assertNull(vm.uiState.value.photoDraft)
        assertEquals(1L, vm.uiState.value.photoGeneration)
        assertTrue(draft.all { p -> p.jpeg.all { it == 0.toByte() } })
    }

    @Test
    fun picksThatFailAreCountedAndTheOthersKept() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        vm.onPicked(listOf(suspend { byteArrayOf(1) }, suspend { null }, suspend { ByteArray(0) }))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.photoDraft!!.photos.size)
        assertEquals(2, vm.uiState.value.photoDraft!!.failedPicks)
    }

    @Test
    fun theViewerDropsItsPhotoAtAClearAndWipedBytesCannotLeave() = runTest {
        val vm = PhotoViewerViewModel(
            SavedStateHandle(mapOf(PhotoViewerRoute.ARG_CONNECTION to "c1", PhotoViewerRoute.ARG_MESSAGE to "m1")),
            attachments,
            social,
        )
        advanceUntilIdle()
        val photo = vm.uiState.value.photo!!
        assertTrue(PhotoExport.intact(photo.bytes, photo.type))
        attachments.clear()
        advanceUntilIdle()
        assertNull(vm.uiState.value.photo)
        assertFalse(PhotoExport.intact(photo.bytes, photo.type))
    }

    /**
     * (ANDROID-PLAN "Memory only, wiped at lock") A clear that lands while the photo is on its way, after the viewer
     * already dropped what it held: the photo that arrives is zeroed and never shown (found while porting to iOS).
     */
    @Test
    fun aPhotoFetchedAcrossAClearIsZeroedAndNeverShown() = runTest {
        attachments.fetchGate = kotlinx.coroutines.CompletableDeferred()
        val vm = PhotoViewerViewModel(
            SavedStateHandle(mapOf(PhotoViewerRoute.ARG_CONNECTION to "c1", PhotoViewerRoute.ARG_MESSAGE to "m1")),
            attachments,
            social,
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loading)
        attachments.clear()
        advanceUntilIdle()
        attachments.fetchGate!!.complete(Unit)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertNull(s.photo)
        assertFalse(s.loading)
        assertEquals(R.string.messages_photo_unavailable, s.problemText)
        assertTrue(attachments.handedOut.single().all { it == 0.toByte() })
    }

    /** The same for a bubble's thumbnail: one fetched across a clear is zeroed and not shown. */
    @Test
    fun aThumbnailFetchedAcrossAClearIsZeroedAndNotShown() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        attachments.fetchGate = kotlinx.coroutines.CompletableDeferred()
        val thumb = async { vm.thumbnail("m1") }
        advanceUntilIdle()
        attachments.clear()
        attachments.fetchGate!!.complete(Unit)
        assertNull(thumb.await())
        assertTrue(attachments.handedOut.single().all { it == 0.toByte() })
        // Without a clear meanwhile it is shown.
        attachments.fetchGate = null
        assertTrue(vm.thumbnail("m1") != null)
    }

    @Test
    fun aCancelWhilePicksArePreparedWipesWhatWasReady() = runTest {
        val vm = conversation()
        advanceUntilIdle()
        val never = kotlinx.coroutines.CompletableDeferred<ByteArray?>()
        vm.onPicked(listOf(suspend { byteArrayOf(7, 7) }, suspend { never.await() }))
        advanceUntilIdle()
        assertEquals(1, attachments.prepared.size)
        vm.cancelPhotos()
        advanceUntilIdle()
        assertNull(vm.uiState.value.photoDraft)
        assertTrue(attachments.prepared.single().jpeg.all { it == 0.toByte() })
    }
}
