package com.vettid.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * ANDROID-PLAN 0.1.57 on the daily check: `forbidden` says "not the holder" under "Your vault moved to a new phone"; an
 * unmapped code is named by `error_vault_code`, and only the code shows.
 */
@RunWith(RobolectricTestRunner::class)
class OwnerCheckRefusalTextTest {
    @get:Rule
    val rule = createComposeRule()

    private val view = OwnerCheckView(OwnerCheckState.HELD, Instant.now().minusSeconds(60), 86_400, 0, hold = true, holdOffUntil = null)

    private val actions = object : OwnerCheckActions {
        override fun setPin(v: String) = Unit
        override fun setPassword(v: String) = Unit
        override fun setHoldOff(choice: HoldOffChoice) = Unit
        override fun submit() = Unit
        override fun lockVault() = Unit
    }

    private fun show(m: OwnerCheckMessage) = rule.setContent {
        OwnerCheckContent(OwnerCheckUiState(mode = OwnerCheckMode.GATED, view = view, message = m), actions, onCancel = {})
    }

    @Test
    fun forbiddenIsNotTheHolder() {
        show(OwnerCheckMessage.Failed(FailureKind.NOT_HOLDER, "forbidden"))
        rule.onNodeWithText("Your vault moved to a new phone").assertExists()
        rule.onNodeWithText(
            "Another phone now holds your vault's credential, so this phone can't do this. Nothing was disclosed. Use VettID " +
                "on the phone that holds it now. If you no longer have that phone, start a recovery on the account site.",
        ).assertExists()
    }

    @Test
    fun anUnmappedCodeIsNamed() {
        show(OwnerCheckMessage.Failed(FailureKind.OTHER, "relay_error", "relay_error"))
        rule.onNodeWithText(
            "Something went wrong (code: relay_error). Try again. If it keeps happening, contact VettID and give this code.",
        ).assertExists()
    }

    @Test
    fun aFailureThatIsNotAVaultAnswerKeepsItsText() {
        show(OwnerCheckMessage.Failed(FailureKind.OTHER, "keystore"))
        rule.onNodeWithText("Something went wrong. Try again.").assertExists()
        rule.onNodeWithTag("owner_check_failed").assertExists()
    }
}
