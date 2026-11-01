package com.vettid.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseView
import com.vettid.core.data.vault.UnlockAttempt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * The vault took the unlock, but a lock came first here (#244): the unlock screen says what happened and what to do,
 * never "Something went wrong", and an old phone that kept its keys after a transfer is not told the move back failed.
 */
@RunWith(RobolectricTestRunner::class)
class UnlockOpenedThenLockedTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = Proxy.newProxyInstance(UnlockActions::class.java.classLoader, arrayOf(UnlockActions::class.java)) { _, _, _ ->
        null
    } as UnlockActions

    private val release = ReleaseView(3, "aa".repeat(48), "active", null, "")
    private val ok = UnlockUiState(loading = false, preflight = PreflightInfo(release, 3, false, false, null))

    private fun show(code: String) {
        rule.setContent { UnlockContent(ok.copy(message = UnlockMessage.Failed(FailureKind.OTHER, code)), actions) }
    }

    @Test
    fun theVaultsOwnLockSaysItWasLockedWhileOpening() {
        show(MoveRepository.CODE_NOT_OPENED)
        rule.onNodeWithTag("unlock_failed").assertExists()
        rule.onNodeWithText("Your vault was locked while it was opening. Unlock it again.").assertExists()
        rule.onNodeWithText("Something went wrong. Try again.").assertDoesNotExist()
    }

    @Test
    fun aPendingLockSaysTheVaultIsBeingLocked() {
        show(MoveRepository.CODE_LOCK_PENDING)
        rule.onNodeWithTag("unlock_failed").assertExists()
        rule.onNodeWithText("Your vault is being locked, so it didn't open. Unlock it again once it's locked.").assertExists()
        rule.onNodeWithText("Something went wrong. Try again.").assertDoesNotExist()
    }

    @Test
    fun anotherFailureOfTheSameKindStillSaysSomethingWentWrong() {
        show("something_else")
        rule.onNodeWithText("Something went wrong. Try again.").assertExists()
    }

    @Test
    fun aPhoneThatKeptItsKeysIsNotToldTheMoveBackFailed() {
        for (code in listOf(MoveRepository.CODE_NOT_OPENED, MoveRepository.CODE_LOCK_PENDING)) {
            val r = UnlockAttempt.Failed(FailureKind.OTHER, code)
            assertEquals(code, UnlockMessage.Failed(FailureKind.OTHER, code), unlockFailedMessage(r, keptTransfer = true))
        }
        // Any other failure that is not temporary still is.
        assertEquals(UnlockMessage.MoveBackFailed, unlockFailedMessage(UnlockAttempt.Failed(FailureKind.OTHER, "x"), keptTransfer = true))
    }
}
