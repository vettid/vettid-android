package com.vettid.feature.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * (#19722) [UnlockRoute] tells its view model when it is shown: from its start, until its stop or its leaving the
 * composition (the app lock replaces the screen while the activity stays started, and the view model stays).
 */
@RunWith(RobolectricTestRunner::class)
class UnlockRouteVisibleTest {
    @get:Rule
    val rule = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val vault = FakeVault(AppPhase.Locked)

    @Test
    fun shownFromTheStartUntilTheStopOrTheScreenLeaves() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
        val vm = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())
        var composed by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { if (composed) UnlockRoute(vm) }
        }
        rule.waitForIdle()
        assertFalse("not before the start", vm.shown)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        assertTrue("from the start", vm.shown)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        assertFalse("not after the stop", vm.shown)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        assertTrue("again at the next start", vm.shown)
        // The app lock in its place, the activity still started: no stop comes.
        rule.runOnIdle { composed = false }
        rule.waitForIdle()
        assertFalse("not once the screen has left", vm.shown)
        rule.runOnIdle { composed = true }
        rule.waitForIdle()
        assertTrue("back on the screen, still started", vm.shown)
    }
}
