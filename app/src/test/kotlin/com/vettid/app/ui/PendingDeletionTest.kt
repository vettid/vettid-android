package com.vettid.app.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.DeletionView
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** A6 audit E4: a failed "Cancel deletion" is said, and the next try clears it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingDeletionTest {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aFailedCancelIsKeptForTheBannerAndClearedByTheNextTry() = runTest {
        val vault = FakeVault(AppPhase.Unlocked)
        vault.pendingDeletion.value = DeletionView(Instant.now().plusSeconds(3_600), false, "d1")
        vault.fail["cancelDeletion"] = VaultFailure(FailureKind.NETWORK)
        val vm = PendingDeletionViewModel(vault)
        assertNull(vm.failure.value)
        vm.cancel()
        assertEquals(FailureKind.NETWORK, vm.failure.value?.kind)
        assertEquals(false, vm.busy.value)
        vault.fail.clear()
        vm.cancel()
        assertNull(vm.failure.value)
        assertNull(vault.pendingDeletion.value)
    }

    @Test
    fun aFailureGoesWhenTheDeletionChangesOrIsGone() = runTest {
        val vault = FakeVault(AppPhase.Unlocked)
        vault.pendingDeletion.value = DeletionView(Instant.now().plusSeconds(3_600), false, "d1")
        vault.fail["cancelDeletion"] = VaultFailure(FailureKind.NETWORK)
        val vm = PendingDeletionViewModel(vault)
        vm.cancel()
        assertNotNull(vm.failure.value)
        vault.pendingDeletion.value = DeletionView(Instant.now().plusSeconds(7_200), false, "d2")
        assertNull(vm.failure.value)
        vault.fail["cancelDeletion"] = VaultFailure(FailureKind.NETWORK)
        vm.cancel()
        assertNotNull(vm.failure.value)
        vault.pendingDeletion.value = null
        assertNull(vm.failure.value)
    }

    @Test
    fun theBannerSaysThatTheCancelFailed() {
        val d = DeletionView(Instant.now().plusSeconds(3_600), false, "d1")
        rule.setContent {
            PendingDeletionBanner(
                d, first = true, onCancel = {}, onOpenSite = {},
                failure = DeletionCancelFailure(FailureKind.NETWORK, null),
            )
        }
        rule.onNode(hasText("could not reach the service", substring = true)).assertExists()
    }
}
