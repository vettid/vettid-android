// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.chain.Network
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.wallet.WalletFlags
import com.vettid.core.wallet.WalletFlagsFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Privacy, data directory scan, the screens' half (ANDROID-PLAN §4 "Wallet", "Tests"; the manager's half, with the PSBT and the
 * signed transaction, is `WalletDataDirectoryScanTest` in `:core:wallet`). The real view models and [com.vettid.core.wallet.WalletsManager]
 * run against a fake vault: create (the phrase shown and checked), reveal (back up later, with the PIN), import (typed words and
 * a passphrase) and send (review, the password, approve). Then the app's whole data directory (files, `no_backup`, DataStore,
 * databases, caches, shared preferences) is scanned for the recovery words, the passphrases, the password and the PIN, as text and
 * in binary, in every form of [SecretScan]. The only wallet file there is `wallet-flags.bin` (real Keystore-encrypted file with
 * a fixed test key; the Keystore is not there under Robolectric), and its plain text holds none of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WalletDataDirectoryScanTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val app: Application = RuntimeEnvironment.getApplication()
    private val password = "Vx!7-corr3ct-horse-pw"
    private val pin = "908172"
    private val newWords = listOf("saffron", "tundra", "velvet", "orchid", "glacier", "bramble", "cobalt", "lantern", "mosaic", "nimbus", "ember", "harbor")
    private val newPassphrase = "Tr0ub4dor&3-pass"
    // A public BIP39 test phrase (not a wallet of anyone), as ImportViewModelTest uses.
    private val importPhrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val importPassphrase = "Im-p0rt P@ss"

    private val testKey = { SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "AES") }
    private val flagsStore = KeystoreFileStore(File(app.noBackupFilesDir, "wallet-flags.bin"), "wallet-flags", key = testKey)
    private val flagsFile = object : WalletFlagsFile {
        override fun load(): ByteArray? = flagsStore.load()

        override fun save(bytes: ByteArray) = flagsStore.save(bytes)
    }

    private fun filesNow(): Set<String> = app.dataDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(app.dataDir).path.replace('\\', '/') }.toSet()

    private suspend fun TestScope.flow(): Pair<FakeWallets, com.vettid.core.wallet.WalletsManager> {
        val ops = FakeWallets().apply {
            this.password = this@WalletDataDirectoryScanTest.password
            pin = this@WalletDataDirectoryScanTest.pin
            words = newWords
            passphrase = newPassphrase
        }
        val m = manager(backgroundScope, ops, WalletFlags(flagsFile))
        m.open()
        return ops to m
    }

    @Test
    @Suppress("LongMethod") // one pass through every flow, then one scan
    fun noSecretOfTheWalletScreensIsLeftOnDisk() = runTest {
        val before = filesNow()
        val (ops, m) = flow()
        val secrets = mutableListOf<SecretScan.Needle>()
        secrets += SecretScan.forms("password", password)
        secrets += SecretScan.forms("pin", pin)
        secrets += SecretScan.phrase("new words", newWords)
        secrets += SecretScan.forms("new passphrase", newPassphrase)
        secrets += SecretScan.phrase("import words", importPhrase.split(" "))
        secrets += SecretScan.forms("import passphrase", importPassphrase)
        fun scan(stage: String) = assertEquals(stage, emptyList<String>(), SecretScan.find(app.dataDir, secrets))

        // Create: the phrase is shown, then the check of three words (any case).
        val create = BackupViewModel(SavedStateHandle(), m, Random(7))
        create.setName("Savings")
        create.setNetwork(Network.TESTNET4)
        create.setPassword(password)
        create.create()
        eventually { create.state.value.step == BackupStep.PHRASE }
        val shown = create.state.value.words!!
        assertEquals(newWords, List(shown.size) { shown.word(it) })
        scan("while the phrase is shown")
        create.startCheck()
        val asked = create.state.value.asked
        scan("during the check")
        for (at in asked) create.answer(" " + newWords[at].uppercase() + " ")
        eventually { create.state.value.step == BackupStep.DONE }
        val id = "01NEW000000000000000000000"
        assertTrue(WalletFlags(flagsFile).backedUp(id))
        scan("after create and check")

        // Reveal (back up later from a row) of a wallet with a spending limit: password and PIN.
        val capped = "01CAPPED00000000000000000A"
        ops.wallets[capped] = ops.wallet(capped, "Spending").copy(capped = true)
        m.open()
        val reveal = BackupViewModel(SavedStateHandle(mapOf("walletId" to capped)), m, Random(7))
        reveal.setPassword(password)
        reveal.setPin(pin)
        reveal.reveal()
        eventually { reveal.state.value.step == BackupStep.PHRASE }
        assertTrue("reveal:$capped:pin" in ops.calls)
        scan("while the revealed phrase is shown")
        reveal.startCheck()
        for (at in reveal.state.value.asked) reveal.answer(newWords[at])
        eventually { reveal.state.value.step == BackupStep.DONE }
        scan("after the reveal and its check")

        // Import: typed words and a passphrase.
        val import = ImportViewModel(m)
        import.setName("Old wallet")
        import.setNetwork(Network.TESTNET4)
        import.setWords(importPhrase)
        import.toggleAdvanced()
        import.setPassphrase(importPassphrase)
        import.toLimits()
        import.toPassword()
        import.setPassword(password)
        import.import()
        eventually { import.state.value.importedId != null }
        assertEquals(importPhrase, ops.importedWords)
        assertEquals(importPassphrase, ops.importedPassphrase)
        scan("after import")

        // Send: the form, the review, the password, approve (the PSBT and the signed transaction are the core test's).
        val sendId = "01WALLET0000000000000000AA"
        ops.wallet(sendId)
        WalletFlags(flagsFile).apply {
            setBackedUp(sendId)
            setPrivacyNoteShown(sendId)
        }
        m.open()
        val engine = FakeEngine()
        val send = SendViewModel(SavedStateHandle(mapOf("walletId" to sendId)), m, FakeServerSettings(), engine)
        eventually { send.state.value.estimates != null }
        send.setAddress(PAYEE)
        send.setUnit(AmountUnit.SATS)
        send.setAmount("40000")
        send.review()
        eventually { send.state.value.step == SendStep.REVIEW }
        send.setPassword(password)
        scan("while the review waits for the password")
        send.approve()
        eventually { send.state.value.step == SendStep.DONE }
        assertEquals(listOf(password), engine.passwords)
        scan("after send")

        // The lock: the screens' state is gone, the directory still holds none of them.
        m.clear()
        scan("after the lock")

        // Only the flags file was written by all of that.
        assertEquals(setOf("no_backup/wallet-flags.bin"), filesNow() - before)
        assertTrue(!SecretScan.contains(flagsStore.load()!!, secrets))
        listOf(app.filesDir, app.noBackupFilesDir, app.cacheDir, app.codeCacheDir, app.getDatabasePath("x").parentFile!!)
            .forEach { assertTrue(it.path, it.path.startsWith(app.dataDir.path)) }
    }
}
