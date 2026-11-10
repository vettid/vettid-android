package com.vettid.feature.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.CredentialKeyAnswer
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.ReleaseInfoView
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultOverview
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Settings → Vault status, Credential key row (ANDROID-PLAN 0.1.119 §4 "Owner check and status"). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CredentialKeyRowTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val fp = "e939 754d 09aa a93a"
    private val overview = VaultOverview(
        "3f9c2a7be41d4c0e9b8a6f5d2c1e0a9b", "unlocked", ReleaseInfoView(10, "active", null, 10, null), 10, null, false, 1, 4,
    )

    private fun content(ck: CredentialKeyUi, retry: () -> Unit = {}) =
        rule.setContent { VaultStatusContent(LoadState(false, overview), {}, {}, credentialKey = ck, onRetryCredentialKey = retry) }

    // --- the row ---

    @Test
    fun shownWithTheLineForAdminsAndAnOrdinaryCopy() {
        content(CredentialKeyUi.Shown(fp))
        rule.onNodeWithText("Credential key: $fp").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(res.getString(R.string.settings_status_credential_key_admins)).performScrollTo().assertIsDisplayed()
        assertEquals(
            "If you are an admin of a service, the other admins may ask you to read this out when you become an admin, " +
                "or after you reset your credential. It is not a secret.",
            res.getString(R.string.settings_status_credential_key_admins),
        )
        rule.onNodeWithTag("credential_key_copy").performScrollTo().performClick()
        val clip = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(fp, clip.primaryClip?.getItemAt(0)?.text?.toString())
        // Ordinary copy: not marked sensitive and not cleared by the app.
        val extras = clip.primaryClip?.description?.extras
        assertTrue(extras == null || !extras.containsKey(com.vettid.core.ui.components.CLIP_EXTRA_IS_SENSITIVE))
    }

    @Test
    fun loadingShowsAProgressLineNeverABlankRow() {
        content(CredentialKeyUi.Loading)
        rule.onNodeWithTag("credential_key_loading").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(res.getString(R.string.settings_loading)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unknownSaysSoAndOffersRetry() {
        var retried = 0
        content(CredentialKeyUi.Unknown) { retried++ }
        rule.onNodeWithText("Credential key: cannot be checked right now.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("credential_key_retry").performScrollTo().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun noneYet() {
        content(CredentialKeyUi.None)
        rule.onNodeWithText("Credential key: none yet").performScrollTo().assertIsDisplayed()
    }

    // --- the view model ---

    private val vault = FakeVault(AppPhase.Unlocked)

    @Test
    fun asksCredentialVersionWhenTheScreenOpensAndShowsTheFingerprint() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, fp)
        val vm = VaultStatusViewModel(vault)
        assertEquals(CredentialKeyUi.Loading, vm.credentialKey.value)
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "credentialKey" })
        assertEquals(CredentialKeyUi.Shown(fp), vm.credentialKey.value)
    }

    /**
     * (GC #6816, as vettid-ios #236) Back in front (the app's return, or the screen over it gone) the key is asked again; the
     * first showing asks nothing more than the opening did, and a screen never hidden asks nothing more.
     */
    @Test
    fun shownAgainAfterBeingHiddenAsksTheKeyAgain() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, fp)
        val vm = VaultStatusViewModel(vault)
        vm.setVisible(true) // the first showing
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "credentialKey" })
        vm.setVisible(true) // shown again without being hidden: nothing changed
        advanceUntilIdle()
        assertEquals(1, vault.calls.count { it == "credentialKey" })
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, "0000 1111 2222 3333")
        vm.setVisible(false)
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(2, vault.calls.count { it == "credentialKey" })
        assertEquals(CredentialKeyUi.Shown("0000 1111 2222 3333"), vm.credentialKey.value)
    }

    /**
     * (GC #6816) A change whose answer was lost, decided later as having replaced the key (a reset): the repository says the
     * key is not known, and the open row asks again instead of keeping the old fingerprint.
     */
    @Test
    fun aKeyMadeUnknownLaterIsAskedAgain() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, fp)
        val vm = VaultStatusViewModel(vault)
        advanceUntilIdle()
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, "0000 1111 2222 3333")
        vault.answerWithKey(null)
        advanceUntilIdle()
        assertEquals(2, vault.calls.count { it == "credentialKey" })
        assertEquals(CredentialKeyUi.Shown("0000 1111 2222 3333"), vm.credentialKey.value)
    }

    @Test
    fun noCredentialIsNoneYet() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(false, null)
        val vm = VaultStatusViewModel(vault)
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.None, vm.credentialKey.value)
    }

    @Test
    fun anErrorOrALockedVaultIsUnknownAndRetryAsksAgain() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, fp)
        vault.fail["credentialKey"] = VaultFailure(FailureKind.NETWORK)
        val vm = VaultStatusViewModel(vault)
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.Unknown, vm.credentialKey.value)

        vault.fail["credentialKey"] = VaultFailure(FailureKind.OTHER, "lock_pending") // a locked vault
        vm.loadCredentialKey()
        assertEquals(CredentialKeyUi.Loading, vm.credentialKey.value)
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.Unknown, vm.credentialKey.value)

        vm.loadCredentialKey()
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.Shown(fp), vm.credentialKey.value)
        assertEquals(3, vault.calls.count { it == "credentialKey" })
    }

    @Test
    fun anAnswerWithoutAKeyIsUnknown() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, null)
        val vm = VaultStatusViewModel(vault)
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.Unknown, vm.credentialKey.value)
    }

    @Test
    fun aCreateRotateOrResetAnswerUpdatesTheFingerprint() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, fp)
        val vm = VaultStatusViewModel(vault)
        advanceUntilIdle()
        vault.answerWithKey("1111 2222 3333 4444")
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.Shown("1111 2222 3333 4444"), vm.credentialKey.value)
    }

    @Test
    fun anOlderAskAnsweringAfterANewerAnswerDoesNotReplaceIt() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, fp)
        val gate = CompletableDeferred<Unit>()
        vault.credentialKeyGate = gate
        val vm = VaultStatusViewModel(vault)
        advanceUntilIdle() // the ask is out, unanswered
        assertEquals(CredentialKeyUi.Loading, vm.credentialKey.value)
        vault.answerWithKey("1111 2222 3333 4444") // a rotation answered meanwhile
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.Shown("1111 2222 3333 4444"), vm.credentialKey.value)
        gate.complete(Unit) // the older ask now answers with the old key
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.Shown("1111 2222 3333 4444"), vm.credentialKey.value)
    }

    @Test
    fun anAnswerThatNamedNoKeyAsksAgainInsteadOfKeepingTheOldFingerprint() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, fp)
        val vm = VaultStatusViewModel(vault)
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.Shown(fp), vm.credentialKey.value)
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, "5555 6666 7777 8888")
        val gate = CompletableDeferred<Unit>()
        vault.credentialKeyGate = gate
        vault.answerWithKey(null)
        runCurrent()
        assertEquals(CredentialKeyUi.Loading, vm.credentialKey.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, vault.calls.count { it == "credentialKey" })
        assertEquals(CredentialKeyUi.Shown("5555 6666 7777 8888"), vm.credentialKey.value)
    }

    /** A routine operation keeps the held key and emits nothing: the ask in flight is not cancelled, and its answer is shown. */
    @Test
    fun aRoutineKeepDuringAnAskDoesNotCancelIt() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, fp)
        val gate = CompletableDeferred<Unit>()
        vault.credentialKeyGate = gate
        val vm = VaultStatusViewModel(vault)
        advanceUntilIdle() // the ask is out
        // (a PIN change meanwhile keeps the held credential and key: the device emits nothing, so nothing arrives here)
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.Loading, vm.credentialKey.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(CredentialKeyUi.Shown(fp), vm.credentialKey.value)
    }

    @Test
    fun tryAgainOnTheOverviewAsksTheKeyToo() = runTest {
        vault.credentialKeyAnswer = CredentialKeyAnswer(true, fp)
        val vm = VaultStatusViewModel(vault)
        advanceUntilIdle()
        vm.load()
        assertEquals(CredentialKeyUi.Loading, vm.credentialKey.value)
        advanceUntilIdle()
        assertEquals(2, vault.calls.count { it == "credentialKey" })
    }

    @Test
    fun shownPrintsItsTypeNameOnly() {
        assertEquals("CredentialKeyUi.Shown", CredentialKeyUi.Shown(fp).toString())
    }

    @Test
    fun theRowIsShownWhetherTheOverviewFailedOrIsStillLoading() {
        rule.setContent {
            Column {
                VaultStatusContent(
                    LoadState<VaultOverview>(false, null, FailureKind.NETWORK), {}, {},
                    credentialKey = CredentialKeyUi.Shown(fp),
                )
            }
        }
        rule.onNodeWithText("Credential key: $fp").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theRowIsShownWhileTheOverviewLoads() {
        rule.setContent { VaultStatusContent(LoadState<VaultOverview>(), {}, {}, credentialKey = CredentialKeyUi.Unknown) }
        rule.onNodeWithText("Credential key: cannot be checked right now.").performScrollTo().assertIsDisplayed()
    }
}
