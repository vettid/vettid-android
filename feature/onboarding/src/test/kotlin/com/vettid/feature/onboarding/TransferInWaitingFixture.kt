package com.vettid.feature.onboarding

import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.data.vault.TransferInWaiting
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Rule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The screen and fake of the tests of a transfer to this phone that waits ([TransferInWhileWaitingTest]). */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class TransferInWaitingFixture {
    @get:Rule
    val main = MainDispatcherRule()

    protected val vault = FakeVault(AppPhase.Setup(SetupStage.TRANSFER_WAITING))
    protected val start: Instant = Instant.parse("2026-11-24T12:00:00Z")

    protected fun code(relay: String) = String(
        InviteQr(
            InviteKind.APP, relay, "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 }, ByteArray(32) { 2 },
            start.plusSeconds(3_600).epochSecond,
        ).marshal(),
    )

    protected val other = code("https://relay-other.vettid.test")

    /** A transfer waiting since [agoSeconds] before the test's start, and a screen on the test's clock. */
    protected fun TestScope.waitingFor(agoSeconds: Long): TransferInViewModel {
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(agoSeconds))
        val vm = TransferInViewModel(vault, vault, vault)
        vm.clock = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this
        }
        return vm
    }

    /** Pasted at the first step, before the screen has read the transfer back (as after a restart). */
    protected fun TestScope.pasteBeforeTheReadBack(vm: TransferInViewModel, text: String) {
        vm.paste()
        vm.setInput(text)
        vm.submitInput()
        advanceTimeBy(100)
    }

    /** A screen whose first read-back failed, on the paste step, ready for a code. */
    protected fun TestScope.readBackFailedThenPasted(text: String): TransferInViewModel {
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
        val vm = waitingFor(120)
        advanceTimeBy(100)
        vault.waitingFailure = null
        vm.paste()
        vm.setInput(text)
        return vm
    }
    /** An expired code of another transfer, from the test's own relay (any code but the waiting one's). */
    protected val expiredOther = String(
        InviteQr(
            InviteKind.APP, "https://relay-other.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 },
            ByteArray(32) { 2 }, start.minusSeconds(3_600).epochSecond,
        ).marshal(),
    )
}
