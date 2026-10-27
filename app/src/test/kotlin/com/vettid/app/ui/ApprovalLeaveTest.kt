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

    private fun stack() = nav.currentBackStack.value.mapNotNull { it.destination.route?.substringAfterLast('.') }
        .filter { !it.startsWith("ComposeNavGraph") && it.isNotEmpty() }.map { it.substringBefore('/').substringBefore('?') }

    private fun leave(id: String) = act { nav.leaveApprovalDetail(id) }

    @Test
    fun aRequestOpenedFromAScreenGoesBackToIt() {
        show()
        act { nav.navigate(ApprovalDetailRoute("auth:a1")) }
        leave(nav.currentBackStackEntry!!.id)
        assertEquals(listOf("LeaveStartRoute"), stack())
    }

    @Test
    fun aDetailWithNothingUnderItIsReplacedByTheApprovalsList() {
        show()
        act { nav.navigate(ApprovalDetailRoute("auth:a1")) { popUpTo<LeaveStartRoute> { inclusive = true } } }
        assertEquals(null, nav.previousBackStackEntry)
        leave(nav.currentBackStackEntry!!.id)
        assertEquals(listOf("ApprovalsRoute"), stack())
    }

    /** Two leaves on one entry (the arrow tapped while the popped screen animates out) pop once. */
    @Test
    fun aSecondLeaveOnTheSameEntryDoesNothing() {
        show()
        act { nav.navigate(ApprovalDetailRoute("auth:a1")) }
        val id = nav.currentBackStackEntry!!.id
        leave(id)
        leave(id)
        assertEquals(listOf("LeaveStartRoute"), stack())
    }

    @Test
    fun aSecondLeaveOnTheSameEntryDoesNothingOverTheApprovalsList() {
        show()
        act { nav.navigate(ApprovalsRoute) }
        act { nav.navigate(ApprovalDetailRoute("auth:a1")) }
        val id = nav.currentBackStackEntry!!.id
        leave(id)
        leave(id)
        assertEquals(listOf("LeaveStartRoute", "ApprovalsRoute"), stack())
    }

    @Test
    fun aSecondLeaveOnAReplacedEntryDoesNothing() {
        show()
        act { nav.navigate(ApprovalDetailRoute("auth:a1")) { popUpTo<LeaveStartRoute> { inclusive = true } } }
        val id = nav.currentBackStackEntry!!.id
        leave(id)
        leave(id)
        assertEquals(listOf("ApprovalsRoute"), stack())
    }
}
