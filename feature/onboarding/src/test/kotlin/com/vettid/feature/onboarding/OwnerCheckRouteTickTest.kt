package com.vettid.feature.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * ANDROID-PLAN 0.1.124, on the route: a pass shows the tick, tells the shell it shows (then that it no longer does) and
 * closes the screen; Back while a check is with the vault does not leave.
 */
@RunWith(RobolectricTestRunner::class)
class OwnerCheckRouteTickTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val vault = FakeVault(AppPhase.Unlocked).also {
        it.ownerCheck.value =
            OwnerCheckView(OwnerCheckState.OK, Instant.now().plusSeconds(3_600), 86_400, 0, hold = true, holdOffUntil = null)
    }

    private var done = 0
    private var cancels = 0
    private val showing = mutableListOf<Boolean>()

    private fun show(vm: OwnerCheckViewModel) = rule.setContent {
        OwnerCheckRoute(
            mode = OwnerCheckMode.VOLUNTARY,
            onDone = { done++ },
            onCancel = { cancels++ },
            onTickShowing = { showing += it },
            viewModel = vm,
        )
    }

    @Test
    fun aPassShowsTheTickThenClosesTheScreen() {
        val vm = OwnerCheckViewModel(vault, vault)
        show(vm)
        rule.waitForIdle()
        rule.runOnUiThread {
            vm.setPin("40281795")
            vm.setPassword("correct horse")
            vm.submit()
        }
        rule.waitForIdle()
        assertEquals("the tick shows", true, vm.uiState.value.passedTick)
        assertEquals("not closed before the hold", 0, done)
        rule.mainClock.advanceTimeBy(PASSED_TICK_MS + 200)
        rule.waitForIdle()
        assertEquals("the screen closed after the tick", 1, done)
        assertEquals(0, cancels)
        assertEquals("the shell was told the tick showed, then that it ended", listOf(true, false), showing.dropWhile { !it })
    }

    @Test
    fun backWhileTheCheckIsWithTheVaultDoesNotLeave() {
        val gate = CompletableDeferred<Unit>()
        vault.checkGate = gate
        val vm = OwnerCheckViewModel(vault, vault)
        show(vm)
        rule.waitForIdle()
        rule.runOnUiThread {
            vm.setPin("40281795")
            vm.setPassword("correct horse")
            vm.submit()
        }
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals("Back is ignored while checking", 0, cancels)
        gate.complete(Unit)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(PASSED_TICK_MS + 200)
        rule.waitForIdle()
        assertEquals(1, done)
    }
}
