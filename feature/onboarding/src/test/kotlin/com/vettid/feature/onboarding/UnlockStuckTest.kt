package com.vettid.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * An unlock screen that cannot open the vault has a way forward. An ended release or a rollback (of the release or of the
 * vault's state): "Recover your vault" and support, never an erase. A phone without the vault's keys: recover first, then
 * erase. Nowhere else.
 */
@RunWith(RobolectricTestRunner::class)
class UnlockStuckTest {
    @get:Rule
    val rule = createComposeRule()

    private val asked = mutableListOf<String>()

    /** Records which action the screen asked for. */
    private val actions = Proxy.newProxyInstance(UnlockActions::class.java.classLoader, arrayOf(UnlockActions::class.java)) { p, m, args ->
        when (m.name) {
            // A recomposition compares the actions.
            "equals" -> p === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(p)
            else -> {
                asked += m.name
                null
            }
        }
    } as UnlockActions

    private val release = ReleaseView(3, "aa".repeat(48), "active", null, "")
    private val ok = UnlockUiState(loading = false, preflight = PreflightInfo(release, 3, false, false, null))
    private val keysGone = ok.copy(keysGone = true)

    private val recoverOnly = mapOf(
        "release ended" to UnlockUiState(loading = false, preflightError = FailureKind.RELEASE_ENDED),
        "release rollback" to UnlockUiState(loading = false, preflight = PreflightInfo(release, 4, false, true, null)),
        "state rollback" to ok.copy(stateRollback = true),
    )

    private val ordinary = mapOf(
        "ready" to ok,
        "network" to ok.copy(message = UnlockMessage.Failed(FailureKind.NETWORK, null)),
        "bad pin" to ok.copy(message = UnlockMessage.BadPin),
        "transfer incomplete" to ok.copy(message = UnlockMessage.Failed(FailureKind.TRANSFER_INCOMPLETE, "transfer_incomplete")),
        "release unknown" to UnlockUiState(loading = false, preflightError = FailureKind.RELEASE_UNKNOWN),
        "recovery pending" to ok.copy(recoveryPending = true),
    )

    @Test
    fun theWayForwardOfEachState() {
        for ((name, s) in recoverOnly) {
            assertTrue(name, s.recoverOnly)
            assertFalse("$name: no erase", s.eraseOffered)
            assertFalse("$name: no recover-or-erase notice", s.copy(keysGone = true).stuckNoticeShown)
        }
        assertTrue(keysGone.eraseOffered)
        assertTrue(keysGone.stuckNoticeShown)
        assertFalse(keysGone.recoverOnly)
        assertFalse("the not-recognised notice instead", keysGone.copy(notRecognised = true).stuckNoticeShown)
        assertFalse("no notice while a kept check waits", keysGone.copy(keptCheckWaiting = true).stuckNoticeShown)
        for ((name, s) in ordinary) {
            assertFalse(name, s.recoverOnly)
            assertFalse(name, s.eraseOffered)
        }
    }

    /**
     * (Owner decision a, 2026-11-19) A phone with no usable user_guid: recover-or-erase as soon as the screen opens, and no PIN
     * asked for first (none can open the vault here).
     */
    @Test
    fun noUserGuidOffersRecoverAndEraseWithNoPin() {
        val s = ok.copy(keysGone = true, noUserGuid = true)
        assertFalse(s.pinAllowed)
        assertTrue(s.eraseOffered)
        rule.setContent { UnlockContent(s, actions) }
        rule.onNodeWithTag("unlock_stuck").assertExists()
        rule.onNodeWithTag("recover_vault").assertExists()
        rule.onNodeWithTag("erase_phone").assertExists()
        // No PIN block and no "Unlock" button at all.
        rule.onNodeWithTag("unlock_pin").assertDoesNotExist()
        rule.onNodeWithText("Unlock").assertDoesNotExist()
        // With a user_guid the PIN is asked for as before.
        assertTrue(ok.pinAllowed)
    }

    /** A kept check waits: the words alone, no actions (no erase, no PIN). */
    @Test
    fun noUserGuidWhileAKeptCheckWaitsShowsTheWordsAlone() {
        val s = ok.copy(keysGone = true, noUserGuid = true, keptCheckWaiting = true)
        rule.setContent { UnlockContent(s, actions) }
        rule.onNodeWithTag("unlock_no_user_guid").assertExists()
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
        rule.onNodeWithTag("unlock_pin").assertDoesNotExist()
    }

    /** Nothing to erase before the release check answered: it may yet say an erase would not help. No words either yet. */
    @Test
    fun noEraseWhileTheReleaseIsChecked() {
        val s = ok.copy(keysGone = true, noUserGuid = true, loading = true)
        assertFalse(s.eraseOffered)
        assertFalse(s.stuckNoticeShown)
        assertFalse(s.stuckWordsOnly)
    }

    /**
     * A release check that failed (the network) could not rule out an ended release or a rollback: the words alone and
     * "Try again", no erase.
     */
    @Test
    fun afterAFailedCheckTheWordsAloneAndNoErase() {
        val s = UnlockUiState(loading = false, preflightError = FailureKind.NETWORK, keysGone = true, noUserGuid = true)
        assertFalse(s.eraseOffered)
        assertFalse(s.stuckNoticeShown)
        assertTrue(s.stuckWordsOnly)
        rule.setContent { UnlockContent(s, actions) }
        rule.onNodeWithTag("unlock_no_user_guid").assertExists()
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
        rule.onNodeWithText("Try again").assertExists()
    }

    /**
     * (Owner decision a, 2026-11-19) After a failed check, "Recover your vault" beside the words, next to "Try again": a check
     * that never succeeds still leaves a way forward. Still no erase.
     */
    @Test
    fun afterAFailedCheckRecoverIsOfferedNextToTryAgain() {
        val s = UnlockUiState(loading = false, preflightError = FailureKind.RELEASE_UNKNOWN, keysGone = true, noUserGuid = true)
        assertTrue(s.recoverBesideRetry)
        var recovered = 0
        rule.setContent { UnlockContent(s, actions, onOpenAccountSite = { recovered++ }) }
        rule.onNodeWithTag("unlock_no_user_guid").assertExists()
        rule.onNodeWithText("Try again").assertExists()
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
        rule.onNodeWithTag("recover_vault").performScrollTo().performClick()
        assertEquals(1, recovered)
        assertEquals(emptyList<String>(), asked)
    }

    /** After a check that answered, the recover-or-erase notice as before: recover once, with erase, not beside the words. */
    @Test
    fun anAnsweredCheckOffersRecoverAndEraseOnce() {
        val s = ok.copy(keysGone = true, noUserGuid = true)
        assertFalse(s.recoverBesideRetry)
        rule.setContent { UnlockContent(s, actions) }
        rule.onNodeWithTag("unlock_no_user_guid").assertDoesNotExist()
        rule.onAllNodes(hasTestTag("recover_vault")).assertCountEquals(1)
        rule.onNodeWithTag("erase_phone").assertExists()
    }

    /** An ended release or a rollback with no user_guid: the recover-only notice alone (one Recover, no erase). */
    @Test
    fun recoverOnlyWithNoUserGuidIsUnchanged() {
        for ((name, r) in recoverOnly) {
            val s = r.copy(noUserGuid = true, keysGone = true)
            assertFalse(name, s.recoverBesideRetry)
            assertFalse(name, s.eraseOffered)
        }
        rule.setContent { UnlockContent(recoverOnly.getValue("release ended").copy(noUserGuid = true, keysGone = true), actions) }
        rule.onNodeWithTag("unlock_recover_only").assertExists()
        rule.onAllNodes(hasTestTag("recover_vault")).assertCountEquals(1)
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
    }

    /** A kept check waiting after a failed check: the words alone (owner decision a, 2026-11-18), no Recover either. */
    @Test
    fun aKeptCheckWaitingKeepsTheWordsAloneAfterAFailedCheck() {
        val s = UnlockUiState(
            loading = false, preflightError = FailureKind.NETWORK, keysGone = true, noUserGuid = true, keptCheckWaiting = true,
        )
        assertFalse(s.recoverBesideRetry)
        rule.setContent { UnlockContent(s, actions) }
        rule.onNodeWithTag("unlock_no_user_guid").assertExists()
        rule.onNodeWithTag("recover_vault").assertDoesNotExist()
    }

    /** The words after a check that answered (no erase offered: the keys are there): no Recover beside them, no Try again. */
    @Test
    fun noRecoverBesideTheWordsWithoutAFailedCheck() {
        val s = ok.copy(noUserGuid = true)
        assertTrue(s.stuckWordsOnly)
        assertFalse(s.recoverBesideRetry)
        // The decision is for a phone whose reading says it has no usable user_guid, not for an unlock's answer alone.
        val answered = UnlockUiState(loading = false, preflightError = FailureKind.NETWORK, message = UnlockMessage.NoUserGuid)
        assertTrue(answered.stuckWordsOnly)
        assertFalse(answered.recoverBesideRetry)
    }

    @Test
    fun aRollbackOffersRecoveryAndNoErase() {
        var recovered = 0
        rule.setContent { UnlockContent(recoverOnly.getValue("state rollback"), actions, onOpenAccountSite = { recovered++ }) }
        rule.onNodeWithTag("unlock_recover_only").assertExists()
        rule.onNodeWithText("support@vettid.org", substring = true).assertExists()
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
        rule.onNodeWithTag("recover_vault").performScrollTo().performClick()
        assertEquals(1, recovered)
    }

    @Test
    fun anEndedReleaseOffersRecoveryAndNoErase() {
        rule.setContent { UnlockContent(recoverOnly.getValue("release ended"), actions) }
        rule.onNodeWithTag("unlock_recover_only").assertExists()
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
    }

    @Test
    fun aPhoneWithoutTheKeysOffersRecoveryThenErase() {
        var recovered = 0
        rule.setContent { UnlockContent(keysGone, actions, onOpenAccountSite = { recovered++ }) }
        rule.onNodeWithTag("unlock_stuck").assertExists()
        rule.onNodeWithTag("recover_vault").performScrollTo().performClick()
        assertEquals(1, recovered)
        assertEquals(emptyList<String>(), asked)
        // The erase opens the confirmation first; nothing is erased here.
        rule.onNodeWithTag("erase_phone").performScrollTo().performClick()
        assertEquals(listOf("askErase"), asked)
    }

    @Test
    fun theEraseConfirmationForAPhoneWithoutTheKeysSaysWhatARecoveryNeeds() {
        rule.setContent { UnlockContent(keysGone.copy(eraseConfirm = true), actions) }
        rule.onNodeWithText("the credential backup must be on", substring = true).assertExists()
    }

    @Test
    fun anOrdinaryFailureShowsNeither() {
        rule.setContent { UnlockContent(ordinary.getValue("network"), actions) }
        rule.onNodeWithTag("unlock_stuck").assertDoesNotExist()
        rule.onNodeWithTag("unlock_recover_only").assertDoesNotExist()
        rule.onNodeWithTag("recover_vault").assertDoesNotExist()
    }

    @Test
    fun aRollbackOffersContactSupportAndStillNoErase() {
        val mails = mutableListOf<String>()
        rule.setContent {
            UnlockContent(recoverOnly.getValue("state rollback"), actions, onContactSupport = {
                mails += "mail"
                true
            })
        }
        rule.onNodeWithTag("contact_support").performScrollTo().performClick()
        assertEquals(listOf("mail"), mails)
        rule.onNodeWithTag("support_address").assertDoesNotExist()
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
    }

    @Test
    fun anEndedReleaseOffersContactSupportAndWithoutAMailAppShowsTheAddress() {
        rule.setContent { UnlockContent(recoverOnly.getValue("release ended"), actions, onContactSupport = { false }) }
        rule.onNodeWithTag("contact_support").performScrollTo().performClick()
        rule.onNodeWithTag("support_address").assertExists()
        rule.onNodeWithText(SupportMail.ADDRESS).assertExists()
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
    }

    @Test
    fun contactSupportIsOfferedOnRecoverOnlyScreensOnly() {
        rule.setContent { UnlockContent(keysGone, actions, onContactSupport = { true }) }
        rule.onNodeWithTag("contact_support").assertDoesNotExist()
    }

    @Test
    fun theSupportMailCarriesNothingOfTheMembers() {
        val i = SupportMail.intent()
        assertEquals(android.content.Intent.ACTION_SENDTO, i.action)
        assertEquals("mailto:support@vettid.org?subject=VettID%3A%20help%20with%20my%20vault", i.dataString)
        assertEquals(null, i.extras)
        assertEquals(null, i.clipData)
    }

    @Test
    fun aRollbackWinsOverKeysGoneAndAnUnrecognisedPhone() {
        val both = recoverOnly.getValue("release rollback").copy(keysGone = true, notRecognised = true)
        assertFalse(both.eraseOffered)
        rule.setContent { UnlockContent(both.copy(eraseConfirm = true), actions) }
        rule.onNodeWithTag("unlock_recover_only").assertExists()
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
        rule.onNodeWithTag("erase_confirm").assertDoesNotExist()
    }

    /**
     * No usable user_guid: with the notice shown its words are not repeated; while a kept check waits (no notice, no
     * erase) the notice's words alone, with no Recover or Erase.
     */
    @Test
    fun aPhoneWithoutAUserGuidIsToldOnceAndOnlyErasesWithTheNotice() {
        var state by androidx.compose.runtime.mutableStateOf(keysGone.copy(message = UnlockMessage.NoUserGuid))
        rule.setContent { UnlockContent(state, actions) }
        rule.onNodeWithTag("erase_phone").assertExists()
        rule.onNodeWithTag("unlock_no_user_guid").assertDoesNotExist()
        state = state.copy(keptTransfer = true, keptCheckWaiting = true)
        rule.waitForIdle()
        rule.onNodeWithTag("unlock_no_user_guid").assertExists()
        rule.onNodeWithText("This phone cannot open your vault").assertExists()
        rule.onNodeWithText(
            "This phone no longer holds your vault's keys. Before you erase VettID here, check on your account site that " +
                "your vault can be recovered, and start the recovery there. When it is ready, erase VettID and choose " +
                "\"I lost my phone: recover my vault\".",
        ).assertExists()
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
        rule.onNodeWithTag("recover_vault").assertDoesNotExist()
        rule.onNodeWithTag("move_back_failed").assertDoesNotExist()
    }

    /**
     * A "Finish the move" refused for want of a usable user_guid: the unlock screen's move card says the recover-or-erase
     * notice's words, not "does not support this yet", and offers no erase itself (that comes after the next PIN).
     */
    @Test
    fun aMoveRefusedForWantOfAUserGuidIsToldWithTheStuckWords() {
        var state by androidx.compose.runtime.mutableStateOf(
            ok.copy(moveFailure = com.vettid.core.data.vault.MoveFailure(FailureKind.NOT_SUPPORTED, "no_user_guid")),
        )
        rule.setContent { UnlockContent(state, actions) }
        rule.onNodeWithTag("unlock_move_failure").assertExists()
        rule.onNodeWithText("This phone cannot open your vault").assertExists()
        rule.onNodeWithText("This phone no longer holds your vault", substring = true).assertExists()
        rule.onNodeWithText("does not support this yet", substring = true).assertDoesNotExist()
        // The card's only action is Continue: no Recover, no Erase.
        rule.onNode(hasText("Continue") and hasAnyAncestor(hasTestTag("unlock_move_failure"))).assertExists()
        rule.onNodeWithTag("erase_phone").assertDoesNotExist()
        rule.onNodeWithTag("recover_vault").assertDoesNotExist()
        rule.onNodeWithText("Recover your vault").assertDoesNotExist()
        // With the recover-or-erase notice up (after the next PIN), the card goes: the notice says it once.
        state = state.copy(keysGone = true)
        rule.waitForIdle()
        rule.onNodeWithTag("unlock_move_failure").assertDoesNotExist()
        rule.onNodeWithTag("erase_phone").assertExists()
        // With an ended release or a rollback (no erase anywhere), the card goes too: the recover-only notice is the way
        // forward, and neither the erase advice nor "does not support this yet" is shown.
        for (gone in listOf(false, true)) {
            state = state.copy(keysGone = gone, stateRollback = true)
            rule.waitForIdle()
            rule.onNodeWithTag("unlock_move_failure").assertDoesNotExist()
            rule.onNodeWithTag("unlock_recover_only").assertExists()
            rule.onNodeWithText("Before you erase", substring = true).assertDoesNotExist()
            rule.onNodeWithText("does not support this yet", substring = true).assertDoesNotExist()
        }
        // While a kept check waits: the move card alone, not also the no-user_guid message card with the same words.
        state = state.copy(
            keysGone = true, stateRollback = false, keptTransfer = true, keptCheckWaiting = true, message = UnlockMessage.NoUserGuid,
        )
        rule.waitForIdle()
        rule.onNodeWithTag("unlock_move_failure").assertExists()
        rule.onNodeWithTag("unlock_no_user_guid").assertDoesNotExist()
        // Any other move failure stays on the stuck and the recover-only screens alike: only the no-user_guid card steps aside.
        val incomplete = com.vettid.core.data.vault.MoveFailure(FailureKind.TRANSFER_INCOMPLETE, "transfer_incomplete")
        for (other in listOf(ok.copy(keysGone = true, moveFailure = incomplete), ok.copy(stateRollback = true, moveFailure = incomplete))) {
            state = other
            rule.waitForIdle()
            rule.onNodeWithTag("unlock_move_failure").assertExists()
        }
        // Any other refusal of the kind keeps its own text.
        state = ok.copy(moveFailure = com.vettid.core.data.vault.MoveFailure(FailureKind.NOT_SUPPORTED, "unsupported_type"))
        rule.waitForIdle()
        rule.onNodeWithText("This phone cannot open your vault").assertDoesNotExist()
    }
}
