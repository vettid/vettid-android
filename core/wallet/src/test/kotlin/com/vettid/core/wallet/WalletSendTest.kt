// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "LargeClass", "TooManyFunctions")

package com.vettid.core.wallet

import com.vettid.core.chain.BroadcastRefusal
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.chain.PayeeCheck
import com.vettid.core.chain.SummaryDifference
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Sending (W2-1; ANDROID-PLAN §4 "Sending", steps 1 to 6): build, the vault's summary and the compare, unlock, sign, broadcast. */
class WalletSendTest {
    private val ops = FakeWalletOps()
    private val chain = FakeChain()
    private val flagsFile = object : WalletFlagsFile {
        var bytes: ByteArray? = null

        override fun load(): ByteArray? = bytes

        override fun save(bytes: ByteArray) {
            this.bytes = bytes
        }
    }
    private val now = Instant.parse("2026-10-31T20:00:00Z")
    private val offered = setOf(Network.SIGNET, Network.TESTNET4)

    /** The unlock window as the vault has it; the app opens and closes it through the manager's own hooks. */
    private var window = false

    /** What the app believes about the window; true makes it skip `credential.unlock` (and meet `credential_locked`). */
    private var believesOpen: (() -> Boolean)? = null
    private var unlockFails: VaultFailure? = null
    private var lockFails = false

    /** A foreign testnet address (BIP173's vector). */
    private val payee = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    private val receive = AddressChain("p2tr", false)

    init {
        ops.windowOpen = { window }
    }

