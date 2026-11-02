// Fixtures stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.items

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

/**
 * AA4 (ANDROID-PLAN 0.1.116 §4 "What an agent may use: share rules") through the app's own repositories against the dev stack,
 * after the A2 exit scenario connected the app's vault to the vaultctl peer. Skipped when the stack is not running (CI):
 * `devstack/devstack.sh up`, then `./gradlew :core:data:testDebugUnitTest --tests '*AgentRulesDevStackTest*'`.
 *
 * What runs on any stack: the password path (`credential.unlock` for every save, `credential.lock` after it, whether the
 * save worked or was refused), the vault's refusal of a rule for an agent that is not paired (`not_found`), a dry run that
 * needs no unlock window, and what the vault lists as `item_ways`. With an agent paired to the app's vault (`device.list`
 * answers a device of kind `agent`; the pairing itself is the AA2 flow with a `vaultctl` agent) the full flow also runs: a
 * rule, its listing, a replacement and its deletion.
 */
class AgentRulesDevStackTest {
    @Test
    fun anAgentRuleIsSavedWithThePasswordAndTheWindowIsLockedAgain() = runBlocking {
        val stack = DevStack()
        assumeTrue("dev stack not running", stack.reachable())
        withTimeout(TIMEOUT_MS) {
            ExitScenario(stack, TestAndroidAttester(), DeviceSecrets.generate(), InMemoryDeviceStateStore()) { println("aa4: $it") }
                .run(this) { c -> scenario(c) }
        }
    }

    private suspend fun CoroutineScope.scenario(c: ExitScenario.Connected) {
        val log: (String) -> Unit = { println("aa4: $it") }
        val pw = ExitScenario.PASSWORD
        var windowUntil: Instant? = null
        val events = mutableListOf<String>()
        val sharing = SharingManager(
            ops = { VaultSharingOps(c.vault) },
            hasItemWays = { false },
            windowUntil = { windowUntil },
            openWindow = {
                events += "unlock"
                windowUntil = Instant.parse(c.vault.credentialUnlock(it))
            },
            closeWindow = {
                events += "lock"
                c.vault.credentialLock()
                windowUntil = null
            },
        )
        val draft = RuleDraft(
            "", tags = listOf("keys"), agentId = AGENT_NOT_PAIRED, perHour = 60, perDay = 1_000, statusTtl = 900,
            expiresAt = Instant.now().plusSeconds(30 * DAY),
        )

        // A dry run for an agent subject signs nothing: no unlock window (0.83.0), and an unknown agent is `not_found`.
        try {
            sharing.preview(draft)
            error("a dry run for an agent that is not paired succeeded")
        } catch (e: VaultFailure) {
            check(e.kind == FailureKind.NOT_FOUND) { "dry run: ${e.kind}" }
        }
        check(events.isEmpty()) { "a dry run touched the window: $events" }

        // A save: the password, the refusal, and the lock still sent.
        try {
            sharing.saveAgentRule(draft, pw)
            error("a rule for an agent that is not paired was saved")
        } catch (e: VaultFailure) {
            check(e.kind == FailureKind.NOT_FOUND) { "save: ${e.kind}" }
        }
        check(events == listOf("unlock", "lock")) { "window events $events" }
        check(windowUntil == null)
        log("a rule for an unknown agent was refused (not_found); the window the save opened was locked again")

        // An agent, if one is paired to this vault: the whole flow.
        val agent = deviceListAgents(c)
        if (agent == null) {
            log("no agent is paired to this vault: the full rule flow was not run (pair a vaultctl agent first, AA2)")
            wrongPassword(sharing, draft, pw, events)
            return
        }
        events.clear()
        val own = draft.copy(agentId = agent)
        val saved = sharing.saveAgentRule(own, pw)
        check(saved.agentId == agent && saved.perHour == 60 && saved.perDay == 1_000) { "saved $saved" }
        check(events == listOf("unlock", "lock")) { "window events $events" }
        check(sharing.agentRules(agent).any { it.ruleId == saved.ruleId })
        val replaced = sharing.saveAgentRule(own.copy(ruleId = saved.ruleId, version = saved.version, perHour = 30), pw)
        check(replaced.perHour == 30) { "replaced $replaced" }
        sharing.deleteRule(saved.ruleId)
        check(sharing.agentRules(agent).none { it.ruleId == saved.ruleId })
        log("rule ${saved.ruleId} saved, replaced and deleted for agent $agent; windows locked after each save")
        wrongPassword(sharing, draft, pw, events)
    }

    /** Last, because a wrong password counts in the vault's backoff. */
    private suspend fun wrongPassword(sharing: SharingManager, draft: RuleDraft, pw: String, events: MutableList<String>) {
        events.clear()
        try {
            sharing.saveAgentRule(draft, pw + "x")
            error("a wrong password saved a rule")
        } catch (e: VaultFailure) {
            check(e.kind == FailureKind.BAD_PASSWORD || e.kind == FailureKind.BACKOFF) { "wrong password: ${e.kind}" }
        }
        check(events == listOf("unlock")) { "a refused unlock was followed by $events" }
    }

    /** The id of a device of kind `agent` the app's vault lists, or null. */
    private suspend fun deviceListAgents(c: ExitScenario.Connected): String? {
        val list: JsonObject = c.vault.deviceList()
        val devices = list["devices"] as? JsonArray ?: return null
        return devices.map { it.jsonObject }.firstOrNull { it["kind"]?.jsonPrimitive?.content == "agent" }
            ?.get("device_id")?.jsonPrimitive?.content
    }

    private companion object {
        const val TIMEOUT_MS = 600_000L
        const val DAY = 86_400L
        const val AGENT_NOT_PAIRED = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
    }
}
