package com.vettid.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vettid.core.data.vault.CredentialStatus
import com.vettid.core.ui.components.ShellChrome
import com.vettid.feature.credential.CredentialActions
import com.vettid.feature.credential.CredentialContent
import com.vettid.feature.credential.CredentialNewRoute
import com.vettid.feature.credential.CredentialRotateRoute
import com.vettid.feature.credential.CredentialUiState
import com.vettid.feature.credential.credentialDestination
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (ANDROID-PLAN 0.1.51) "New credential" is shown again now that the lost answer to `credential.reset` is handled
 * (VAULT-MESSAGING 0.48.0): the Credential screen has its row, the row opens the route, and the route is in the graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NewCredentialShownTest {
    @get:Rule
    val rule = createComposeRule()

    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})

    @Test
    fun theCredentialScreenShowsTheNewCredentialRowAndItIsReachable() {
        var opened = 0
        val status = CredentialStatus(
            exists = true, version = 3, keyFingerprint = "ab12", updatedAt = null, alarm = null,
            backup = true, unlockTtlSeconds = 300, criticalItems = 0,
        )
        rule.setContent {
            CredentialContent(CredentialUiState(loading = false, status = status), chrome, CredentialActions(newCredential = { opened++ }))
        }
        rule.onNodeWithTag("backup_switch").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("new_credential_row").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theNewCredentialRouteIsRegistered() {
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "start") {
                composable("start") {}
                credentialDestination(chrome, navigate = {}, onBack = {})
            }
        }
        rule.runOnIdle {
            assertNotNull(nav.graph.findNode<CredentialRotateRoute>())
            assertNotNull(nav.graph.findNode<CredentialNewRoute>())
        }
    }
}
