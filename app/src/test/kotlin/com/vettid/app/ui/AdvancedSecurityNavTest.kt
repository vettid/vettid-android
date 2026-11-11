package com.vettid.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vettid.core.data.feed.AdvancedSecurityRow
import com.vettid.core.data.feed.FeedTarget
import com.vettid.feature.devices.PairRoute
import com.vettid.feature.settings.AdvancedSecurityRoute
import com.vettid.feature.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ANDROID-PLAN 0.1.124: a notification for a moved row opens Settings, then Advanced security at that row; Back goes to
 * Settings. A second one for another row opens the screen at that row, not the first one's screen again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AdvancedSecurityNavTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    private fun show() {
        rule.activityRule.scenario.onActivity {
            it.setContent {
                nav = rememberNavController()
                NavHost(nav, startDestination = SecretsStartRoute) {
                    composable<SecretsStartRoute> { Text("start") }
                    composable<SettingsRoute> { Text("settings") }
                    composable<AdvancedSecurityRoute> { e -> Text("advanced ${e.toRoute<AdvancedSecurityRoute>().focus}") }
                    composable<PairRoute> { Text("pair") }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun act(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.waitForIdle()
    }

    private fun top() = nav.currentBackStackEntry!!

    @Test
    fun aNotificationOpensAdvancedSecurityAtItsRow() {
        show()
        act { nav.openFeedTarget(FeedTarget.AdvancedSecurity(AdvancedSecurityRow.HOLD)) }
        assertEquals(AdvancedSecurityRow.HOLD, top().toRoute<AdvancedSecurityRoute>().row)
        act { nav.openFeedTarget(FeedTarget.AdvancedSecurity(AdvancedSecurityRow.INTERVAL)) }
        assertEquals(AdvancedSecurityRow.INTERVAL, top().toRoute<AdvancedSecurityRoute>().row)
        // One Advanced security on the stack, over Settings.
        val routes = nav.currentBackStack.value.mapNotNull { it.destination.route }
        assertEquals(1, routes.count { it.contains("AdvancedSecurityRoute") })
        act { nav.popBackStack() }
        assertEquals(true, top().destination.route?.contains("SettingsRoute"))
    }

    @Test
    fun theLockAfterFailedChecksStillOpensSettings() {
        show()
        act { nav.openFeedTarget(FeedTarget.Security) }
        assertEquals(true, top().destination.route?.contains("SettingsRoute"))
    }
}
