package com.vettid.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings → Account → "Delete your VettID account" opens the account site's page; nothing is asked or sent in the app. */
@RunWith(RobolectricTestRunner::class)
class DeleteAccountRowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theRowIsShownAndOpensTheDeleteAccountPage() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                SettingsContent(
                    SettingsUiState(accountDeleteUrl = "https://account.staging.vettid.org/account/delete/"),
                    SettingsActions(),
                )
            }
        }
        rule.onNodeWithTag("delete_account").performScrollTo().assertTextContains("Delete your VettID account")
        rule.onNodeWithTag("delete_account").performClick()
        assertEquals(listOf("https://account.staging.vettid.org/account/delete/"), opened)
    }
}
