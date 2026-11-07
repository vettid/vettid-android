package com.vettid.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.vettid.core.data.vault.EarlierRelease
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseLogEntry
import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.ReleaseSecurity
import com.vettid.core.data.vault.ReleaseView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

/**
 * The release offer at unlock is compact (owner decision 2026-11-07, GC thread 01M6SQ6VMNMB0C2CRPNZYNWKBB): the PIN and
 * Unlock first and on screen, then "Update available: release N" with the security line, the fingerprint, the approval and
 * "What's new in release N", which opens the full notes in a sheet. The approval never waits for the sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class ReleaseOfferCompactTest {
    @get:Rule
    val rule = createComposeRule()

    private fun release(n: Long) = ReleaseView(n, "%02d".format(n).repeat(48), "active", null, "https://vettid.org/security/releases/$n/")

    private val fingerprint = release(5).fingerprint
    private val preflight = PreflightInfo(release(4), 4, false, false, release(5))
    private val notes = ReleaseNotes.Available(
        "vettid.org",
        ReleaseLogEntry(
            5, "Faster unlocks.", listOf("One round trip less.", "Clearer messages."), ReleaseSecurity.URGENT, "Fixes the backoff.",
        ),
        listOf(EarlierRelease(4, "Sharing rules per tag.")),
    )

    private var unlock by mutableStateOf(UnlockUiState(loading = false, preflight = preflight, notes = notes))
    private val unlockCalls = mutableListOf<String>()
    private val unlockActions = Proxy.newProxyInstance(
        UnlockActions::class.java.classLoader, arrayOf(UnlockActions::class.java),
    ) { p, m, args ->
        when (m.name) {
            "equals" -> p === args[0]
            "hashCode" -> System.identityHashCode(p)
            else -> {
                unlockCalls += m.name
                if (m.name == "setApproveOffer") unlock = unlock.copy(approveOffer = args[0] as Boolean)
                null
            }
        }
    } as UnlockActions

    private var recover by mutableStateOf(RecoverUiState(step = RecoverStep.PIN, preflight = preflight, notes = notes))
    private val recoverActions = Proxy.newProxyInstance(
        RecoverActions::class.java.classLoader, arrayOf(RecoverActions::class.java),
    ) { p, m, args ->
        when {
            m.name == "equals" -> p === args[0]
            m.name == "hashCode" -> System.identityHashCode(p)
            m.name == "setApproveOffer" -> { recover = recover.copy(approveOffer = args[0] as Boolean); null }
            m.returnType == Boolean::class.javaPrimitiveType -> false
            else -> null
        }
    } as RecoverActions

    /** The test tags in the semantics tree's order (depth first, unmerged). */
    private fun tagsInOrder(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { out += it }
            n.children.forEach { walk(it) }
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    private fun assertBefore(tags: List<String>, first: String, then: String) {
        val a = tags.indexOf(first)
        val b = tags.indexOf(then)
        assertTrue("$first in $tags", a >= 0)
        assertTrue("$then in $tags", b >= 0)
        assertTrue("$first before $then: $tags", a < b)
    }

    @Test
    fun thePinAndUnlockComeFirstAndAreOnScreenWithoutScrolling() {
        rule.setContent { UnlockContent(unlock, unlockActions) }
        val tags = tagsInOrder()
        assertBefore(tags, "unlock_pin", "release_offer")
        rule.onNodeWithTag("unlock_pin").assertIsDisplayed()
        // Unlock is the screen's pinned bottom button: on screen whatever the content's length.
        rule.onNodeWithTag("primary_button").assertIsDisplayed().assertTextEquals("Unlock")
        val pin = rule.onNodeWithTag("unlock_pin").fetchSemanticsNode().boundsInRoot
        val offer = rule.onNodeWithTag("release_offer").fetchSemanticsNode().boundsInRoot
        assertTrue("the notice is below the PIN", offer.top >= pin.bottom)
        // The full notes are not on the screen itself.
        rule.onNodeWithTag("whats_new_summary").assertDoesNotExist()
        rule.onNodeWithTag("whats_new_sheet").assertDoesNotExist()
    }

    @Test
    fun theUnlockResultsComeBeforeTheOffer() {
        // Review F1: a failed unlock's explanation and its erase come first; the optional offer is last.
        unlock = unlock.copy(signerRefused = true, notRecognised = true, message = UnlockMessage.BadPin)
        rule.setContent { UnlockContent(unlock, unlockActions) }
        val tags = tagsInOrder()
        assertBefore(tags, "unlock_pin", "unlock_signer_refused")
        assertBefore(tags, "unlock_signer_refused", "release_offer")
        assertBefore(tags, "unlock_not_recognised", "release_offer")
        assertBefore(tags, "erase_phone", "release_offer")
        unlock = unlock.copy(notRecognised = false, keysGone = true)
        rule.waitForIdle()
        assertBefore(tagsInOrder(), "unlock_stuck", "release_offer")
    }

    @Test
    fun theCompactNoticeShowsTheSecurityLineAndTheFingerprint() {
        rule.setContent { UnlockContent(unlock, unlockActions) }
        rule.onNodeWithText("Update available: release 5").assertExists()
        rule.onNodeWithTag("release_offer_security", useUnmergedTree = true).assertTextEquals("Security fix: update urgently")
        rule.onNodeWithTag("release_offer_fingerprint", useUnmergedTree = true).assertTextContains(fingerprint, substring = true)
    }

    @Test
    fun aReleaseWithoutASecurityFixSaysSo() {
        unlock = unlock.copy(notes = notes.copy(entry = notes.entry.copy(security = ReleaseSecurity.NONE, securityText = null)))
        rule.setContent { UnlockContent(unlock, unlockActions) }
        rule.onNodeWithTag("release_offer_security", useUnmergedTree = true).assertTextEquals("No security fix")
        rule.onNodeWithTag("release_offer_fingerprint", useUnmergedTree = true).assertTextContains(fingerprint, substring = true)
    }

    @Test
    fun whileTheNotesLoadTheNoticeShowsTheProgressLineAndTheApprovalWorks() {
        unlock = unlock.copy(notes = null)
        rule.setContent { UnlockContent(unlock, unlockActions) }
        rule.onNodeWithTag("release_offer_loading").assertTextContains("Loading the release notes…")
        rule.onNodeWithTag("release_offer_security", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("release_offer_fingerprint", useUnmergedTree = true).assertTextContains(fingerprint, substring = true)
        rule.onNodeWithTag("approve_offer").assertIsEnabled().performClick()
        rule.onNodeWithTag("approve_offer").assertIsOn()
    }

    @Test
    fun unavailableNotesAreSaidAndTheApprovalWorks() {
        unlock = unlock.copy(notes = ReleaseNotes.Unavailable)
        rule.setContent { UnlockContent(unlock, unlockActions) }
        rule.onNodeWithTag("release_offer_unavailable", useUnmergedTree = true).assertTextEquals("Release notes unavailable")
        rule.onNodeWithTag("release_offer_security", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("release_offer_fingerprint", useUnmergedTree = true).assertTextContains(fingerprint, substring = true)
        rule.onNodeWithTag("approve_offer").assertIsEnabled().performClick()
        rule.onNodeWithTag("approve_offer").assertIsOn()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, so that the lines wrap as on a phone
    fun theApprovalAndTheLinkDoNotMoveWhenTheNotesArrive() {
        // Review F2: the notes' line keeps its height whatever its state, also where its texts wrap differently.
        RuntimeEnvironment.setFontScale(2f)
        unlock = unlock.copy(notes = null)
        rule.setContent { UnlockContent(unlock, unlockActions) }
        fun tops() = listOf("approve_offer", "whats_new_open").map { rule.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
        val loading = tops()
        unlock = unlock.copy(notes = notes)
        rule.waitForIdle()
        assertEquals(loading, tops())
        unlock = unlock.copy(notes = ReleaseNotes.Unavailable)
        rule.waitForIdle()
        assertEquals(loading, tops())
        unlock = unlock.copy(notes = notes.copy(entry = notes.entry.copy(security = ReleaseSecurity.NONE)))
        rule.waitForIdle()
        assertEquals(loading, tops())
    }

    @Test
    fun theCompactNoticeHoldsExactlyThePlansContents() {
        // ANDROID-PLAN 0.1.122: "Update available: release N", the security line, the fingerprint,
        // "Approve the update with this unlock" and the link; security_text stays in the sheet.
        rule.setContent { UnlockContent(unlock, unlockActions) }
        rule.onNodeWithText("Update available: release 5").assertExists()
        rule.onNodeWithText("Approve the update with this unlock", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("whats_new_open").assertTextEquals("What's new in release 5")
        rule.onNodeWithText("Fixes the backoff.").assertDoesNotExist()
        rule.onNodeWithTag("whats_new_open").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Fixes the backoff.").assertExists()
    }

    @Test
    fun theApprovalWorksWithoutOpeningTheSheet() {
        rule.setContent { UnlockContent(unlock, unlockActions) }
        rule.onNodeWithTag("approve_offer").assertIsOff().performClick()
        rule.onNodeWithTag("approve_offer").assertIsOn()
        assertEquals(listOf("setApproveOffer"), unlockCalls.filter { it == "setApproveOffer" })
        rule.onNodeWithTag("whats_new_sheet").assertDoesNotExist()
    }

    @Test
    fun theLinkIsAButtonThatOpensTheSheetWithTheFullNotesAndTheFingerprint() {
        rule.setContent { UnlockContent(unlock, unlockActions) }
        val link = rule.onNodeWithTag("whats_new_open")
        link.assertTextEquals("What's new in release 5")
        assertEquals(Role.Button, link.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        link.performClick()
        rule.waitForIdle()
        val sheet = rule.onNodeWithTag("whats_new_sheet").fetchSemanticsNode()
        assertEquals("What's new in release 5", sheet.config.getOrNull(SemanticsProperties.PaneTitle))
        rule.onNodeWithTag("whats_new_summary").assertTextEquals("Faster unlocks.")
        rule.onNodeWithText("One round trip less.").assertExists()
        rule.onNodeWithText("Fixes the backoff.").assertExists()
        rule.onNodeWithText("Release 4: Sharing rules per tag.").assertExists()
        rule.onNodeWithText("From vettid.org").assertExists()
        rule.onNodeWithTag("whats_new_full").assertExists()
        rule.onNodeWithTag("whats_new_fingerprint").assertTextContains(fingerprint, substring = true)
    }

    @Test
    fun theSheetSaysWhileTheNotesLoad() {
        unlock = unlock.copy(notes = null)
        rule.setContent { UnlockContent(unlock, unlockActions) }
        rule.onNodeWithTag("release_offer_security", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("whats_new_open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("whats_new_loading").assertExists()
        rule.onNodeWithTag("whats_new_fingerprint").assertTextContains(fingerprint, substring = true)
    }

    // --- the recovery's PIN step (MoveScreens), the other place the offer is shown ---

    @Test
    fun theRecoveryPinComesFirstAndItsOfferIsCompact() {
        rule.setContent { RecoverContent(recover, recoverActions, {}, {}, {}) }
        assertBefore(tagsInOrder(), "recover_pin", "release_offer")
        rule.onNodeWithTag("recover_pin").assertIsDisplayed()
        rule.onNodeWithTag("primary_button").assertIsDisplayed()
        rule.onNodeWithTag("release_offer_security", useUnmergedTree = true).assertTextEquals("Security fix: update urgently")
        rule.onNodeWithTag("release_offer_fingerprint", useUnmergedTree = true).assertTextContains(fingerprint, substring = true)
        rule.onNodeWithTag("whats_new_summary").assertDoesNotExist()
        rule.onNodeWithTag("approve_offer").performClick()
        rule.onNodeWithTag("approve_offer").assertIsOn()
        rule.onNodeWithTag("whats_new_sheet").assertDoesNotExist()
        rule.onNodeWithTag("whats_new_open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("whats_new_summary").assertTextEquals("Faster unlocks.")
        rule.onNodeWithTag("whats_new_fingerprint").assertTextContains(fingerprint, substring = true)
    }
}
