package com.vettid.feature.onboarding

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseView
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/** A6 audit A5: with the check due, Next on the PIN moves to the password and sends nothing. */
@RunWith(RobolectricTestRunner::class)
class UnlockNextFocusTest {
    @get:Rule
    val rule = createComposeRule()

    private val asked = mutableListOf<String>()

    private val actions = Proxy.newProxyInstance(UnlockActions::class.java.classLoader, arrayOf(UnlockActions::class.java)) { _, m, _ ->
        asked += m.name
        null
    } as UnlockActions

    private val release = ReleaseView(3, "aa".repeat(48), "active", null, "")

    @Test
    fun nextOnThePinMovesToThePasswordWhenTheCheckIsDue() {
        val due = UnlockUiState(loading = false, preflight = PreflightInfo(release, 3, false, false, null), checkDue = true)
        rule.setContent { UnlockContent(due, actions) }
        rule.onNodeWithTag("unlock_pin").performImeAction()
        rule.onNodeWithTag("unlock_password").assertIsFocused()
        assertFalse(asked.toString(), "submit" in asked)
    }
}
