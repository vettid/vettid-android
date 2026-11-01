package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.chain.Network
import com.vettid.core.wallet.WalletFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/** Create and back up, and back up later (ANDROID-PLAN §4 "Creating and backing up"). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ops = FakeWallets()
    private val flagsFile = MemoryFlags()

    /** The vault calls run on the IO dispatcher: wait for them in real time. */
    private suspend fun eventually(cond: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!cond()) delay(10)
            }
        }
    }

    private suspend fun TestScope.vm(saved: SavedStateHandle = SavedStateHandle()): BackupViewModel {
        val m = manager(backgroundScope, ops, WalletFlags(flagsFile))
        m.open()
        return BackupViewModel(saved, m, Random(7))
    }

    @Test
    fun aNewWalletIsCreatedShownCheckedAndBackedUp() = runTest {
        val vm = vm()
        assertEquals(BackupStep.FORM, vm.state.value.step)
        assertEquals(listOf(Network.SIGNET, Network.TESTNET4), vm.state.value.networks)
        vm.setName("Savings")
        vm.setNetwork(Network.TESTNET4)
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        // The password went once to the create and once to the reveal, and is gone from the state.
        assertEquals(listOf("create:Savings:testnet4:true", "reveal:01NEW000000000000000000000"), ops.calls.filterNot { it == "list" })
        assertEquals("", vm.state.value.password)
        assertEquals(BackupStep.PHRASE, vm.state.value.step)
        val shown = vm.state.value.words!!
        assertEquals(ops.words, List(shown.size) { shown.word(it) })
        vm.startCheck()
        val s = vm.state.value
        assertEquals(BackupStep.CHECK, s.step)
        assertEquals(3, s.asked.size)
        assertEquals(s.asked.sorted(), s.asked)
        assertEquals(4, s.candidates.size)
        assertTrue(s.asked[0] in s.candidates)
        // The three right ones, typed in any case.
        for (at in s.asked) vm.answer(" " + ops.words[at].uppercase() + " ")
        eventually { vm.state.value.step == BackupStep.DONE }
        assertEquals(BackupStep.DONE, vm.state.value.step)
        assertTrue(vm.state.value.backedUp)
        assertEquals(null, vm.state.value.words)
        // The words that were shown are wiped, not only dropped.
        assertEquals(String(CharArray(ops.words[0].length)), shown.word(0))
        assertTrue(WalletFlags(flagsFile).backedUp("01NEW000000000000000000000"))
    }

    @Test
    fun aPhraseStoredInCapitalsIsCheckedInAnyCase() = runTest {
        // The vault's phrase is kept as stored (`VaultWalletOps.phraseOf`): a tap on the candidate and the typed word both match.
        ops.words = List(12) { i -> "WORD${'A' + i}" }
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        vm.startCheck()
        val s = vm.state.value
        assertEquals(4, s.candidates.map { ops.words[it].lowercase() }.distinct().size)
        vm.pick(s.asked[0])
        vm.answer(ops.words[s.asked[1]].lowercase())
        vm.answer(ops.words[s.asked[2]])
        eventually { vm.state.value.step == BackupStep.DONE }
        assertTrue(vm.state.value.backedUp)
    }

    @Test
    fun theStateNeverPrintsTheWordsOrThePassword() = runTest {
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        val printed = vm.state.value.toString()
        assertFalse(printed.contains(ops.words[0]))
        assertFalse(printed.contains("pw"))
    }

    @Test
    fun aRevealThatFailsLeavesTheWalletToBeBackedUpFromItsRow() = runTest {
        ops.revealFails = true
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.DONE }
        assertEquals(BackupStep.DONE, vm.state.value.step)
        assertTrue(vm.state.value.revealFailed)
        assertFalse(WalletFlags(flagsFile).backedUp("01NEW000000000000000000000"))
    }

    @Test
    fun aWrongPasswordStaysOnTheFormWithItsText() = runTest {
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("wrong")
        vm.create()
        eventually { vm.state.value.error != null }
        assertEquals(BackupStep.FORM, vm.state.value.step)
        assertTrue(vm.state.value.error != null)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun theNameRule() {
        assertTrue(WalletNames.valid("Savings"))
        assertTrue(WalletNames.valid("x".repeat(128)))
        // The vault counts bytes of UTF-8: 64 two-byte letters fit, 65 do not; a blank name is no name.
        assertTrue(WalletNames.valid("é".repeat(64)))
        assertFalse(WalletNames.valid("é".repeat(65)))
        assertFalse(WalletNames.valid("   "))
        assertFalse(WalletNames.valid(""))
        assertFalse(WalletNames.valid("x".repeat(129)))
        assertFalse(WalletNames.valid("a${Char(7)}b"))
    }

    @Test
    fun aWalletNotBackedUpIsBackedUpFromItsRow() = runTest {
        ops.wallet("01OLD000000000000000000000")
        val vm = vm(SavedStateHandle(mapOf("walletId" to "01OLD000000000000000000000")))
        assertEquals(BackupStep.PASSWORD, vm.state.value.step)
        vm.setPassword("pw")
        vm.reveal()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        assertEquals(BackupStep.PHRASE, vm.state.value.step)
        assertTrue("reveal:01OLD000000000000000000000" in ops.calls)
        // "Show the words again" from the check goes back to them.
        vm.startCheck()
        vm.answer("nope")
        vm.showAgain()
        assertEquals(BackupStep.PHRASE, vm.state.value.step)
    }
    @Test
    fun theLastAnswerIsTakenOnceAndTheControlsWaitForTheSave() = runTest {
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        vm.startCheck()
        val s = vm.state.value
        vm.pick(s.asked[0])
        vm.answer(ops.words[s.asked[1]])
        vm.answer(ops.words[s.asked[2]])
        // A second tap while the save runs is ignored, and so is "Show the words again".
        vm.answer(ops.words[s.asked[2]])
        vm.showAgain()
        assertEquals(null, vm.state.value.wrong)
        eventually { vm.state.value.step == BackupStep.DONE }
        assertTrue(vm.state.value.backedUp)
        assertEquals(1, flagsFile.saves)
    }

    @Test
    fun aSaveThatFailsSaysSoAndIsTriedAgain() = runTest {
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        vm.startCheck()
        val s = vm.state.value
        flagsFile.failSaves = true
        for (at in s.asked) vm.answer(ops.words[at])
        eventually { vm.state.value.step == BackupStep.DONE && !vm.state.value.busy }
        assertTrue(vm.state.value.saveFailed)
        assertFalse(vm.state.value.backedUp)
        assertEquals(null, vm.state.value.words)
        flagsFile.failSaves = false
        vm.saveAgain()
        eventually { vm.state.value.backedUp }
        assertFalse(vm.state.value.saveFailed)
    }

    @Test
    fun theVaultClosingEndsTheFlowAndWipesTheWords() = runTest {
        val m = manager(backgroundScope, ops, WalletFlags(flagsFile))
        m.open()
        val vm = BackupViewModel(SavedStateHandle(), m, Random(7))
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        val shown = vm.state.value.words!!
        m.clear()
        eventually { vm.state.value.closed }
        assertEquals(null, vm.state.value.words)
        assertEquals(String(CharArray(ops.words[0].length)), shown.word(0))
    }

    @Test
    fun theListShowsBackedUpOnceTheCheckPassed() = runTest {
        val m = manager(backgroundScope, ops, WalletFlags(flagsFile))
        ops.wallet("01OLD000000000000000000000")
        m.open()
        val list = WalletsViewModel(m)
        eventually { list.state.value.rows.singleOrNull()?.backedUp == false }
        m.confirmBackup("01OLD000000000000000000000")
        eventually { list.state.value.rows.single().backedUp }
    }
    @Test
    fun aWrongAnswerEndsTheCheckAndTheNextAsksNewPositions() = runTest {
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        vm.startCheck()
        val first = vm.state.value.asked
        vm.pick(vm.state.value.candidates.first { it != first[0] && !ops.words[it].equals(ops.words[first[0]], ignoreCase = true) })
        assertEquals(first[0], vm.state.value.wrong)
        // Ended: no other candidate, typed word or the right one is taken any more.
        assertTrue(vm.state.value.candidates.isEmpty())
        vm.pick(first[0])
        vm.answer(ops.words[first[0]])
        assertEquals(0, vm.state.value.askIndex)
        assertEquals(first[0], vm.state.value.wrong)
        vm.showAgain()
        assertEquals(BackupStep.PHRASE, vm.state.value.step)
        // The next check draws its positions again (the seeded random gives another set here).
        var drawn = first
        repeat(5) {
            vm.startCheck()
            drawn = vm.state.value.asked
            if (drawn != first) return@repeat
        }
        assertTrue(drawn != first)
        assertEquals(null, vm.state.value.wrong)
    }

    @Test
    fun aRepeatedWordIsOfferedOnceAndEitherCopyOfItIsRight() = runTest {
        ops.words = List(12) { i -> if (i % 4 == 0) "same" else "word$i" }
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        vm.startCheck()
        val s = vm.state.value
        assertEquals(4, s.candidates.map { ops.words[it] }.distinct().size)
        for (at in s.asked) vm.answer(ops.words[at])
        eventually { vm.state.value.step == BackupStep.DONE }
        assertTrue(vm.state.value.backedUp)
    }

    @Test
    fun aPassphraseIsShownWithTheWordsAndWipedWithThem() = runTest {
        ops.passphrase = "Correct Horse"
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        val shown = vm.state.value.words!!
        assertTrue(shown.hasPassphrase)
        assertEquals("Correct Horse", shown.passphrase())
        vm.startCheck()
        for (at in vm.state.value.asked) vm.answer(ops.words[at])
        eventually { vm.state.value.step == BackupStep.DONE }
        assertEquals(String(CharArray("Correct Horse".length)), shown.passphrase())
    }

    @Test
    fun theVaultsLimitAndPinRequiredHaveTheirOwnTexts() = runTest {
        ops.createFails = "limit"
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.problem != null }
        assertEquals(BackupProblem.LIMIT, vm.state.value.problem)
        ops.wallet("01CAP000000000000000000000")
        ops.revealFails = false
        ops.revealCode = "pin_required"
        val back = vm(SavedStateHandle(mapOf("walletId" to "01CAP000000000000000000000")))
        back.setPassword("pw")
        back.reveal()
        eventually { back.state.value.problem != null }
        assertEquals(BackupProblem.PIN_REQUIRED, back.state.value.problem)
        assertTrue(back.state.value.capped)
        assertEquals("", back.state.value.password)
    }
    @Test
    fun aWalletWithASpendingLimitOffersNoRevealYet() = runTest {
        ops.wallets["01CAPPED00000000000000000A"] = ops.wallet("01CAPPED00000000000000000A").copy(capped = true)
        val vm = vm(SavedStateHandle(mapOf("walletId" to "01CAPPED00000000000000000A")))
        assertTrue(vm.state.value.capped)
        vm.setPassword("pw")
        vm.reveal()
        assertFalse(vm.state.value.busy)
        assertFalse(ops.calls.any { it.startsWith("reveal:") })
    }

    @Test
    fun typingNeverReopensAnEndedCheck() = runTest {
        val vm = vm()
        vm.setName("Savings")
        vm.setPassword("pw")
        vm.create()
        eventually { vm.state.value.step == BackupStep.PHRASE }
        vm.startCheck()
        val at = vm.state.value.asked[0]
        vm.answer("nope")
        vm.setTyped(ops.words[at])
        assertEquals(at, vm.state.value.wrong)
        assertEquals("", vm.state.value.typed)
    }

    @Test
    fun theNameSaysWhatIsWrong() {
        assertEquals(WalletNames.Problem.BLANK, WalletNames.problem("  "))
        assertEquals(WalletNames.Problem.TOO_LONG, WalletNames.problem("é".repeat(65)))
        assertEquals(WalletNames.Problem.CONTROL, WalletNames.problem("a${Char(7)}b"))
        assertEquals(null, WalletNames.problem("Savings"))
    }
}
