package com.vettid.feature.wallet

import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.wallet.WalletFlags
import com.vettid.core.wallet.WalletsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Import a wallet (ANDROID-PLAN §4 "Wallet", screens table): each word against the BIP39 list as typed, the checksum before
 * anything is sent, the passphrase rule, then the limits, the password and `wallet.create` with `item`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ops = FakeWallets()
    private val flagsFile = MemoryFlags()
    private lateinit var wallets: WalletsManager

    // Public BIP39 test phrases (not wallets of anyone).
    private val good = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val badSum = "legal winner thank year wave sausage worth useful legal winner yellow thank"

    private suspend fun TestScope.vm(): ImportViewModel {
        wallets = manager(backgroundScope, ops, WalletFlags(flagsFile))
        wallets.open()
        return ImportViewModel(wallets)
    }

    private fun ImportViewModel.fill(words: String = good) {
        setName("Old wallet")
        setNetwork(Network.TESTNET4)
        setWords(words)
    }

    // The words as typed

    @Test
    fun eachWordIsCheckedAgainstTheListAsItIsTyped() {
        assertEquals(PhraseStatus(), PhraseInput.analyse(""))
        // A word being typed only has to begin one; a finished word has to be one.
        assertNull(PhraseInput.analyse("legal win").unknown)
        assertEquals(2, PhraseInput.analyse("legal winx").unknown)
        assertEquals(2, PhraseInput.analyse("legal winx ").unknown)
        assertNull(PhraseInput.analyse("legal winner ").unknown)
        // "wi" begins words but is not one once a space follows it.
        assertEquals(2, PhraseInput.analyse("legal wi ").unknown)
        assertNull(PhraseInput.analyse("LEGAL").unknown)
        // Digits and punctuation are no part of a word.
        assertEquals(1, PhraseInput.analyse("l3gal").unknown)
        assertEquals(PhraseStatus(1, null, true), PhraseInput.analyse("zoo "))
        assertFalse(PhraseInput.analyse("zo").lastComplete)
    }

    @Test
    fun anyCaseAndSpacingAreTaken() {
        assertEquals(12, PhraseInput.tokens("  Legal   WINNER\nthank\tyear wave sausage worth useful legal winner thank yellow ").size)
        assertTrue(PhraseInput.analyse(good.uppercase()).ready)
        assertEquals(good, String(PhraseInput.normalised("  " + good.uppercase().replace(" ", "  ") + "\n")))
    }

    @Test
    fun onlyTheFiveLengthsAreReady() {
        assertFalse(PhraseInput.analyse("legal winner thank").ready)
        assertTrue(PhraseInput.analyse(good).ready)
        assertFalse(PhraseInput.analyse("$good legal").ready)
        // The last word still being typed is not ready until it is a whole word.
        assertFalse(PhraseInput.analyse(good.dropLast(3)).ready)
    }

    @Test
    fun theChecksumIsCheckedBeforeAnythingIsSent() = runTest {
        val vm = vm()
        vm.fill(badSum)
        assertTrue(vm.state.value.formReady)
        vm.toLimits()
        // The words are all of the list and 12 of them, but the checksum fails: stay, say so, send nothing.
        assertEquals(ImportStep.FORM, vm.state.value.step)
        assertTrue(vm.state.value.invalidPhrase)
        // Editing the words takes the message away.
        vm.setWords(good)
        assertFalse(vm.state.value.invalidPhrase)
        vm.toLimits()
        assertEquals(ImportStep.LIMITS, vm.state.value.step)
        assertTrue(ops.calls.none { it.startsWith("create") })
    }

    @Test
    fun anUnknownWordOrTheWrongCountKeepsContinueOff() = runTest {
        val vm = vm()
        vm.fill("legal winner thank year wave sausage worth useful legal winner thank yelow")
        assertEquals(12, vm.state.value.status.unknown)
        assertFalse(vm.state.value.formReady)
        vm.toLimits()
        assertEquals(ImportStep.FORM, vm.state.value.step)
        vm.setWords("legal winner thank")
        assertFalse(vm.state.value.formReady)
        // A name is needed too.
        vm.setWords(good)
        vm.setName("")
        assertFalse(vm.state.value.formReady)
    }

    // The passphrase

    @Test
    fun thePassphraseIsPrintableAsciiOfAtMost256Bytes() {
        assertTrue(PassphraseRule.valid(""))
        assertTrue(PassphraseRule.valid(" Correct Horse! ~"))
        assertTrue(PassphraseRule.valid("x".repeat(256)))
        assertFalse(PassphraseRule.valid("x".repeat(257)))
        assertFalse(PassphraseRule.valid("café"))
        assertFalse(PassphraseRule.valid("tab\there"))
        assertFalse(PassphraseRule.valid("line\nbreak"))
        assertFalse(PassphraseRule.valid("\u007f"))
    }

    @Test
    fun aPassphraseThatIsNotAllowedKeepsContinueOff() = runTest {
        val vm = vm()
        vm.fill()
        vm.setPassphrase("café")
        assertFalse(vm.state.value.passphraseValid)
        assertFalse(vm.state.value.formReady)
        vm.setPassphrase("Correct Horse")
        assertTrue(vm.state.value.formReady)
    }

    // The whole flow

    @Test
    fun anImportSendsTheWordsAndPassphraseThenSetsTheLimitsAtOnce() = runTest {
        val vm = vm()
        vm.fill("  " + good.uppercase() + "  ")
        vm.toggleAdvanced()
        vm.setPassphrase(" Correct  Horse ")
        vm.toLimits()
        assertEquals(ImportStep.LIMITS, vm.state.value.step)
        vm.setLimitChoice(true)
        vm.setPerTx("500000")
        vm.setPerPeriod("2000000")
        vm.toPassword()
        assertEquals(ImportStep.PASSWORD, vm.state.value.step)
        vm.setPassword("pw")
        vm.import()
        eventually { vm.state.value.importedId != null }
        // `wallet.create` with `item`: lower-case words, single spaces; the passphrase exactly as typed. No reveal: the member typed
        // the words, so the wallet starts backed up; the limits followed at once.
        assertEquals(good, ops.importedWords)
        assertEquals(" Correct  Horse ", ops.importedPassphrase)
        assertEquals(listOf("create:Old wallet:testnet4:true", "import", "caps.set:500000:2000000"), ops.calls.filterNot { it == "list" })
        val id = vm.state.value.importedId!!
        assertTrue(WalletFlags(flagsFile).backedUp(id))
        assertTrue(wallets.capsPending.value.isEmpty())
        assertFalse(vm.state.value.capsFailed)
        // Nothing of the words is left in the state.
        assertEquals("", vm.state.value.typed)
        assertEquals("", vm.state.value.passphrase)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun noLimitAndNoPassphraseSendsOnlyTheCreate() = runTest {
        val vm = vm()
        vm.fill()
        vm.toLimits()
        vm.toPassword()
        vm.setPassword("pw")
        vm.import()
        eventually { vm.state.value.importedId != null }
        assertNull(ops.importedPassphrase)
        assertEquals(listOf("create:Old wallet:testnet4:true", "import"), ops.calls.filterNot { it == "list" })
    }

    @Test
    fun aWrongPasswordKeepsTheWordsForAnotherTry() = runTest {
        val vm = vm()
        vm.fill()
        vm.toLimits()
        vm.toPassword()
        vm.setPassword("wrong")
        vm.import()
        eventually { vm.state.value.error != null }
        assertEquals(FailureKind.BAD_PASSWORD, vm.state.value.error)
        assertEquals(ImportStep.PASSWORD, vm.state.value.step)
        assertEquals("", vm.state.value.password)
        assertNull(vm.state.value.importedId)
        vm.setPassword("pw")
        vm.import()
        eventually { vm.state.value.importedId != null }
        assertEquals(good, ops.importedWords)
    }

    @Test
    fun theVaultsLimitAndExistsHaveTheirOwnTexts() = runTest {
        ops.createFails = "limit"
        val vm = vm()
        vm.fill()
        vm.toLimits()
        vm.toPassword()
        vm.setPassword("pw")
        vm.import()
        eventually { vm.state.value.problem != null }
        assertEquals(ImportProblem.LIMIT, vm.state.value.problem)
        ops.createFails = "exists"
        vm.setPassword("pw")
        vm.import()
        eventually { vm.state.value.problem == ImportProblem.EXISTS }
        assertNull(vm.state.value.error)
    }

    @Test
    fun aLimitThatCouldNotBeSetLeavesThePendingLimitAndOffersTryAgain() = runTest {
        ops.capsSetFails = "internal"
        val vm = vm()
        vm.fill()
        vm.toLimits()
        vm.setLimitChoice(true)
        vm.setPerTx("500000")
        vm.toPassword()
        vm.setPassword("pw")
        vm.import()
        eventually { vm.state.value.importedId != null }
        assertEquals(ImportStep.DONE, vm.state.value.step)
        assertTrue(vm.state.value.capsFailed)
        assertEquals(setOf(vm.state.value.importedId), wallets.capsPending.value)
        ops.capsSetFails = null
        vm.retryCaps()
        eventually { !vm.state.value.capsFailed && !vm.state.value.busy }
        assertTrue(wallets.capsPending.value.isEmpty())
        assertTrue(ops.calls.none { it.startsWith("caps.raise") })
    }

    @Test
    fun backIsOneStepAndLeavesThePasswordBehind() = runTest {
        val vm = vm()
        vm.fill()
        vm.toLimits()
        vm.toPassword()
        vm.setPassword("pw")
        vm.previous()
        assertEquals(ImportStep.LIMITS, vm.state.value.step)
        assertEquals("", vm.state.value.password)
        vm.previous()
        assertEquals(ImportStep.FORM, vm.state.value.step)
        // The words are still there.
        assertEquals(good, vm.state.value.typed)
    }

    @Test
    fun theVaultClosingEndsTheFlowAndForgetsTheWords() = runTest {
        val vm = vm()
        vm.fill()
        vm.toggleAdvanced()
        vm.setPassphrase("secret")
        // The view model has seen the vault open before it closes.
        testScheduler.runCurrent()
        wallets.clear()
        eventually { vm.state.value.closed }
        assertEquals("", vm.state.value.typed)
        assertEquals("", vm.state.value.passphrase)
        // Nothing can be sent any more.
        vm.toLimits()
        assertEquals(ImportStep.FORM, vm.state.value.step)
    }

    @Test
    fun theStateNeverPrintsTheWordsThePassphraseOrThePassword() = runTest {
        val vm = vm()
        vm.fill()
        vm.setPassphrase("hunter2")
        vm.setPassword("pw-secret")
        val printed = vm.state.value.toString()
        for (s in listOf("legal", "winner", "hunter2", "pw-secret", "Old wallet")) assertFalse("'$s' in $printed", printed.contains(s))
        assertFalse(PhraseStatus(12, null, true).toString().contains("legal"))
    }

    @Test
    fun foldingAdvancedAwayForgetsThePassphrase() = runTest {
        val vm = vm()
        vm.fill()
        vm.toggleAdvanced()
        vm.setPassphrase("Correct Horse")
        vm.toggleAdvanced()
        assertEquals("", vm.state.value.passphrase)
        assertFalse(vm.state.value.advanced)
        vm.toLimits()
        vm.toPassword()
        vm.setPassword("pw")
        vm.import()
        eventually { vm.state.value.importedId != null }
        assertNull(ops.importedPassphrase)
    }

    @Test
    fun theChecksumIsCheckedAgainInsideImportBeforeAnythingIsSent() = runTest {
        val vm = vm()
        vm.fill()
        vm.toLimits()
        vm.toPassword()
        // The words change behind the steps (not reachable from the screens): nothing is sent for them.
        vm.setWords(badSum)
        vm.setPassword("pw")
        vm.import()
        assertEquals(ImportStep.FORM, vm.state.value.step)
        assertTrue(vm.state.value.invalidPhrase)
        assertEquals("", vm.state.value.password)
        assertTrue(ops.calls.none { it.startsWith("create") })
    }

    @Test
    fun aListedWalletWithoutCapsSkipsTheLimitsStep() = runTest {
        ops.wallets["01OLD00000000000000000000A"] = ops.wallet("01OLD00000000000000000000A").copy(capsSupported = false)
        val vm = vm()
        vm.fill()
        vm.toLimits()
        assertEquals(ImportStep.PASSWORD, vm.state.value.step)
        assertFalse(vm.state.value.limitsAsked)
        vm.previous()
        assertEquals(ImportStep.FORM, vm.state.value.step)
        vm.toLimits()
        vm.setPassword("pw")
        vm.import()
        eventually { vm.state.value.importedId != null }
        assertTrue(ops.calls.none { it.startsWith("caps.") })
        assertTrue(wallets.capsPending.value.isEmpty())
        assertEquals(ImportStep.PASSWORD, vm.state.value.step)
    }

    @Test
    fun aCreateAnswerWithoutCapsDropsTheLimitAndSaysSo() = runTest {
        ops.noCaps = true
        val vm = vm()
        vm.fill()
        vm.toLimits()
        vm.setLimitChoice(true)
        vm.setPerTx("500000")
        vm.toPassword()
        vm.setPassword("pw")
        vm.import()
        eventually { vm.state.value.importedId != null }
        assertEquals(ImportStep.DONE, vm.state.value.step)
        assertTrue(vm.state.value.capsUnsupported)
        assertFalse(vm.state.value.capsFailed)
        assertTrue(wallets.capsPending.value.isEmpty())
        assertTrue(ops.calls.none { it.startsWith("caps.") })
    }

    @Test
    fun aBackedUpFlagThatCannotBeWrittenIsSaid() = runTest {
        val vm = vm()
        vm.fill()
        vm.toLimits()
        vm.toPassword()
        vm.setPassword("pw")
        flagsFile.failSaves = true
        vm.import()
        eventually { vm.state.value.importedId != null }
        assertTrue(vm.state.value.notRecorded)
        assertEquals(ImportStep.DONE, vm.state.value.step)
    }
}
