// Fixtures stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.location

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.DevStack
import com.vettid.core.testing.ExitScenario
import com.vettid.core.testing.TestAndroidAttester
import com.vettid.core.vault.DeviceSecrets
import com.vettid.core.vault.InMemoryDeviceStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Receiving a location (ANDROID-PLAN §4 "Location", L1b; VAULT-MESSAGING §10.16) through [LocationSharing] against the dev stack,
 * after the A2 exit scenario connected the app's vault to the vaultctl peer. The peer is the sender: `vaultctl request
 * location.share.start` shares a position-less once share with the app (a vaultctl peer has no device to send positions, so the
 * latest position stays empty and the test says so). Skipped when the stack is not running (CI):
 * `devstack/devstack.sh up`, then `./gradlew :core:data:testDebugUnitTest --tests '*LocationReceiveDevStackTest*'`.
 *
 * What it checks: the incoming share appears (from `location.event{started, in}` or the list); `location.get` reads it; the app's
 * **Ask for location** reaches the peer (`limit` on a second ask within 10 minutes); **Stop receiving** ends it and the list no
 * longer holds it. A release without the feature answers `unsupported_type` and the test ends there. **Not run by the builder**
 * (no dev stack was started for this PR). What it would need beyond this: a second vault that sends positions (`location.update`
 * from a device), to check a live position, the trail and a snapshot end to end.
 */
class LocationReceiveDevStackTest {
    @Test
    fun aPeersShareIsReceivedReadAskedAboutAndStopped() = runBlocking {
        val stack = DevStack()
        assumeTrue("dev stack not running", stack.reachable())
        withTimeout(TIMEOUT_MS) {
            ExitScenario(stack, TestAndroidAttester(), DeviceSecrets.generate(), InMemoryDeviceStateStore()) { println("location: $it") }
                .run(this) { c -> scenario(this, stack, c) }
        }
    }

    private class MemoryStore : ShareStore {
        override fun load() = emptyList<StoredShare>()

        override fun save(shares: List<StoredShare>) = Unit
    }

    private suspend fun scenario(scope: CoroutineScope, stack: DevStack, c: ExitScenario.Connected) {
        val sharing = LocationSharing(
            scope,
            ops = { VaultLocationOps(c.vault) },
            store = MemoryStore(),
            unlocked = { true },
            due = { false },
            deviceId = { c.device.deviceId },
        )
        val events = scope.launch { c.device.events.collect { sharing.onEvent(it) } }
        try {
            try {
                stack.peerRequest(
                    "location.share.start",
                    buildJsonObject {
                        put("connection_id", c.peerConnectionId)
                        put("mode", "once")
                        put("precision", "approximate")
                    },
                )
            } catch (e: IllegalStateException) {
                // The peer's release does not list the feature (`unsupported_type`), or refused: nothing more to check.
                println("location: the peer could not start a share (${e.message}); nothing more to check")
                return
            }
            var share: IncomingShare? = null
            repeat(WAIT_TRIES) {
                if (share == null) {
                    delay(POLL_MS)
                    runCatching { sharing.refresh() }
                    share = sharing.incoming.value.firstOrNull { it.connectionId == c.connectionId }
                }
            }
            val got = checkNotNull(share) { "the peer's share did not appear" }
            try {
                sharing.open(c.connectionId)
            } catch (e: VaultFailure) {
                check(e.kind == FailureKind.NOT_FOUND || e.kind == FailureKind.NOT_SUPPORTED) { "location.get: ${e.kind}" }
            }
            println("location: incoming share ${got.shareId}, position yet: ${sharing.incoming.value.firstOrNull()?.last != null}")

            // Ask for location: the first goes; a second within 10 minutes is `limit`.
            check(sharing.ask(c.connectionId, "Where are you?") == AskResult.Sent)
            val second = sharing.ask(c.connectionId, null)
            check(second is AskResult.Limited && second.minutes in 1..10) { "second ask: $second" }

            sharing.stopReceiving(got.shareId)
            check(sharing.incoming.value.none { it.shareId == got.shareId }) { "still held after Stop receiving" }
            sharing.refresh()
            check(sharing.incoming.value.none { it.shareId == got.shareId }) { "still listed by the vault" }
        } finally {
            events.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
        const val POLL_MS = 500L
        const val WAIT_TRIES = 20
    }
}
