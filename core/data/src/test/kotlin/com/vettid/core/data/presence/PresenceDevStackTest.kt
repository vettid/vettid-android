// Fixtures stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.presence

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.DevStack
import com.vettid.core.testing.ExitScenario
import com.vettid.core.testing.TestAndroidAttester
import com.vettid.core.vault.DeviceSecrets
import com.vettid.core.vault.InMemoryDeviceStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Presence (ANDROID-PLAN §4 "Presence", P1; VAULT-MESSAGING §10.17) through [PresenceManager] against the dev stack, after the
 * A2 exit scenario connected the app's vault to the vaultctl peer. Skipped when the stack is not running (CI):
 * `devstack/devstack.sh up`, then `./gradlew :core:data:testDebugUnitTest --tests '*PresenceDevStackTest*'`.
 *
 * What it checks: whether the vault has presence is learnt from `presence.get` (a vault of a release without the feature
 * answers `unsupported_type`, and the test ends there); the default policy (available, all, no exceptions); a state change and an
 * exception for the peer with their versions; and that `presence.query` of the peer is answered (the result itself needs the peer's
 * vault to answer a ping, which the stack's `vaultctl` does not do on demand: the reading is "Checking…" or, at `exp`, unknown).
 * **Not run by the builder** (no dev stack was started for this PR).
 */
class PresenceDevStackTest {
    @Test
    fun thePolicyIsReadChangedAndThePeerIsAskedAgainstTheVault() = runBlocking {
        val stack = DevStack()
        assumeTrue("dev stack not running", stack.reachable())
        withTimeout(TIMEOUT_MS) {
            ExitScenario(stack, TestAndroidAttester(), DeviceSecrets.generate(), InMemoryDeviceStateStore()) { println("presence: $it") }
                .run(this) { c -> scenario(c) }
        }
    }

    private suspend fun CoroutineScope.scenario(c: ExitScenario.Connected) {
        val m = PresenceManager(this, ops = { VaultPresenceOps(c.vault) })
        try {
            m.refreshPolicy()
        } catch (e: VaultFailure) {
            check(e.kind == FailureKind.NOT_SUPPORTED) { "presence.get: ${e.kind}" }
            check(m.availability.value == PresenceAvailability.UNSUPPORTED) { "availability: ${m.availability.value}" }
            println("presence: this stack's release does not list the feature (unsupported_type); nothing more to check")
            return
        }
        check(m.availability.value == PresenceAvailability.AVAILABLE)
        val first = checkNotNull(m.policy.value) { "no policy" }
        check(first.state == PresenceState.AVAILABLE && first.share == PresenceShare.ALL && first.except.isEmpty()) { "default: $first" }

        m.update { it.copy(state = PresenceState.BUSY) }
        check(m.policy.value?.state == PresenceState.BUSY && m.policy.value!!.version > first.version) { "state: ${m.policy.value}" }
        m.update { it.withSharedWith(c.connectionId, false) }
        m.refreshPolicy()
        check(m.policy.value?.except == listOf(c.connectionId)) { "except: ${m.policy.value}" }
        check(m.policy.value?.sharesWith(c.connectionId) == false)

        m.query(c.connectionId)
        check(m.readings.value[c.connectionId] != null) { "no reading after the query" }
        println("presence: ${m.readings.value[c.connectionId]}")

        // Back to the default.
        m.update { it.copy(state = PresenceState.AVAILABLE).withSharedWith(c.connectionId, true) }
        m.refreshPolicy()
        check(m.policy.value?.state == PresenceState.AVAILABLE && m.policy.value?.except.isNullOrEmpty()) { "restored: ${m.policy.value}" }
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
    }
}
