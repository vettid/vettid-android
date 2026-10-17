package com.vettid.feature.onboarding

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
    private val actions = Proxy.newProxyInstance(UnlockActions::class.java.classLoader, arrayOf(UnlockActions::class.java)) { _, m, _ ->
        asked += m.name
        null
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
        }
        assertTrue(keysGone.eraseOffered)
        assertFalse(keysGone.recoverOnly)
        for ((name, s) in ordinary) {
            assertFalse(name, s.recoverOnly)
            assertFalse(name, s.eraseOffered)
        }
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
}
