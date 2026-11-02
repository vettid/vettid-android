package com.vettid.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * (#245 review) The recovery's PIN step, when a lock came first (`recoveryUnlock` → `UnlockEnd`): the unlock screen's texts,
 * not "Something went wrong"; another failure of the same kind keeps the generic text.
 */
@RunWith(RobolectricTestRunner::class)
class RecoverLockFirstTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = Proxy.newProxyInstance(RecoverActions::class.java.classLoader, arrayOf(RecoverActions::class.java)) { _, m, _ ->
        if (m.returnType == Boolean::class.javaPrimitiveType) false else null
    } as RecoverActions

    private val release = ReleaseView(3, "aa".repeat(48), "active", null, "")
    private val pin = RecoverUiState(step = RecoverStep.PIN, preflight = PreflightInfo(release, 0, false, false, null))

    private fun show(code: String?, kind: FailureKind = FailureKind.OTHER) =
        rule.setContent { RecoverContent(pin.copy(error = kind, errorCode = code), actions, {}, {}, {}) }

    @Test
    fun theVaultsOwnLockSaysItWasLockedWhileOpening() {
        show(MoveRepository.CODE_NOT_OPENED)
        rule.onNodeWithText("Your vault was locked while it was opening. Unlock it again.").assertExists()
        rule.onNodeWithText("Something went wrong. Try again.").assertDoesNotExist()
    }

    @Test
    fun aPendingLockSaysALockCameFirst() {
        show(MoveRepository.CODE_LOCK_PENDING)
        val text = "A lock you asked for came first, so your vault didn't open. Unlock it again when the lock has finished."
        rule.onNodeWithText(text).assertExists()
    }

    @Test
    fun anotherFailureKeepsTheGenericText() {
        show("something_else")
        rule.onNodeWithText("Something went wrong. Try again.").assertExists()
    }
}
