package com.vettid.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * An unlock screen that cannot open the vault offers erase-or-recover: an ended release, a rollback of the release or
 * of the vault's state, or a phone that no longer holds the vault's keys. Nowhere else.
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

    private val stuck = mapOf(
        "release ended" to UnlockUiState(loading = false, preflightError = FailureKind.RELEASE_ENDED),
        "release rollback" to UnlockUiState(loading = false, preflight = PreflightInfo(release, 4, false, true, null)),
        "state rollback" to ok.copy(stateRollback = true),
        "device secret" to ok.copy(message = UnlockMessage.Failed(FailureKind.DEVICE_SECRET, "device_secret")),
    )

    private val notStuck = mapOf(
        "ready" to ok,
        "network" to ok.copy(message = UnlockMessage.Failed(FailureKind.NETWORK, null)),
        "bad pin" to ok.copy(message = UnlockMessage.BadPin),
        "transfer incomplete" to ok.copy(message = UnlockMessage.Failed(FailureKind.TRANSFER_INCOMPLETE, "transfer_incomplete")),
        "release unknown" to UnlockUiState(loading = false, preflightError = FailureKind.RELEASE_UNKNOWN),
        "recovery pending" to ok.copy(recoveryPending = true),
    )

    @Test
    fun eraseOrRecoverIsOfferedOnStuckScreensOnly() {
        for ((name, s) in stuck) {
            assertTrue(name, s.stuck)
            assertTrue(name, s.eraseOffered)
        }
        for ((name, s) in notStuck) {
            assertFalse(name, s.stuck)
            assertFalse(name, s.eraseOffered)
        }
    }

    @Test
    fun aStuckScreenShowsBothActions() {
        var recovered = 0
        rule.setContent { UnlockContent(stuck.getValue("device secret"), actions) { recovered++ } }
        rule.onNodeWithTag("unlock_stuck").assertExists()
        rule.onNodeWithTag("recover_vault").performScrollTo().performClick()
        assertEquals(1, recovered)
        assertEquals(emptyList<String>(), asked)
        // The erase opens the confirmation first; nothing is erased here.
        rule.onNodeWithTag("erase_phone").performScrollTo().performClick()
        assertEquals(listOf("askErase"), asked)
    }

    @Test
    fun anEndedReleaseShowsBothActions() {
        rule.setContent { UnlockContent(stuck.getValue("release ended"), actions) }
        rule.onNodeWithTag("unlock_stuck").assertExists()
    }

    @Test
    fun anOrdinaryFailureShowsNeither() {
        rule.setContent { UnlockContent(notStuck.getValue("network"), actions) }
        rule.onNodeWithTag("unlock_stuck").assertDoesNotExist()
        rule.onNodeWithTag("recover_vault").assertDoesNotExist()
    }
}