    private fun manager() = WalletsManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { ops },
        { n -> chain.open(n) },
        WalletFlags(flagsFile),
        { it in offered },
        { now },
        windowOpen = { believesOpen?.invoke() ?: window },
        openWindow = { password ->
            ops.calls += "unlock"
            unlockFails?.let { throw it }
            if (password != "pw") throw VaultFailure(FailureKind.BAD_PASSWORD, "bad_password")
            window = true
        },
        closeWindow = {
            ops.calls += "lock"
            if (lockFails) throw VaultFailure(FailureKind.NETWORK)
            window = false
        },
    )

    private fun withWallet(): VaultWallet {
        val w = WalletFixtures.wallet(nextReceive = 1)
        ops.wallets[w.id] = w
        ops.issued[w.id to receive] = mutableListOf(WalletFixtures.address(receive, 0))
        runBlocking { WalletFlags(flagsFile).setPrivacyNoteShown(w.id) }
        return w
    }

    private fun request(amount: Long? = 40_000, rate: Double = 2.0, address: String = payee, unconfirmed: Boolean = false) =
        SendRequest(address, amount, rate, unconfirmed)

    private suspend fun opened(): Pair<WalletsManager, VaultWallet> {
        val w = withWallet()
        chain.fund(receive, 0, 100_000)
        val m = manager()
        m.open()
        return m to w
    }

    private suspend fun ready(m: WalletsManager, w: VaultWallet, r: SendRequest = request()): PreparedSend =
        (m.prepareSend(w.id, r) as PrepareResult.Ready).prepared

    // --- steps 1 to 4: the build, the vault's summary, the compare ---

    @Test
    fun aPaymentIsBuiltInspectedAndShownAsTheVaultsSummary() = runBlocking {
        val (m, w) = opened()
        val p = ready(m, w)
        // The review shows the vault's summary.
        val payees = p.vault.outputs.filter { !it.change }
        assertEquals(listOf(payee), payees.map { it.address })
        assertEquals(40_000L, p.vault.sendingSats)
        // Change goes to the receiving account's next change address (index 0 of m/86'/1'/0'/1).
        val change = p.vault.outputs.single { it.change }
        assertEquals("m/86'/1'/0'/1/0", change.path)
        assertEquals(WalletFixtures.address(AddressChain("p2tr", true), 0).address, change.address)
        assertEquals(p.vault.feeSats, p.feeSats)
        assertEquals(100_000L, p.vault.totalInSats)
        assertEquals(SpendCap(true, null, null, null), p.cap)
        assertEquals(1, ops.inspected.size)
        // Nothing was signed, unlocked or broadcast.
        assertTrue(ops.signs.isEmpty())
        assertTrue("unlock" !in ops.calls)
        assertTrue(chain.spend.broadcasts.isEmpty())
    }

    @Test
    fun everyKindOfDifferenceStopsBeforeAnythingIsSigned() = runBlocking {
        val tampers: Map<String, (com.vettid.core.chain.InspectSummary) -> com.vettid.core.chain.InspectSummary> = mapOf(
            "payee address" to { s -> s.copy(outputs = s.outputs.map { if (!it.change) it.copy(address = "tb1qthief") else it }) },
            "payee amount" to { s -> s.copy(outputs = s.outputs.map { if (!it.change) it.copy(amountSats = it.amountSats + 1) else it }, sendingSats = s.sendingSats + 1) },
            "change" to { s -> s.copy(outputs = s.outputs.map { if (it.change) it.copy(amountSats = it.amountSats - 1) else it }, changeSats = s.changeSats - 1) },
            "change address" to { s -> s.copy(outputs = s.outputs.map { if (it.change) it.copy(address = "tb1pelse") else it }) },
            "fee" to { s -> s.copy(feeSats = s.feeSats + 1) },
            "extra output" to { s -> s.copy(outputs = s.outputs + com.vettid.core.chain.SummaryOutput("tb1qextra", 330, false, null, null)) },
            "coin" to { s -> s.copy(inputs = s.inputs.map { it.copy(amountSats = it.amountSats + 1) }) },
        )
        val (m, w) = opened()
        for ((name, tamper) in tampers) {
            ops.tamper = tamper
            val r = m.prepareSend(w.id, request())
            assertTrue("$name: $r", r is PrepareResult.Different)
            assertEquals(SendCodes.SUMMARY_MISMATCH, (r as PrepareResult.Different).code)
            assertTrue("$name: kinds", r.kinds.isNotEmpty())
            assertTrue("$name: nothing signed", ops.signs.isEmpty())
            assertTrue("$name: nothing unlocked", "unlock" !in ops.calls)
            assertTrue("$name: nothing broadcast", chain.spend.broadcasts.isEmpty())
            ops.inspected.clear()
        }
    }

    @Test
    fun aDifferentPayeeIsNamedByKind() = runBlocking {
        val (m, w) = opened()
        ops.tamper = { s -> s.copy(outputs = s.outputs.map { if (!it.change) it.copy(address = "tb1qthief") else it }) }
        val r = m.prepareSend(w.id, request()) as PrepareResult.Different
        assertTrue(SummaryDifference.PAYEE_ADDRESS in r.kinds)
    }

    @Test
    fun anInvalidPsbtFromTheVaultSignsNothingAndKeepsItsReason() = runBlocking {
        val (m, w) = opened()
        ops.inspectReason = "fee_rate"
        val r = m.prepareSend(w.id, request()) as PrepareResult.Refused
        assertEquals("fee_rate", r.reason)
        assertTrue(r.byVault)
        assertFalse(r.alarm)
        assertEquals(SendCodes.INVALID_PSBT, r.code)
        ops.inspectReason = SendCodes.WRONG_SEED
        assertTrue((m.prepareSend(w.id, request()) as PrepareResult.Refused).alarm)
        assertTrue(ops.signs.isEmpty())
    }

    @Test
    fun theVaultsSpendingLimitCheckIsCarriedForTheReview() = runBlocking {
        val (m, w) = opened()
        val at = Instant.parse("2026-11-01T10:00:00Z")
        ops.cap = SpendCap(false, "per_period", 5_000, at)
        assertEquals(SpendCap(false, "per_period", 5_000, at), ready(m, w).cap)
        // A vault without limits gives none, and the screen says nothing about limits.
        ops.cap = null
        assertNull(ready(m, w).cap)
    }

    @Test
    fun theAddressFeeRateAndAmountAreCheckedBeforeAnythingIsAsked() = runBlocking {
        val (m, w) = opened()
        val mainnet = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val bad = m.prepareSend(w.id, request(address = mainnet)) as PrepareResult.BadAddress
        assertTrue(bad.check is PayeeCheck.WrongNetwork)
        assertTrue((m.prepareSend(w.id, request(address = "nonsense")) as PrepareResult.BadAddress).check is PayeeCheck.Invalid)
        assertEquals(PrepareResult.BadFeeRate, m.prepareSend(w.id, request(rate = 0.5)))
        assertEquals(PrepareResult.BadFeeRate, m.prepareSend(w.id, request(rate = 1000.5)))
        assertEquals(PrepareResult.BadAmount, m.prepareSend(w.id, request(amount = 100)))
        assertTrue("nothing asked of the vault", ops.inspected.isEmpty())
    }

    @Test
    fun onlyConfirmedCoinsAreSpentUnlessAsked() = runBlocking {
        val w = withWallet()
        chain.fund(receive, 0, 30_000)
        chain.fund(AddressChain("p2wpkh", false), 3, 200_000, confirmed = false)
        val m = manager()
        m.open()
        val short = m.prepareSend(w.id, request(amount = 100_000)) as PrepareResult.NotEnough
        assertEquals(30_000L, short.availableSats)
        val p = (m.prepareSend(w.id, request(amount = 100_000, unconfirmed = true)) as PrepareResult.Ready).prepared
        assertEquals(1, p.vault.inputs.size)
        assertEquals("p2wpkh", p.vault.inputs[0].type)
    }

    @Test
    fun coinsOfBothAccountsAreSpentTogetherAndSendAllEmptiesThem() = runBlocking {
        val w = withWallet()
        chain.fund(receive, 0, 60_000)
        chain.fund(AddressChain("p2wpkh", false), 2, 50_000)
        chain.fund(AddressChain("p2wpkh", true), 1, 10_000)
        val m = manager()
        m.open()
        val p = (m.prepareSend(w.id, request(amount = null)) as PrepareResult.Ready).prepared
        assertTrue(p.sendAll)
        assertEquals(3, p.vault.inputs.size)
        assertEquals(setOf("p2tr", "p2wpkh"), p.vault.inputs.map { it.type }.toSet())
        assertEquals(0L, p.changeSats)
        assertEquals(120_000L, p.payeeSats + p.feeSats)
        assertTrue(p.vault.outputs.none { it.change })
    }

    @Test
    fun aRemainderTooSmallForAnOutputGoesToTheFeeAndTheReviewSaysSo() = runBlocking {
        val w = withWallet()
        chain.fund(receive, 0, 40_400)
        val m = manager()
        m.open()
        val p = (m.prepareSend(w.id, request(amount = 40_000, rate = 1.0)) as PrepareResult.Ready).prepared
        assertEquals(0L, p.changeSats)
        assertEquals(400L, p.feeSats)
        // A taproot input and one output are 99 vB (99 sats); the 301 beyond that are the remainder.
        assertEquals(301L, p.toFeeSats)
    }

    @Test
    fun theChainServerFailingOrMissingIsSaidAndNothingIsSigned() = runBlocking {
        val (m, w) = opened()
        chain.open = ChainOpen.NoServer(ChainServer.NotSet)
        assertTrue(m.prepareSend(w.id, request()) is PrepareResult.NoChain)
        chain.open = null
        chain.spend.rawTxs.clear()
        assertTrue(m.prepareSend(w.id, request()) is PrepareResult.ServerFailed)
        assertTrue(ops.signs.isEmpty())
    }

    // --- W1d: the limit still to be set goes first; an unreadable flags file holds sends as it holds Receive ---

    @Test
    fun aPendingLimitIsSentFirstAndHoldsTheSendWhileItCannotBe() = runBlocking {
        val w = withWallet()
        chain.fund(receive, 0, 100_000)
        WalletFlags(flagsFile).setPendingCaps(w.id, WalletCaps(500_000, null))
        ops.capsSetFails = "unavailable"
        val m = manager()
        runCatching { m.open() }
        assertEquals(PrepareResult.Held(SendHold.CAPS_PENDING), m.prepareSend(w.id, request()))
        assertTrue("nothing inspected while the limit is pending", ops.inspected.isEmpty())
        ops.capsSetFails = null
        val p = m.prepareSend(w.id, request())
        assertTrue(p.toString(), p is PrepareResult.Ready)
        // The set went before anything else was asked for the send.
        val set = ops.calls.indexOfLast { it.startsWith("caps.set") }
        assertTrue(set in 0 until ops.calls.indexOf("inspect"))
    }

    @Test
    fun anUnreadableFlagsFileHoldsSendsAsItHoldsReceive() = runBlocking {
        val w = withWallet()
        chain.fund(receive, 0, 100_000)
        flagsFile.bytes = """{"version":2}""".encodeToByteArray()
        val m = manager()
        runCatching { m.open() }
        assertEquals(PrepareResult.Held(SendHold.SETTINGS_UNREADABLE), m.prepareSend(w.id, request()))
        assertEquals(ReceiveResult.SettingsUnreadable, m.receiveAddress(w.id))
        assertTrue(ops.inspected.isEmpty())
    }

    // --- steps 5 and 6: unlock, sign, broadcast ---

    @Test
    fun theWindowIsOpenedThenTheVaultSignsThenItIsBroadcastAndTheWindowClosed() = runBlocking {
        val (m, w) = opened()
        val p = ready(m, w)
        val r = m.approveSend(p, "pw")
        assertTrue(r.toString(), r is SendResult.Sent)
        assertEquals(p.vault.txid, (r as SendResult.Sent).txid)
        val order = ops.calls.filter { it in setOf("inspect", "unlock", "sign", "lock") }
        assertEquals(listOf("inspect", "unlock", "sign", "lock"), order)
        assertEquals(listOf("pw"), ops.signPasswords)
        // The PSBT signed is the one inspected; one broadcast of the signed transaction.
        assertEquals(ops.inspected, ops.signs)
        assertEquals(1, chain.spend.broadcasts.size)
        assertFalse(m.hasUnsent(w.id))
        assertFalse(window)
    }

    @Test
    fun anOpenWindowIsUsedAsItIsAndLeftOpen() = runBlocking {
        val (m, w) = opened()
        window = true
        assertTrue(m.approveSend(ready(m, w), "pw") is SendResult.Sent)
        assertTrue("unlock" !in ops.calls)
        assertTrue("lock" !in ops.calls)
        assertTrue(window)
    }

    @Test
    fun aWindowThatClosedMeanwhileIsOpenedAndTheSigningAskedOnce() = runBlocking {
        val (m, w) = opened()
        believesOpen = { true }
        val r = m.approveSend(ready(m, w), "pw")
        assertTrue(r.toString(), r is SendResult.Sent)
        assertEquals(listOf("sign", "unlock", "sign", "lock"), ops.calls.filter { it in setOf("unlock", "sign", "lock") })
    }

    @Test
    fun aVaultThatStillSaysLockedAfterTheUnlockIsCredentialLocked() = runBlocking {
        val (m, w) = opened()
        believesOpen = { true }
        ops.windowOpen = { false }
        assertEquals(SendResult.CredentialLocked, m.approveSend(ready(m, w), "pw"))
        assertEquals(2, ops.signs.size)
        assertTrue(chain.spend.broadcasts.isEmpty())
    }

    @Test
    fun aWrongPasswordAtTheUnlockSignsNothing() = runBlocking {
        val (m, w) = opened()
        val p = ready(m, w)
        val e = runCatching { m.approveSend(p, "wrong") }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.BAD_PASSWORD, e.kind)
        assertTrue(ops.signs.isEmpty())
        assertTrue("a window that never opened is not closed", "lock" !in ops.calls)
    }

    @Test
    fun aWindowThatMayHaveOpenedIsClosedAfterAnUnreadableAnswer() = runBlocking {
        val (m, w) = opened()
        val p = ready(m, w)
        unlockFails = VaultFailure(FailureKind.NO_RESPONSE)
        runCatching { m.approveSend(p, "pw") }
        assertTrue("lock" in ops.calls)
        assertTrue(ops.signs.isEmpty())
    }

    @Test
    fun aWindowThatCouldNotBeClosedIsSaid() = runBlocking {
        val (m, w) = opened()
        lockFails = true
        assertTrue(m.approveSend(ready(m, w), "pw") is SendResult.Sent)
        assertTrue(m.windowNotClosed.value)
    }

    // --- the same request on a lost answer ---

    @Test
    fun aLostAnswerIsResentAsTheSameRequestNeverSignedAgain() = runBlocking {
        val (m, w) = opened()
        ops.unanswered = 1
        val p = ready(m, w)
        assertEquals(SendResult.Unanswered, m.approveSend(p, "pw"))
        assertTrue(m.hasUnsent(w.id))
        assertEquals(1, ops.signs.size)
        // A new approval is refused: it would build the payment again and count again against the limit.
        assertEquals(SendResult.Unanswered, m.approveSend(p, "pw"))
        assertEquals(1, ops.signs.size)
        assertEquals(SendResult.Unanswered, m.retrySign(w.id))
        val done = m.retrySign(w.id)
        assertTrue(done.toString(), done is SendResult.Sent)
        // One sign request ever; the other two tries were resends of it.
        assertEquals(1, ops.signs.size)
        assertEquals(1 + 1, ops.resends)
        assertEquals(1, chain.spend.broadcasts.size)
        assertFalse(m.hasUnsent(w.id))
        assertEquals(SendResult.NothingToSend, m.retrySign(w.id))
    }

    @Test
    fun theVaultsCachedRefusalToTheResentRequestDecidesIt() = runBlocking {
        val (m, w) = opened()
        ops.unanswered = 0
        assertEquals(SendResult.Unanswered, m.approveSend(ready(m, w), "pw"))
        ops.resendFailure = refusal("cap_exceeded", "per_tx", """{"reason":"per_tx","limit_sats":500}""")
        val r = m.retrySign(w.id) as SendResult.CapExceeded
        assertEquals(CapReason.PER_TX, r.reason)
        assertFalse(m.hasUnsent(w.id))
        assertEquals(1, ops.signs.size)
    }

    @Test
    fun aTransportFailureOnTheResendLeavesTheRequestToTryAgain() = runBlocking {
        val (m, w) = opened()
        ops.unanswered = 0
        assertEquals(SendResult.Unanswered, m.approveSend(ready(m, w), "pw"))
        ops.resendFailure = java.io.IOException("no relay")
        val e = runCatching { m.retrySign(w.id) }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.NETWORK, e.kind)
        assertTrue(m.hasUnsent(w.id))
        ops.resendFailure = null
        assertTrue(m.retrySign(w.id) is SendResult.Sent)
    }

    @Test
    fun anUnanswerableSigningCanBeGivenUp() = runBlocking {
        val (m, w) = opened()
        ops.unanswered = 100
        assertEquals(SendResult.Unanswered, m.approveSend(ready(m, w), "pw"))
        m.discardUnsent(w.id)
        assertFalse(m.hasUnsent(w.id))
        assertEquals(SendResult.NothingToSend, m.retrySign(w.id))
    }

    // --- errors, as kinds and codes ---

    private fun refusal(code: String, message: String = "", body: String? = null) =
        VaultOpException("wallet.sign", code, message, body?.let { VaultJson.parseObject(it.encodeToByteArray()) })

    @Test
    fun capExceededPerTxCarriesItsLimit() = runBlocking {
        val (m, w) = opened()
        ops.signError = refusal("cap_exceeded", "per_tx", """{"reason":"per_tx","limit_sats":500000}""")
        val r = m.approveSend(ready(m, w), "pw") as SendResult.CapExceeded
        assertEquals(CapReason.PER_TX, r.reason)
        assertEquals(500_000L, r.limitSats)
        assertNull(r.remainingSats)
        assertNull(r.allowedAfter)
        assertEquals(SendCodes.CAP_EXCEEDED, r.code)
        // Nothing was signed, so nothing to broadcast or keep; the window the app opened is closed.
        assertTrue(chain.spend.broadcasts.isEmpty())
        assertFalse(m.hasUnsent(w.id))
        assertEquals(listOf("unlock", "sign", "lock"), ops.calls.filter { it in setOf("unlock", "sign", "lock") })
    }

    @Test
    fun capExceededPerPeriodCarriesWhatIsLeftAndWhenItFits() = runBlocking {
        val (m, w) = opened()
        ops.signError = refusal(
            "cap_exceeded", "per_period",
            """{"reason":"per_period","limit_sats":2000000,"remaining_sats":15000,"allowed_after":"2026-11-02T08:30:00.000Z"}""",
        )
        val r = m.approveSend(ready(m, w), "pw") as SendResult.CapExceeded
        assertEquals(CapReason.PER_PERIOD, r.reason)
        assertEquals(2_000_000L, r.limitSats)
        assertEquals(15_000L, r.remainingSats)
        assertEquals(Instant.parse("2026-11-02T08:30:00Z"), r.allowedAfter)
        // A fit never: no allowed_after, nothing remaining.
        ops.signError = refusal("cap_exceeded", "per_period", """{"reason":"per_period","limit_sats":10,"remaining_sats":0}""")
        val never = m.approveSend(ready(m, w), "pw") as SendResult.CapExceeded
        assertEquals(0L, never.remainingSats)
        assertNull(never.allowedAfter)
    }

    @Test
    fun invalidPsbtAtTheSigningKeepsItsReasonAndWrongSeedIsAnAlarm() = runBlocking {
        val (m, w) = opened()
        ops.signError = refusal("invalid_psbt", "derivation_key")
        val r = m.approveSend(ready(m, w), "pw") as SendResult.Refused
        assertEquals("derivation_key", r.reason)
        assertFalse(r.alarm)
        assertEquals(SendCodes.INVALID_PSBT, r.code)
        ops.signError = refusal("invalid_psbt", "wrong_seed")
        val alarm = m.approveSend(ready(m, w), "pw") as SendResult.Refused
        assertTrue(alarm.alarm)
        assertEquals("wrong_seed", alarm.reason)
        assertTrue(chain.spend.broadcasts.isEmpty())
    }

    @Test
    fun duplicateWalletAndOtherErrorsAreMapped() = runBlocking {
        val (m, w) = opened()
        ops.signError = refusal("duplicate_wallet")
        assertEquals(SendResult.DuplicateWallet, m.approveSend(ready(m, w, request(amount = 40_001)), "pw"))
        ops.signError = refusal("backoff")
        val e = runCatching { m.approveSend(ready(m, w, request(amount = 40_002)), "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.BACKOFF, e.kind)
        ops.signError = refusal("credential_locked")
        // The vault still says locked after the app opened the window: asked again, then the vault's word.
        window = true
        ops.windowOpen = { false }
        assertEquals(SendResult.CredentialLocked, m.approveSend(ready(m, w, request(amount = 40_003)), "pw"))
    }

    @Test
    fun aSignedTransactionThatIsNotTheOneReviewedIsNeverBroadcast() = runBlocking {
        val (m, w) = opened()
        ops.signedDiffers = true
        assertEquals(SendResult.SignedDiffers, m.approveSend(ready(m, w), "pw"))
        assertTrue(chain.spend.broadcasts.isEmpty())
        assertFalse(m.hasUnsent(w.id))
    }

    // --- the signed transaction: memory only, kept for Try again, dropped at lock ---

    @Test
    fun aFailedBroadcastKeepsTheTransactionAndTryAgainResendsTheSameBytesAfterTheServerChanged() = runBlocking {
        val (m, w) = opened()
        chain.spend.broadcastError = ChainError.BroadcastRefused(BroadcastRefusal.FEE_TOO_LOW, "min relay fee not met")
        val failed = m.approveSend(ready(m, w), "pw") as SendResult.BroadcastFailed
        assertEquals(SendCodes.BROADCAST_FAILED, failed.code)
        assertEquals(BroadcastRefusal.FEE_TOO_LOW, (failed.cause as BroadcastCause.Refused).refusal)
        assertTrue(m.hasUnsent(w.id))
        assertEquals(setOf(w.id), m.unsent.value)
        val first = chain.spend.broadcasts.single()
        // Another server is chosen; Try again sends the very same bytes there, and signs nothing again.
        chain.spend = FakeSpend()
        val again = m.retryBroadcast(w.id)
        assertTrue(again.toString(), again is SendResult.Sent)
        assertArrayEquals(first, chain.spend.broadcasts.single())
        assertEquals(1, ops.signs.size)
        assertFalse(m.hasUnsent(w.id))
        assertTrue(m.unsent.value.isEmpty())
    }

    @Test
    fun aServerThatDoesNotAnswerIsAFailureWithTheTransactionKept() = runBlocking {
        val (m, w) = opened()
        chain.spend.broadcastError = ChainError.Unreachable()
        val failed = m.approveSend(ready(m, w), "pw") as SendResult.BroadcastFailed
        assertTrue(failed.cause is BroadcastCause.Server)
        chain.open = ChainOpen.NoServer(ChainServer.NotSet)
        val noServer = m.retryBroadcast(w.id) as SendResult.BroadcastFailed
        assertTrue(noServer.cause is BroadcastCause.NoChain)
        assertTrue(m.hasUnsent(w.id))
    }

    @Test
    fun aServerThatAlreadyHasTheTransactionIsSuccess() = runBlocking {
        val (m, w) = opened()
        chain.spend.broadcastError = ChainError.BroadcastRefused(BroadcastRefusal.ALREADY_KNOWN, "txn-already-in-mempool")
        val r = m.approveSend(ready(m, w), "pw") as SendResult.Sent
        assertTrue(r.alreadyKnown)
        assertFalse(m.hasUnsent(w.id))
    }

    @Test
    fun theSignedTransactionIsDroppedAtLockAndNeverWrittenToDisk() = runBlocking {
        val (m, w) = opened()
        chain.spend.broadcastError = ChainError.Timeout()
        val failed = m.approveSend(ready(m, w), "pw")
        assertTrue(failed is SendResult.BroadcastFailed)
        val tx = chain.spend.broadcasts.single()
        assertTrue(m.hasUnsent(w.id))
        // Never on disk: neither the bytes nor their hex are in the settings file.
        val file = flagsFile.bytes
        if (file != null) {
            assertFalse(indexOf(file, tx) >= 0)
            assertFalse(String(file).contains(com.vettid.core.chain.Tx.toHex(tx)))
        }
        m.clear()
        assertFalse(m.hasUnsent(w.id))
        assertTrue(m.unsent.value.isEmpty())
        assertEquals(SendResult.NothingToSend, m.retryBroadcast(w.id))
        assertEquals(1, chain.spend.broadcasts.size)
        // The same for a signing still unanswered.
        val m2 = manager()
        m2.open()
        ops.unanswered = 5
        assertEquals(SendResult.Unanswered, m2.approveSend(ready(m2, w), "pw"))
        m2.clear()
        assertFalse(m2.hasUnsent(w.id))
        assertEquals(SendResult.NothingToSend, m2.retrySign(w.id))
    }

    @Test
    fun aSendPreparedBeforeALockIsNotSigned() = runBlocking {
        val (m, w) = opened()
        val p = ready(m, w)
        m.clear()
        assertEquals(SendResult.Cleared, m.approveSend(p, "pw"))
        assertTrue(ops.signs.isEmpty())
        assertTrue("unlock" !in ops.calls)
    }

    // --- B1: a reviewed payment is signed once ---

    @Test
    fun aPaymentIsNeverSignedASecondTimeAfterSentFailedDiscardedOrAnUnreadableAnswer() = runBlocking {
        // After Sent.
        val (m, w) = opened()
        val p = ready(m, w)
        assertTrue(m.approveSend(p, "pw") is SendResult.Sent)
        assertEquals(SendResult.AlreadySigned, m.approveSend(p, "pw"))
        assertEquals(1, ops.signs.size)
        // After a failed broadcast, and after it is given up.
        chain.spend.broadcastError = ChainError.Timeout()
        val q = ready(m, w, request(amount = 41_000))
        assertTrue(m.approveSend(q, "pw") is SendResult.BroadcastFailed)
        assertEquals(SendResult.AlreadySigned, m.approveSend(q, "pw"))
        m.discardUnsent(w.id)
        assertEquals(SendResult.AlreadySigned, m.approveSend(q, "pw"))
        assertEquals(2, ops.signs.size)
        // After an answer that does not read: the vault may have signed.
        chain.spend.broadcastError = null
        ops.signUnreadable = true
        val r = ready(m, w, request(amount = 42_000))
        assertTrue(runCatching { m.approveSend(r, "pw") }.exceptionOrNull() is WalletWireException)
        ops.signUnreadable = false
        assertEquals(SendResult.AlreadySigned, m.approveSend(r, "pw"))
        assertEquals(3, ops.signs.size)
    }

    @Test
    fun aKeptTransactionRefusesAnyApproveForTheWallet() = runBlocking {
        val (m, w) = opened()
        chain.spend.broadcastError = ChainError.Timeout()
        assertTrue(m.approveSend(ready(m, w), "pw") is SendResult.BroadcastFailed)
        assertEquals(SendResult.AlreadySigned, m.approveSend(ready(m, w, request(amount = 45_000)), "pw"))
        assertEquals(1, ops.signs.size)
    }

    @Test
    fun aDefiniteRefusalThatCountedNothingLetsTheSameApproveBeTriedAgain() = runBlocking {
        val refusals = listOf(
            refusal("cap_exceeded", "per_tx", """{"reason":"per_tx","limit_sats":5}"""), refusal("invalid_psbt", "fee"),
            refusal("credential_locked"), refusal("bad_password"), refusal("backoff"), refusal("stale_credential"),
        )
        val (m, w) = opened()
        val p = ready(m, w)
        for (e in refusals) {
            ops.signError = e
            window = false
            val sent = ops.signs.size
            val r = runCatching { m.approveSend(p, "pw") }.getOrNull()
            // The previous refusal gave the payment back: this approve reached the vault.
            assertTrue("${e.code}: reached the vault", r != SendResult.AlreadySigned && ops.signs.size > sent)
        }
        ops.signError = null
        window = false
        assertTrue(m.approveSend(p, "pw") is SendResult.Sent)
        assertEquals(SendResult.AlreadySigned, m.approveSend(p, "pw"))
    }

    @Test
    fun aRefusalThatMayHaveCountedKeepsTheApproveClosed() = runBlocking {
        val (m, w) = opened()
        val p = ready(m, w)
        ops.signError = refusal("duplicate_wallet")
        assertEquals(SendResult.DuplicateWallet, m.approveSend(p, "pw"))
        ops.signError = null
        assertEquals(SendResult.AlreadySigned, m.approveSend(p, "pw"))
    }

    // --- 1: a signing that may still apply holds new sends ---

    @Test
    fun anOutstandingSigningHoldsPrepareAndApproveUntilTheVaultDecidesIt() = runBlocking {
        val (m, w) = opened()
        val p = ready(m, w)
        ops.outstanding = setOf(w.id)
        assertEquals(PrepareResult.Held(SendHold.SIGN_UNSETTLED), m.prepareSend(w.id, request()))
        assertEquals(SendResult.Held(SendHold.SIGN_UNSETTLED), m.approveSend(p, "pw"))
        assertTrue(ops.signs.isEmpty())
        ops.outstanding = emptySet()
        assertTrue(m.approveSend(p, "pw") is SendResult.Sent)
    }

    // --- 3, 4, 7 ---

    @Test
    fun nothingIsBroadcastAfterALockThatCameInDuringTheBroadcast() = runBlocking {
        val (m, w) = opened()
        val p = ready(m, w)
        chain.onOpen = { m.clear() }
        val r = m.approveSend(p, "pw")
        assertEquals(SendResult.Cleared, r)
        assertTrue(chain.spend.broadcasts.isEmpty())
        assertFalse(m.hasUnsent(w.id))
    }

    @Test
    fun aLockDropsTheKeptSigningRequest() = runBlocking {
        val (m, w) = opened()
        ops.unanswered = 5
        assertEquals(SendResult.Unanswered, m.approveSend(ready(m, w), "pw"))
        assertEquals(0, ops.forgets)
        m.clear()
        assertEquals(1, ops.forgets)
        assertFalse(m.hasUnsent(w.id))
        // Given up by the member: dropped as well.
        val m2 = manager()
        m2.open()
        assertEquals(SendResult.Unanswered, m2.approveSend(ready(m2, w, request(amount = 41_000)), "pw"))
        m2.discardUnsent(w.id)
        assertEquals(2, ops.forgets)
    }

    @Test
    fun theInspectCapCarriesTheLimitThatStoppedIt() = runBlocking {
        val (m, w) = opened()
        val summary = ready(m, w).vault.toJson()
        fun cap(body: String) = WalletWire.inspection(
            VaultJson.parseObject((summary.dropLast(1) + ""","cap":""" + body + "}").encodeToByteArray()),
        ).cap!!
        assertEquals(500L, cap("""{"ok":false,"reason":"per_tx","limit_sats":500}""").limitSats)
        assertEquals(700L, cap("""{"ok":false,"reason":"per_tx","limit":700}""").limitSats)
        assertNull(cap("""{"ok":true}""").limitSats)
    }

    // --- the rest ---

    @Test
    fun historyIsReadAfterThePendingLimitAndPassedOn() = runBlocking {
        val (m, w) = opened()
        ops.history = listOf(WalletTx("ab".repeat(32), now, 40_000, 59_000, 1_000, listOf(WalletPayee(payee, 40_000)), null, null))
        val h = m.history(w.id, 20)
        assertEquals(1, h.size)
        assertEquals("history:20", ops.calls.last())
    }

    @Test
    fun feeEstimatesComeFromTheChainServerAndMapToSlowNormalFast() = runBlocking {
        val (m, w) = opened()
        val est = m.feeEstimates(w.id) as FeeEstimates.Done
        assertEquals(1.0, FeeTargets.rate(est.byTarget, FeeTargets.SLOW)!!, 0.0)
        assertEquals(5.0, FeeTargets.rate(est.byTarget, FeeTargets.NORMAL)!!, 0.0)
        assertEquals(12.0, FeeTargets.rate(est.byTarget, FeeTargets.FAST)!!, 0.0)
        // No entry for a target: the nearest quicker one; the rate stays within 1 and 1,000.
        assertEquals(12.0, FeeTargets.rate(mapOf(2 to 12.0, 1008 to 0.2), 6)!!, 0.0)
        assertEquals(1.0, FeeTargets.rate(mapOf(2 to 0.2), 2)!!, 0.0)
        assertNull(FeeTargets.rate(emptyMap(), 6))
        assertTrue(FeeTargets.muchHigher(25.0, 12.0))
        assertFalse(FeeTargets.muchHigher(24.0, 12.0))
        chain.open = ChainOpen.NoServer(ChainServer.NotSet)
        assertTrue(m.feeEstimates(w.id) is FeeEstimates.NoChain)
        assertNotNull(m)
    }

    @Test
    fun theSendTypesPrintTheirNamesOnly() = runBlocking {
        val (m, w) = opened()
        val p = ready(m, w)
        val values = listOf<Any>(p, SendRequest(payee, 40_000, 2.0), PrepareResult.Ready(p), SpendCap(false, "per_tx", 1, null), SendResult.Sent(p.vault.txid))
        for (v in values) {
            for (s in listOf(payee, p.vault.txid, "40000", "tb1p")) assertFalse("'$s' in $v", "$v".contains(s))
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
