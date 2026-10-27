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
import com.vettid.feature.approvals.ApprovalDetailRoute
import com.vettid.feature.approvals.ApprovalsRoute
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Serializable
internal data object LeaveStartRoute

/**
 * Owner test 2026-10-27: Deny on a sign-in request opened from its notification left a blank screen, and Back then left
 * the app. After a decision (or an expiry) the member lands on a real screen: where they came from, else the Approvals list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ApprovalLeaveTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    private fun show() {
        rule.activityRule.scenario.onActivity {
            it.setContent {
                nav = rememberNavController()
                NavHost(nav, startDestination = LeaveStartRoute) {
                    composable<LeaveStartRoute> { Text("start") }
                    composable<ApprovalsRoute> { Text("approvals") }
                    // The shell's top-level navigation also looks for the pairing, which is a destination of the real graph.
                    composable<com.vettid.feature.devices.PairRoute> { Text("pair") }
                    composable<ApprovalDetailRoute> { Text("detail ${it.toRoute<ApprovalDetailRoute>().key}") }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun act(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.waitForIdle()
    }

    private fun here() = nav.currentBackStackEntry?.destination?.route.orEmpty()

    @Test
    fun aRequestOpenedFromAScreenGoesBackToIt() {
        show()
        act { nav.navigate(ApprovalDetailRoute("auth:a1")) }
        assertTrue(here().contains("ApprovalDetailRoute"))
        act { nav.leaveApprovalDetail() }
        assertTrue(here().contains("LeaveStartRoute"))
    }

    @Test
    fun aDetailWithNothingUnderItLandsOnTheApprovalsList() {
        show()
        act { nav.navigate(ApprovalDetailRoute("auth:a1")) { popUpTo<LeaveStartRoute> { inclusive = true } } }
        assertEquals(null, nav.previousBackStackEntry)
        act { nav.leaveApprovalDetail() }
        assertTrue(here().contains("ApprovalsRoute"))
    }

    @Test
    fun aSecondLeaveNeverEmptiesTheStack() {
        show()
        act { nav.navigate(ApprovalDetailRoute("auth:a1")) }
        act { nav.leaveApprovalDetail() }
        act { nav.leaveApprovalDetail() }
        assertTrue(nav.currentBackStackEntry != null)
        assertTrue(here().contains("ApprovalsRoute"))
    }
}
