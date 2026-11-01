// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Spending limits at creation and import (ANDROID-PLAN §4 "Spending limits", W1d; VAULT-MESSAGING §10.18, 0.56.0): the rules, the
 * pending limits on this phone, `wallet.caps.set` after the backup (and first after a restart), no Receive until it is set, and a
 * `wallet.caps.raise` that fails leaving the limits as they were.
 */
class WalletCapsTest {
    private val ops = FakeWalletOps()
    private val chain = FakeChain()
    private val flagsFile = object : WalletFlagsFile {
        var bytes: ByteArray? = null
        var failLoad = false
        var failSave = false
        var saves = 0

        override fun load(): ByteArray? {
            check(!failLoad) { "cannot decrypt" }
            return bytes
        }

        override fun save(bytes: ByteArray) {
            check(!failSave) { "disk full" }
            saves++
            this.bytes = bytes
        }
    }
    private val caps = WalletCaps(500_000, 2_000_000)

    private fun manager() = WalletsManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { ops },
        { n -> chain.open(n) },
        WalletFlags(flagsFile),
        { it == Network.SIGNET || it == Network.TESTNET4 },
        { Instant.parse("2026-10-31T20:00:00Z") },
    )

    // The rules

    @Test
    fun theRulesAreTheVaults() {
        assertNull(CapsRules.problem(null, null))
        assertNull(CapsRules.problem(1, 1))
        assertNull(CapsRules.problem(500_000, 2_000_000))
        assertNull(CapsRules.problem(null, CapsRules.MAX_SATS))
        assertEquals(CapsRules.Problem.OUT_OF_RANGE, CapsRules.problem(0, null))
        assertEquals(CapsRules.Problem.OUT_OF_RANGE, CapsRules.problem(null, -5))
        assertEquals(CapsRules.Problem.OUT_OF_RANGE, CapsRules.problem(CapsRules.MAX_SATS + 1, null))
        assertEquals(CapsRules.Problem.TX_ABOVE_PERIOD, CapsRules.problem(2_000_001, 2_000_000))
        assertEquals(2_100_000_000_000_000L, CapsRules.MAX_SATS)
        assertTrue(WalletCaps(null, null).none)
        assertEquals("WalletCaps", caps.toString())
    }

    // The flags

    @Test
    fun pendingLimitsAreKeptPerWalletAndAnOldFileStillReads() = runBlocking {
        flagsFile.bytes = """{"version":1,"backed_up":["W1"],"testnet":{},"note_shown":[]}""".encodeToByteArray()
        val f = WalletFlags(flagsFile)
        assertTrue(f.backedUp("W1"))
        assertNull(f.pendingCaps("W1"))
        assertTrue(f.setPendingCaps("W1", caps))
        assertTrue(f.setPendingCaps("W2", WalletCaps(null, 9)))
        val again = WalletFlags(flagsFile)
        assertEquals(caps, again.pendingCaps("W1"))
        assertEquals(WalletCaps(null, 9), again.pendingCaps("W2"))
        assertEquals(setOf("W1", "W2"), again.allPendingCaps()!!.keys)
        assertTrue(again.backedUp("W1"))
        assertTrue(again.clearPendingCaps("W1"))
        assertNull(again.pendingCaps("W1"))
        assertTrue(again.forget("W2"))
        assertTrue(again.allPendingCaps()!!.isEmpty())
        // Nothing pending: the file is as it was before W1d.
        assertEquals("""{"version":1,"backed_up":["W1"],"testnet":{},"note_shown":[]}""", flagsFile.bytes!!.decodeToString())
    }

    @Test
    fun aPendingEntryThePlanCouldNotHaveWrittenMakesTheFileUnreadable() = runBlocking {
        flagsFile.bytes = """{"version":1,"backed_up":["W1"],"testnet":{},"note_shown":[],"pending_caps":{"W1":{"per_tx_sats":0}}}""".encodeToByteArray()
        val f = WalletFlags(flagsFile)
        // Fails safe like every flag: unreadable reads as "not backed up" and nothing pending.
        assertFalse(f.backedUp("W1"))
        assertNull(f.pendingCaps("W1"))
    }

    // Creation: without limits, then set after the check

    @Test
    fun aWalletIsCreatedWithoutLimitsAndTheChosenOnesWaitForTheCheck() = runBlocking {
        val m = manager()
        m.open()
        val c = m.create("Savings", Network.TESTNET4, "pw", caps)
        val id = c.wallet.id
        // `wallet.create` carries no caps and nothing was set yet; the reveal needs no PIN.
        assertEquals(listOf("list", "create:testnet4", "reveal"), ops.calls)
        assertFalse(c.wallet.capped)
        assertEquals(setOf(id), m.capsPending.value)
        assertEquals(caps, WalletFlags(flagsFile).pendingCaps(id))
        // No Receive until the limits are set, and no address was issued.
        assertEquals(ReceiveResult.CapsPending, m.receiveAddress(id))
        assertFalse("new" in ops.calls)
        assertEquals(ReceiveResult.CapsPending, m.newReceiveAddress(id))

        // The phrase checked: the limits go.
        m.confirmBackup(id)
        m.settleCaps(id)
        assertEquals("caps.set:500000:2000000", ops.calls.last())
        assertTrue(m.capsPending.value.isEmpty())
        assertNull(WalletFlags(flagsFile).pendingCaps(id))
        val w = m.wallets.value.single { it.id == id }
        assertTrue(w.capped)
        assertEquals(500_000L, w.perTxSats)
        assertEquals(2_000_000L, w.perPeriodSats)
        assertTrue(m.receiveAddress(id) is ReceiveResult.Address)
        assertFalse(ops.calls.any { it.startsWith("caps.raise") })
    }

    @Test
    fun noLimitIsTheDefaultAndSetsNothing() = runBlocking {
        val m = manager()
        m.open()
        val none = m.create("Savings", Network.TESTNET4, "pw")
        val alsoNone = m.create("Spending", Network.TESTNET4, "pw", WalletCaps(null, null))
        assertTrue(m.capsPending.value.isEmpty())
        assertTrue(WalletFlags(flagsFile).allPendingCaps()!!.isEmpty())
        m.settleCaps(none.wallet.id)
        m.settleCaps(alsoNone.wallet.id)
        assertFalse(ops.calls.any { it.startsWith("caps.") })
        assertTrue(m.receiveAddress(none.wallet.id) is ReceiveResult.Address)
    }

    @Test
    fun limitsTheVaultWouldRefuseAreNotSent() {
        val m = manager()
        for (bad in listOf(WalletCaps(0, null), WalletCaps(5, 4), WalletCaps(null, CapsRules.MAX_SATS + 1))) {
            val e = runCatching { runBlocking { m.create("x", Network.TESTNET4, "pw", bad) } }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException)
        }
        assertTrue(ops.calls.none { it.startsWith("create") })
    }

    @Test
    fun aLimitSetBeforeTheCheckInTheSameSessionIsNotSentByTheListOrAnEvent() = runBlocking {
        val m = manager()
        m.open()
        val c = m.create("Savings", Network.TESTNET4, "pw", caps)
        // The vault's list again (an event, a refresh) and a second open of the session: the wallet is held until its check.
        m.refresh()
        m.open()
        assertFalse(ops.calls.any { it.startsWith("caps.") })
        assertEquals(setOf(c.wallet.id), m.capsPending.value)
        // The reveal for the check still works without a PIN (it is the backup flow's own).
        assertTrue(m.revealPhrase(c.wallet.id, "pw").wordCount == 24)
    }

    // After a restart

    @Test
    fun afterARestartThePendingLimitIsSentBeforeAnythingElseForThatWallet() = runBlocking {
        val first = manager()
        first.open()
        val id = first.create("Savings", Network.TESTNET4, "pw", caps).wallet.id
        // The process dies here: the phrase screen was open. A new manager over the same files, the same vault.
        ops.calls.clear()
        val m = manager()
        m.open()
        assertEquals(listOf("list", "caps.set:500000:2000000"), ops.calls)
        assertTrue(m.capsPending.value.isEmpty())
        assertNull(WalletFlags(flagsFile).pendingCaps(id))
        assertTrue(m.receiveAddress(id) is ReceiveResult.Address)
        // Receive's own requests came after the set.
        assertTrue(ops.calls.indexOf("caps.set:500000:2000000") < ops.calls.indexOfFirst { it == "new" || it.startsWith("list:") })
    }

    @Test
    fun aFailedSendKeepsItPendingWithNoReceiveAndNeverRaises() = runBlocking {
        val first = manager()
        first.open()
        val id = first.create("Savings", Network.TESTNET4, "pw", caps).wallet.id
        ops.calls.clear()
        ops.capsSetFails = "internal"
        val m = manager()
        m.open()
        assertEquals(setOf(id), m.capsPending.value)
        assertEquals(caps, WalletFlags(flagsFile).pendingCaps(id))
        // Receive tries again first, fails, and offers nothing; no address was issued or listed for the wallet.
        assertEquals(ReceiveResult.CapsPending, m.receiveAddress(id))
        assertEquals(2, ops.calls.count { it.startsWith("caps.set") })
        assertTrue(ops.calls.none { it == "new" || it.startsWith("list:") })
        // A look-up, the address type and a phrase reveal each start with the send too, and fail with it.
        assertTrue(m.lookup(id) is LookupResult.VaultFailed)
        assertEquals(FailureKind.OTHER, runCatching { m.setAddressType(id, "p2wpkh") }.exceptionOrNull().let { (it as VaultFailure).kind })
        assertTrue(runCatching { m.revealPhrase(id, "pw") }.exceptionOrNull() is VaultFailure)
        assertTrue(ops.calls.none { it.startsWith("update") || it == "reveal" })
        // The retry is a set, only ever a set.
        assertTrue(ops.calls.none { it.startsWith("caps.raise") })
        // The vault is back: Try again sets it.
        ops.capsSetFails = null
        m.settleCaps(id)
        assertTrue(m.capsPending.value.isEmpty())
        assertTrue(m.receiveAddress(id) is ReceiveResult.Address)
    }

    @Test
    fun aStaleVersionReadsTheWalletAgainAndSendsOnce() = runBlocking {
        val m = manager()
        m.open()
        val id = m.create("Savings", Network.TESTNET4, "pw", caps).wallet.id
        // Another device (or an address) moved the wallet's version.
        ops.wallets[id] = ops.wallets.getValue(id).copy(version = 9)
        m.settleCaps(id)
        assertEquals(2, ops.calls.count { it.startsWith("caps.set") })
        assertEquals(10L, m.wallets.value.single { it.id == id }.version)
        assertTrue(m.capsPending.value.isEmpty())
    }

    @Test
    fun aWalletThatAlreadyHasStricterLimitsDropsTheStalePendingOne() = runBlocking {
        val m = manager()
        m.open()
        val id = m.create("Savings", Network.TESTNET4, "pw", caps).wallet.id
        // The limits were lowered meanwhile (another phone): 500,000 would be a raise, so the vault says `bad_request`.
        ops.wallets[id] = ops.wallets.getValue(id).copy(capped = true, perTxSats = 100_000, perPeriodSats = 1_000_000, version = 5)
        m.settleCaps(id)
        assertTrue(m.capsPending.value.isEmpty())
        assertNull(WalletFlags(flagsFile).pendingCaps(id))
        val w = m.wallets.value.single { it.id == id }
        assertEquals(100_000L, w.perTxSats)
        assertFalse(ops.calls.any { it.startsWith("caps.raise") })
    }

    @Test
    fun aRefusedSetThatIsNotAlreadyStrictStaysPendingAndRaisesNothing() = runBlocking {
        val m = manager()
        m.open()
        val id = m.create("Savings", Network.TESTNET4, "pw", caps).wallet.id
        // One member is stricter, the other would be a raise: not "already as strict".
        ops.wallets[id] = ops.wallets.getValue(id).copy(capped = true, perTxSats = 100_000, perPeriodSats = 9_000_000, version = 5)
        val e = runCatching { m.settleCaps(id) }.exceptionOrNull() as VaultFailure
        assertEquals("bad_request", e.code)
        assertEquals(setOf(id), m.capsPending.value)
        assertFalse(ops.calls.any { it.startsWith("caps.raise") })
        assertEquals(9_000_000L, ops.wallets.getValue(id).perPeriodSats)
    }

    // Import

    @Test
    fun anImportSendsTheWordsStartsBackedUpAndSetsTheLimitsAtOnce() = runBlocking {
        val m = manager()
        m.open()
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val r = m.import("Old", Network.TESTNET4, "pw", words.toCharArray(), "Correct Horse".toCharArray(), caps)
        assertNull(r.capsFailure)
        assertEquals(words, ops.importedWords)
        assertEquals("Correct Horse", ops.importedPassphrase)
        // No phrase is revealed (the member typed it); the wallet is backed up; the limits followed the create.
        assertEquals(listOf("list", "create:testnet4", "import", "caps.set:500000:2000000"), ops.calls)
        assertTrue(m.backedUp(r.wallet.id))
        assertTrue(m.capsPending.value.isEmpty())
        assertTrue(m.wallets.value.single().capped)
        assertTrue(m.receiveAddress(r.wallet.id) is ReceiveResult.Address)
    }

    @Test
    fun anImportWithoutLimitsOrPassphraseSendsOnlyTheCreate() = runBlocking {
        val m = manager()
        m.open()
        val r = m.import("Old", Network.SIGNET, "pw", "a b c".toCharArray(), null)
        assertNull(r.capsFailure)
        assertNull(ops.importedPassphrase)
        assertEquals(listOf("list", "create:signet", "import"), ops.calls)
        assertTrue(m.backedUp(r.wallet.id))
    }

    @Test
    fun anImportWhoseLimitsFailedKeepsThemPendingWithNoReceive() = runBlocking {
        ops.capsSetFails = "internal"
        val m = manager()
        m.open()
        val r = m.import("Old", Network.TESTNET4, "pw", "a b c".toCharArray(), null, caps)
        assertEquals(FailureKind.OTHER, r.capsFailure?.kind)
        assertEquals(setOf(r.wallet.id), m.capsPending.value)
        assertEquals(caps, WalletFlags(flagsFile).pendingCaps(r.wallet.id))
        assertEquals(ReceiveResult.CapsPending, m.receiveAddress(r.wallet.id))
        // A restart (the vault is fine now) sends it first.
        ops.capsSetFails = null
        ops.calls.clear()
        val again = manager()
        again.open()
        assertEquals(listOf("list", "caps.set:500000:2000000"), ops.calls)
    }

    @Test
    fun aFailedImportKeepsNothing() = runBlocking {
        val m = manager()
        m.open()
        val e = runCatching { m.import("Old", Network.TESTNET4, "wrong", "a b c".toCharArray(), null, caps) }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.BAD_PASSWORD, e.kind)
        assertTrue(m.capsPending.value.isEmpty())
        assertTrue(WalletFlags(flagsFile).allPendingCaps()!!.isEmpty())
        assertTrue(m.wallets.value.isEmpty())
    }

    // Lower, raise

    private fun capped(id: String = "01WALLET000000000000000001") =
        WalletFixtures.wallet(id = id).copy(capped = true, perTxSats = 500_000, perPeriodSats = 2_000_000).also { ops.wallets[id] = it }

    @Test
    fun aRaiseThatFailsLeavesTheOldLimitsEverywhere() = runBlocking {
        val w = capped()
        val m = manager()
        m.open()
        for ((pin, password, code) in listOf(
            Triple("000000", "pw", FailureKind.BAD_PIN),
            Triple("123456", "wrong", FailureKind.BAD_PASSWORD),
        )) {
            val e = runCatching { m.raiseCaps(w.id, null, 3_000_000, pin, password) }.exceptionOrNull() as VaultFailure
            assertEquals(code, e.kind)
        }
        ops.capsRaiseFails = "backoff"
        assertEquals(FailureKind.BACKOFF, (runCatching { m.raiseCaps(w.id, null, 3_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure).kind)
        // The vault's wallet and the app's are as they were.
        val vault = ops.wallets.getValue(w.id)
        assertEquals(500_000L, vault.perTxSats)
        assertEquals(2_000_000L, vault.perPeriodSats)
        assertEquals(w.version, vault.version)
        val mine = m.wallets.value.single()
        assertEquals(500_000L, mine.perTxSats)
        assertEquals(2_000_000L, mine.perPeriodSats)
        assertEquals(w.version, mine.version)
        assertTrue(mine.capped)
        // Each attempt was one credential operation; no set was sent in its place.
        assertEquals(3, ops.calls.count { it.startsWith("caps.raise") })
        assertTrue(ops.calls.none { it.startsWith("caps.set") })
    }

    @Test
    fun aRaiseThatSucceedsTakesTheNewLimitsAndRemovalIsNull() = runBlocking {
        val w = capped()
        val m = manager()
        m.open()
        m.raiseCaps(w.id, null, 2_000_000, "123456", "pw")
        assertEquals("caps.raise:null:2000000", ops.calls.last())
        var mine = m.wallets.value.single()
        assertNull(mine.perTxSats)
        assertEquals(2_000_000L, mine.perPeriodSats)
        assertTrue(mine.capped)
        assertEquals(w.version + 1, mine.version)
        m.raiseCaps(w.id, null, null, "123456", "pw")
        mine = m.wallets.value.single()
        assertFalse(mine.capped)
        assertNull(mine.perPeriodSats)
    }

    @Test
    fun aRaiseOverAConflictReadsTheWalletAndDoesNotSendTheSecretsAgain() = runBlocking {
        val w = capped()
        val m = manager()
        m.open()
        ops.wallets[w.id] = ops.wallets.getValue(w.id).copy(version = 8)
        val e = runCatching { m.raiseCaps(w.id, null, 2_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.CONFLICT, e.kind)
        assertEquals(1, ops.calls.count { it.startsWith("caps.raise") })
        assertEquals(8L, m.wallets.value.single().version)
        assertEquals(500_000L, m.wallets.value.single().perTxSats)
    }

    @Test
    fun aRaiseThatWouldBeRefusedByTheRulesIsNeverSent() {
        val w = capped()
        val m = manager()
        runBlocking { m.open() }
        assertTrue(runCatching { runBlocking { m.raiseCaps(w.id, 3_000_000, 2_000_000, "123456", "pw") } }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(ops.calls.none { it.startsWith("caps.") })
    }

    @Test
    fun lowerSendsASetWithNoPasswordAndOnlyTheGivenMembers() = runBlocking {
        val w = capped()
        val m = manager()
        m.open()
        m.lowerCaps(w.id, 100_000, null)
        assertEquals("caps.set:100000:null", ops.calls.last())
        val mine = m.wallets.value.single()
        assertEquals(100_000L, mine.perTxSats)
        assertEquals(2_000_000L, mine.perPeriodSats)
        assertTrue(runCatching { m.lowerCaps(w.id, null, null) }.exceptionOrNull() is IllegalArgumentException)
        // A value above the current one is the vault's `bad_request` (use_wallet_caps_raise); the wallet stays as it was.
        val e = runCatching { m.lowerCaps(w.id, 400_000, null) }.exceptionOrNull() as VaultFailure
        assertEquals("bad_request", e.code)
        assertEquals(100_000L, m.wallets.value.single().perTxSats)
    }

    // The phrase of a capped wallet

    @Test
    fun aCappedWalletsPhraseNeedsThePinAsWellAsThePassword() = runBlocking {
        val w = capped()
        val m = manager()
        m.open()
        val none = runCatching { m.revealPhrase(w.id, "pw") }.exceptionOrNull() as VaultFailure
        assertEquals("pin_required", none.code)
        val wrong = runCatching { m.revealPhrase(w.id, "pw", "000000") }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.BAD_PIN, wrong.kind)
        assertEquals(24, m.revealPhrase(w.id, "pw", "123456").wordCount)
        // A wallet without limits reveals with the password alone.
        val plain = WalletFixtures.wallet(id = "01WALLET000000000000000002").also { ops.wallets[it.id] = it }
        m.refresh()
        assertEquals(24, m.revealPhrase(plain.id, "pw").wordCount)
    }

    @Test
    fun theWalletsLimitsComeFromTheVaultsList() {
        val json = WalletFixtures.walletJson(caps = """{"per_tx_sats":500000,"period_seconds":86400,"spent_sats":0}""")
        val w = WalletWire.wallet(com.vettid.core.vault.VaultJson.parseObject(json.encodeToByteArray()))
        assertTrue(w.capped)
        assertEquals(500_000L, w.perTxSats)
        assertNull(w.perPeriodSats)
        assertFalse(WalletFixtures.wallet().capped)
    }

    // A flags file that fails to load or to save (fail secure)

    @Test
    fun aFileThatCannotBeLoadedIsNeverWrittenOverAndItsLimitsStayUnknown() = runBlocking {
        val f = WalletFlags(flagsFile)
        assertTrue(f.setPendingCaps("W1", caps))
        assertTrue(f.setBackedUp("W1"))
        val before = flagsFile.bytes!!.decodeToString()
        val saves = flagsFile.saves
        flagsFile.failLoad = true
        // Nothing can be changed, and nothing is written: the pending limits are still in the file.
        assertFalse(f.setBackedUp("W2"))
        assertFalse(f.setPendingCaps("W2", caps))
        assertFalse(f.clearPendingCaps("W1"))
        assertFalse(f.forget("W1"))
        assertEquals(saves, flagsFile.saves)
        // Unknown, not empty.
        assertNull(f.allPendingCaps())
        flagsFile.failLoad = false
        assertEquals(before, flagsFile.bytes!!.decodeToString())
        assertEquals(caps, f.allPendingCaps()!!["W1"])
        assertTrue(f.setBackedUp("W2"))
    }

    @Test
    fun aFileThatCannotBeSavedSaysSoAndKeepsWhatItHad() = runBlocking {
        val f = WalletFlags(flagsFile)
        assertTrue(f.setPendingCaps("W1", caps))
        flagsFile.failSave = true
        assertFalse(f.setPendingCaps("W2", caps))
        assertFalse(f.clearPendingCaps("W1"))
        flagsFile.failSave = false
        assertEquals(setOf("W1"), f.allPendingCaps()!!.keys)
    }

    @Test
    fun anUnreadableFlagsFileMeansNoReceiveForAnyWalletUntilItReads() = runBlocking {
        val first = manager()
        first.open()
        val id = first.create("Savings", Network.TESTNET4, "pw", caps).wallet.id
        val other = WalletFixtures.wallet(id = "01WALLET000000000000000009", network = "testnet4").also { ops.wallets[it.id] = it }
        ops.calls.clear()
        flagsFile.failLoad = true
        val m = manager()
        m.open()
        // Nobody knows which wallets have a limit to set: every wallet is held back, the existing pending text is shown.
        assertTrue(WalletsManager.UNKNOWN in m.capsPending.value)
        assertTrue(WalletsManager.isPending(m.capsPending.value, other.id))
        assertEquals(ReceiveResult.CapsPending, m.receiveAddress(other.id))
        assertEquals(ReceiveResult.CapsPending, m.receiveAddress(id))
        assertTrue(ops.calls.none { it == "new" || it.startsWith("list:") || it.startsWith("caps.") })
        // The file reads again: the pending limit is sent first, and Receive is offered.
        flagsFile.failLoad = false
        assertTrue(m.receiveAddress(other.id) is ReceiveResult.Address)
        // Only the wallet that has a limit to set stays held back.
        assertEquals(setOf(id), m.capsPending.value)
        assertTrue(m.receiveAddress(id) is ReceiveResult.Address)
        assertTrue(m.capsPending.value.isEmpty())
        assertEquals("caps.set:500000:2000000", ops.calls.first { it.startsWith("caps.") })
        assertTrue(ops.calls.none { it.startsWith("caps.raise") })
    }

    @Test
    fun limitsThatCouldNotBeSavedAreSaidAndStillSetInThisSession() = runBlocking {
        val m = manager()
        m.open()
        flagsFile.failSave = true
        val c = m.create("Savings", Network.TESTNET4, "pw", caps)
        assertTrue(c.capsNotSaved)
        assertNull(c.revealFailure)
        assertEquals(setOf(c.wallet.id), m.capsPending.value)
        assertEquals(ReceiveResult.CapsPending, m.receiveAddress(c.wallet.id))
        m.settleCaps(c.wallet.id)
        assertEquals("caps.set:500000:2000000", ops.calls.last())
        assertTrue(m.capsPending.value.isEmpty())
        // A save that works says nothing.
        flagsFile.failSave = false
        assertFalse(m.create("Spending", Network.TESTNET4, "pw", caps).capsNotSaved)
    }

    @Test
    fun anImportWhoseBackedUpFlagCannotBeWrittenSaysSo() = runBlocking {
        val m = manager()
        m.open()
        flagsFile.failSave = true
        val r = m.import("Old", Network.TESTNET4, "pw", "a b c".toCharArray(), null)
        assertTrue(r.notRecorded)
        assertFalse(r.capsNotSaved)
        flagsFile.failSave = false
        assertFalse(m.import("Older", Network.TESTNET4, "pw", "a b c".toCharArray(), null).notRecorded)
    }

    @Test
    fun theLimitJustSetMakesThePhraseNeedThePinBeforeAnythingIsSent() = runBlocking {
        val first = manager()
        first.open()
        val id = first.create("Savings", Network.TESTNET4, "pw", caps).wallet.id
        ops.calls.clear()
        // A restart: the pending limit is first in line (it fails once), then Back up asks for the phrase without a PIN.
        ops.capsSetFails = "internal"
        val m = manager()
        m.open()
        ops.capsSetFails = null
        val e = runCatching { m.revealPhrase(id, "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(WalletsManager.PIN_REQUIRED, e.code)
        assertTrue("the password was not sent", ops.calls.none { it == "reveal" })
        assertTrue(m.wallets.value.single { it.id == id }.capped)
        assertEquals(24, m.revealPhrase(id, "pw", "123456").wordCount)
    }

    // A vault before release 26: no `caps` on its wallets

    @Test
    fun aListedWalletWithoutCapsMeansTheLimitsAreNotOffered() = runBlocking {
        val m = manager()
        m.open()
        assertTrue("no wallets yet: asked as usual", m.capsOffered)
        val old = WalletFixtures.wallet(id = "01OLD00000000000000000000A", caps = null)
        ops.wallets[old.id] = old
        m.refresh()
        assertFalse(m.wallets.value.single().capsSupported)
        assertFalse(m.capsOffered)
        // With caps present, even an empty object, it is offered.
        ops.wallets.clear()
        ops.wallets["01NEW"] = WalletFixtures.wallet(id = "01NEW00000000000000000000A", caps = "{}")
        m.refresh()
        assertTrue(m.wallets.value.single().capsSupported)
        assertTrue(m.capsOffered)
    }

    @Test
    fun aCreateAnswerWithoutCapsKeepsNothingPendingAndSendsNoSet() = runBlocking {
        ops.noCaps = true
        val m = manager()
        m.open()
        val c = m.create("Savings", Network.TESTNET4, "pw", caps)
        assertTrue(c.capsUnsupported)
        assertFalse(c.capsNotSaved)
        assertTrue(m.capsPending.value.isEmpty())
        assertTrue(WalletFlags(flagsFile).allPendingCaps()!!.isEmpty())
        // Receive is not gated, the check goes on, and no set is ever sent.
        assertTrue(m.receiveAddress(c.wallet.id) is ReceiveResult.Address)
        m.settleCaps(c.wallet.id)
        assertTrue(ops.calls.none { it.startsWith("caps.") })
        // Nothing chosen: nothing to say.
        assertFalse(m.create("Spending", Network.TESTNET4, "pw").capsUnsupported)
    }

    @Test
    fun anImportAnswerWithoutCapsKeepsNothingPendingAndSendsNoSet() = runBlocking {
        ops.noCaps = true
        val m = manager()
        m.open()
        val r = m.import("Old", Network.TESTNET4, "pw", "a b c".toCharArray(), null, caps)
        assertTrue(r.capsUnsupported)
        assertNull(r.capsFailure)
        assertTrue(m.capsPending.value.isEmpty())
        assertTrue(ops.calls.none { it.startsWith("caps.") })
        assertTrue(m.receiveAddress(r.wallet.id) is ReceiveResult.Address)
    }

    @Test
    fun withCapsInTheAnswerTheLimitsAreKeptAndSetAsBefore() = runBlocking {
        val m = manager()
        m.open()
        val c = m.create("Savings", Network.TESTNET4, "pw", caps)
        assertFalse(c.capsUnsupported)
        assertEquals(setOf(c.wallet.id), m.capsPending.value)
    }
}
