// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import android.app.Application
import com.vettid.core.chain.Network
import com.vettid.core.chain.PsbtBase64
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * Privacy, data directory scan, the core's half (ANDROID-PLAN §4 "Wallet", "Tests"; the view models' half is
 * `WalletDataDirectoryScanTest` in `:feature:wallet`). The real [WalletsManager] and [WalletFlags] (over the real
 * Keystore-encrypted file, with a fixed test key as the Keystore is not there under Robolectric) run against a fake vault and chain
 * server: create (the phrase shown and checked), reveal (`item.reveal` with the PIN), import (typed words and a passphrase) and send
 * (prepare, approve with the password, sign, broadcast). Then the app's whole data directory (files, `no_backup`, DataStore,
 * databases, caches, shared preferences) is scanned for the recovery words, the passphrases, the password, the PIN, the PSBT and the
 * signed transaction, as text and in binary, in every form of [SecretScan]. The only wallet file that may be there is
 * `wallet-flags.bin`, and its plain text holds none of them either. No seed or extended key exists app-side (the vault signs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WalletDataDirectoryScanTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-10-31T20:00:00Z")
    private val payee = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    private val receive = AddressChain("p2tr", false)

    // Secrets use [A-Za-z0-9 -] only: an XML or JSON file would store `&`, `<` or `"` escaped, and the plain needle would miss it.
    private val password = "Vx7-corr3ct-horse-pw"
    private val pin = "908172"
    private val newWords = listOf("saffron", "tundra", "velvet", "orchid", "glacier", "bramble", "cobalt", "lantern", "mosaic", "nimbus", "ember", "harbor")
    private val newPassphrase = "Tr0ub4dor 3-pass"
    private val importWords = listOf("juniper", "walnut", "pebble", "falcon", "cricket", "marble", "thistle", "violet", "anchor", "copper", "dahlia", "fennel")
    private val importPassphrase = "Im-p0rt Pass"

    private val testKey = { SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "AES") }
    private val flagsStore = KeystoreFileStore(File(app.noBackupFilesDir, "wallet-flags.bin"), "wallet-flags", key = testKey)
    private val flagsFile = object : WalletFlagsFile {
        override fun load(): ByteArray? = flagsStore.load()

        override fun save(bytes: ByteArray) = flagsStore.save(bytes)
    }

    private fun filesNow(): Set<String> = app.dataDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(app.dataDir).path }.toSet()

    @Test
    fun theScannerFindsASecretInEveryFormItLooksFor() {
        val dir = File(app.cacheDir, "control").apply { mkdirs() }
        val word = "saffron-wallet-word"
        val raw = ByteArray(40) { (it * 11 + 3).toByte() }
        val needles = SecretScan.forms("text", word) + SecretScan.forms("raw", raw)
        for (n in needles) File(dir, "${n.secret}-${n.form}").writeBytes(byteArrayOf(1, 2, 3, 4, 5) + n.bytes + byteArrayOf(9))
        // Base64 of the secret in the middle of a larger value, at each alignment.
        for (shift in 0..2) {
            val big = ByteArray(shift) { 1 } + word.encodeToByteArray() + byteArrayOf(5, 6, 7)
            File(dir, "inside-$shift").writeText("{\"x\":\"" + java.util.Base64.getEncoder().encodeToString(big) + "\"}")
        }
        File(dir, "clean").writeText("nothing here")
        val found = SecretScan.find(dir, needles).map { it.substringBefore(":").let(::File).name }.toSet()
        for (n in needles) assertTrue("${n.secret} ${n.form}", "${n.secret}-${n.form}" in found)
        assertTrue("inside-0 inside-1 inside-2".split(" ").all { it in found })
        assertTrue("clean" !in found)
        dir.deleteRecursively()
    }

    @Test
    @Suppress("LongMethod") // one pass through every flow, then one scan
    fun noSecretOfTheWalletFlowsIsLeftOnDisk() = runBlocking {
        withTimeout(60_000) {
            val before = filesNow()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // The chain server setting is the other file the wallet may write: a URL, and nothing else.
            val serversStore = KeystoreFileStore(File(app.noBackupFilesDir, "chain-servers.bin"), "chain-servers", key = testKey)
            // NOTE: this file is written by the test itself (a URL, through the same store): its presence below proves nothing about the
            // wallet; the check is that nothing of a secret is in it. The real writer (`ChainServers.save`) needs a server that answers
            // block 0, which `:app` tests (`ChainServersFileTest`) cover.
            serversStore.save("""{"signet":"https://mempool.space/signet/api"}""".encodeToByteArray())

            val ops = FakeWalletOps().apply {
                this.password = this@WalletDataDirectoryScanTest.password
                pin = this@WalletDataDirectoryScanTest.pin
                words = newWords
                passphrase = newPassphrase
            }
            val chain = FakeChain()
            var window = false
            ops.windowOpen = { window }
            ops.vaultClock = now
            val m = WalletsManager(
                scope,
                { ops },
                { n -> chain.open(n) },
                WalletFlags(flagsFile),
                { it == Network.SIGNET || it == Network.TESTNET4 },
                { now },
                windowOpen = { window },
                openWindow = { p ->
                    if (p != password) throw VaultFailure(FailureKind.BAD_PASSWORD, "bad_password")
                    window = true
                },
                closeWindow = { window = false },
                settleMs = 0,
            )
            // The wallet the send runs on: the vector wallet, funded, backed up.
            val sendWallet = WalletFixtures.wallet(nextReceive = 1)
            ops.wallets[sendWallet.id] = sendWallet
            ops.issued[sendWallet.id to receive] = mutableListOf(WalletFixtures.address(receive, 0))
            WalletFlags(flagsFile).apply {
                setPrivacyNoteShown(sendWallet.id)
                setBackedUp(sendWallet.id)
            }
            chain.fund(receive, 0, 100_000)
            m.open()

            val secrets = mutableListOf<SecretScan.Needle>()
            secrets += SecretScan.forms("password", password)
            secrets += SecretScan.forms("pin", pin)
            secrets += SecretScan.phrase("new words", newWords)
            secrets += SecretScan.forms("new passphrase", newPassphrase)
            secrets += SecretScan.phrase("import words", importWords)
            secrets += SecretScan.forms("import passphrase", importPassphrase)
            // Amounts are looked for in the logs only: a five-digit number is no secret in a binary file.
            val amounts = listOf("40000", "40,000", "100000", "100,000").flatMap { SecretScan.forms("amount $it", it).take(1) }
            fun scan(stage: String) {
                SecretScan.flush()
                val dirs = listOfNotNull(app.dataDir, app.getExternalFilesDir(null), app.externalCacheDir)
                for (d in dirs) assertEquals("$stage: $d", emptyList<String>(), SecretScan.find(d, secrets))
                assertEquals("$stage: logs", emptyList<String>(), SecretScan.logLeaks(secrets + amounts))
            }

            // Create: the phrase is shown (the manager hands it to the screen) and checked.
            val created = m.create("Savings", Network.TESTNET4, password.toCharArray())
            val phrase = created.phrase!!
            assertEquals(newWords, phrase.words().map { String(it) })
            assertTrue(m.confirmBackup(created.wallet.id))
            scan("after create and check")
            phrase.wipe()

            // Reveal with the PIN (a wallet with a spending limit): the words again.
            ops.wallets[created.wallet.id] = created.wallet.copy(capped = true)
            val shown = m.revealPhrase(created.wallet.id, password.toCharArray(), pin.toCharArray())
            assertEquals(newWords, shown.words().map { String(it) })
            assertEquals(newPassphrase, String(shown.passphrase()!!))
            shown.wipe()
            scan("after reveal")

            // Import: typed words and a passphrase.
            val imported = m.import("Old", Network.TESTNET4, password.toCharArray(), importWords.joinToString(" ").toCharArray(), importPassphrase.toCharArray())
            assertEquals(importWords.joinToString(" "), ops.importedWords)
            assertEquals(importPassphrase, ops.importedPassphrase)
            assertTrue(imported.wallet.id.isNotEmpty())
            scan("after import")

            // Send: prepare, approve with the password, sign, broadcast.
            m.recentSends(sendWallet.id)
            m.recentSends(sendWallet.id)
            val prepared = (m.prepareSend(sendWallet.id, SendRequest(payee, 40_000, 2.0, false)) as PrepareResult.Ready).prepared
            val sent = m.approveSend(prepared, password.toCharArray())
            assertTrue(sent.toString(), sent is SendResult.Sent)
            val psbt = ops.inspected.single()
            assertEquals(psbt, ops.signs.single())
            val signedTx = chain.spend.broadcasts.single()
            // The payee, the wallet's receive and change addresses, the txid, and the xpubs and descriptors (the plan's list).
            secrets += SecretScan.forms("payee address", payee)
            secrets += SecretScan.forms("txid", (sent as SendResult.Sent).txid)
            for (a in listOf(receive, AddressChain("p2tr", true), AddressChain("p2wpkh", false), AddressChain("p2wpkh", true))) {
                secrets += SecretScan.forms("address ${a.type}/${a.change}", WalletFixtures.address(a, 0).address)
            }
            for (acc in sendWallet.accounts) {
                secrets += SecretScan.forms("xpub ${acc.type}", acc.xpub)
                secrets += SecretScan.forms("descriptor ${acc.type}", acc.receiveDescriptor)
                secrets += SecretScan.forms("change descriptor ${acc.type}", acc.changeDescriptor)
            }
            secrets += SecretScan.forms("psbt (base64 text)", psbt)
            secrets += SecretScan.forms("psbt (bytes)", PsbtBase64.decode(psbt)!!)
            secrets += SecretScan.forms("signed transaction", signedTx)
            scan("after send")

            // Lock (the manager's clear), then the whole directory once more.
            m.clear()
            scan("after the lock")

            // What the flows left: the flags file and the chain server file, and nothing else.
            val added = filesNow() - before
            assertEquals(setOf("no_backup/wallet-flags.bin", "no_backup/chain-servers.bin"), added.map { it.replace('\\', '/') }.toSet())
            // Their plain text holds no secret either (the encrypted file is scanned above; this is what is inside it).
            for (store in listOf(flagsStore, serversStore)) {
                val plain = store.load()!!
                assertTrue(!SecretScan.contains(plain, secrets))
            }
            assertTrue(String(flagsStore.load()!!).isNotEmpty())
            // The scan covers the whole data directory, not only these places.
            listOf(app.filesDir, app.noBackupFilesDir, app.cacheDir, app.codeCacheDir, app.getDatabasePath("x").parentFile!!)
                .forEach { assertTrue(it.path, it.path.startsWith(app.dataDir.path)) }
            scope.cancel()
        }
    }
}
