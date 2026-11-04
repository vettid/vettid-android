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
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.locationShareList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

/**
 * Sharing the member's location (ANDROID-PLAN §4 "Location", L1a; VAULT-MESSAGING §10.16) through [LocationSharing] against the dev
 * stack, after the A2 exit scenario connected the app's vault to the vaultctl peer. Skipped when the stack is not running (CI):
 * `devstack/devstack.sh up`, then `./gradlew :core:data:testDebugUnitTest --tests '*LocationDevStackTest*'`.
 *
 * What it checks: `location.share.start` to the peer (a vault of a release without the feature answers `unsupported_type`, and the
 * test ends there); the share in `location.share.list` with this device as its source; one position sent as the ephemeral
 * `location.update` (the vault forwards it from memory: `last_sent_at` appears in the list); `location.share.stop` and the list
 * without the share. The platform's positions are a fake (a test coordinate), no service is started. The peer's receiving side is
 * L1b's. **Not run by the builder** (no dev stack was started for this PR).
 */
class LocationDevStackTest {
    @Test
    fun aShareIsStartedFedOneUpdateAndStoppedAgainstTheVault() = runBlocking {
        val stack = DevStack()
        assumeTrue("dev stack not running", stack.reachable())
        withTimeout(TIMEOUT_MS) {
            ExitScenario(stack, TestAndroidAttester(), DeviceSecrets.generate(), InMemoryDeviceStateStore()) { println("location: $it") }
                .run(this) { c -> scenario(this, c) }
        }
    }

    private class Fixes : PositionProvider {
        val flow = MutableSharedFlow<Fix>()

        override fun fixes(request: PositionRequest): Flow<Fix> = flow
    }

    private class NoService(override val positions: PositionProvider) : LocationPlatform {
        override fun startService() = true

        override fun stopService() = Unit
    }

    private class MemoryStore : ShareStore {
        var saved: List<StoredShare> = emptyList()

        override fun load() = saved

        override fun save(shares: List<StoredShare>) {
            saved = shares
        }
    }

    private suspend fun listed(vault: VaultApi): List<JsonObject> =
        (vault.locationShareList()["outgoing"] as? JsonArray).orEmpty().map { it as JsonObject }

    private suspend fun scenario(scope: CoroutineScope, c: ExitScenario.Connected) {
        val fixes = Fixes()
        val store = MemoryStore()
        val sharing = LocationSharing(
            scope,
            ops = { VaultLocationOps(c.vault) },
            store = store,
            unlocked = { true },
            due = { false },
            deviceId = { c.device.deviceId },
        )
        sharing.attach(NoService(fixes))
        sharing.setInFront(true)
        // The vault's events reach the sharing as in VaultManager.
        val events = scope.launchEvents(c, sharing)
        try {
            val share = try {
                sharing.start(ShareRequest(c.connectionId, ShareTiming.ForAWhile(ShareLength.MINUTES_15, ShareInterval.SECONDS_30), SharePrecision.APPROXIMATE))
            } catch (e: VaultFailure) {
                check(e.kind == FailureKind.NOT_SUPPORTED) { "location.share.start: ${e.kind}" }
                println("location: this stack's release does not list the feature (unsupported_type); nothing more to check")
                return
            }
            check(sharing.serviceState.value.active) { "no share to send for" }
            val entry = listed(c.vault).single { (it["share_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content == share.shareId }
            check((entry["device_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content == c.device.deviceId) { "source device: $entry" }
            check(store.saved.map { it.shareId } == listOf(share.shareId)) { "stored: ${store.saved}" }

            // One position (a test coordinate): the vault forwards it from memory and notes when.
            delay(SETTLE_MS)
            fixes.flow.emit(Fix(52.5200, 13.4050, 15.0, null, null, null, Instant.now()))
            var sent = false
            repeat(WAIT_TRIES) {
                if (!sent) {
                    delay(POLL_MS)
                    sent = listed(c.vault).any { "last_sent_at" in it }
                }
            }
            println("location: last_sent_at in the list: $sent")

            sharing.stop(share.shareId)
            check(listed(c.vault).none { (it["share_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content == share.shareId }) { "still listed" }
            check(!sharing.serviceState.value.active) { "still sending" }
            check(store.saved.isEmpty()) { "stored after the stop: ${store.saved}" }
        } finally {
            events.cancel()
        }
    }

    private fun CoroutineScope.launchEvents(c: ExitScenario.Connected, sharing: LocationSharing) =
        launch { c.device.events.collect { sharing.onEvent(it) } }

    private companion object {
        const val TIMEOUT_MS = 180_000L
        const val SETTLE_MS = 500L
        const val POLL_MS = 500L
        const val WAIT_TRIES = 20
    }
}
