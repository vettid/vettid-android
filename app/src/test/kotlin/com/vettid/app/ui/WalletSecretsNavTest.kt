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
import com.vettid.core.data.wallet.BackupRoute
import com.vettid.core.data.wallet.SendRoute
import com.vettid.core.data.wallet.ImportWalletRoute
import com.vettid.core.data.wallet.NewWalletRoute
import com.vettid.feature.devices.PairRoute
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Serializable
internal data object SecretsStartRoute

@Serializable
internal data object SecretsOtherRoute

/**
 * #238 review: the screens that hold a recovery phrase are never kept in the saved back stack. Leaving them by the drawer or a
 * notification pops them (so their view model and the words go), coming back does not restore them, and Back leaves once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WalletSecretsNavTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    private fun show(withWallet: Boolean = true) {
        rule.activityRule.scenario.onActivity {
            it.setContent {
                nav = rememberNavController()
                NavHost(nav, startDestination = SecretsStartRoute) {
                    composable<SecretsStartRoute> { Text("start") }
                    composable<SecretsOtherRoute> { Text("other") }
                    composable<PairRoute> { Text("pair") }
                    if (withWallet) {
                        composable<NewWalletRoute> { Text("new wallet") }
                        composable<ImportWalletRoute> { Text("import wallet") }
                        composable<BackupRoute> { Text("backup") }
                        composable<SendRoute> { Text("send") }
                    }
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

    @Test
    fun leavingByTheDrawerPopsThePhraseAndComingBackDoesNotRestoreIt() {
        show()
        act { nav.navigate(NewWalletRoute) }
        act { nav.navigateTopLevel(SecretsOtherRoute) }
        assertFalse(stack().any { it.startsWith("NewWalletRoute") })
        act { nav.navigateTopLevel(SecretsStartRoute) }
        assertEquals(listOf("SecretsStartRoute"), stack())
    }

    @Test
    fun aBackupFromARowIsPoppedToo() {
        show()
        act { nav.navigate(BackupRoute("01W")) }
        act { nav.navigateTopLevel(SecretsOtherRoute) }
        assertFalse(stack().any { it.startsWith("BackupRoute") })
    }

    @Test
    fun aSendIsPoppedToo() {
        show()
        act { nav.navigate(SendRoute("01W")) }
        act { nav.navigateTopLevel(SecretsOtherRoute) }
        assertFalse(stack().any { it.startsWith("SendRoute") })
        act { nav.navigateTopLevel(SecretsStartRoute) }
        assertEquals(listOf("SecretsStartRoute"), stack())
    }

    @Test
    fun anImportIsPoppedToo() {
        show()
        act { nav.navigate(ImportWalletRoute) }
        act { nav.navigateTopLevel(SecretsOtherRoute) }
        assertFalse(stack().any { it.startsWith("ImportWalletRoute") })
        act { nav.navigateTopLevel(SecretsStartRoute) }
        assertEquals(listOf("SecretsStartRoute"), stack())
    }

    @Test
    fun aBuildWithoutTheWalletHasNothingToPop() {
        show(withWallet = false)
        act { nav.navigateTopLevel(SecretsOtherRoute) }
        assertEquals("SecretsOtherRoute", stack().last())
    }

    @Test
    fun backLeavesTheScreenOnceNotTheOneBelowIt() {
        show()
        act { nav.navigate(SecretsOtherRoute) }
        act { nav.navigate(NewWalletRoute) }
        val id = nav.currentBackStackEntry!!.id
        act { nav.leaveEntry(id) }
        act { nav.leaveEntry(id) }
        assertEquals(listOf("SecretsStartRoute", "SecretsOtherRoute"), stack())
    }
}
