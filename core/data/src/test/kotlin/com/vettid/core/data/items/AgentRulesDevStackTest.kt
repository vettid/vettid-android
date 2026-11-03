// Fixtures stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.items

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
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
 * `hasItemWays` is fixed to false here, not read from the stack's capabilities, so every rule in this test is an ask rule.
 *
 * **Status: the branch with a paired agent (a dry run with matches, a save with the window already open, save, list, replace,
 * delete) has not been run yet**, because no paired agent was available. Only the unpaired part has been run against a stack.
 *
 * What runs on any stack: the password path (`credential.unlock` for every save, `credential.lock` after it, whether the
 * save worked or was refused), the vault's refusal of a rule for an agent that is not paired (`not_found`), and a wrong
 * password. With an agent paired to the app's vault (`device.list` answers a device of kind `agent`; the pairing itself is the
 * AA2 flow with a `vaultctl` agent) the full flow also runs, and the vault must list a rule saved as ask back as ask.
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
            // As VaultManager does: the vault's refusals arrive as VaultFailure, not as the client's VaultOpException.
            openWindow = {
                events += "unlock"
                windowUntil = Instant.parse(vaultGuard { c.vault.credentialUnlock(it) })
            },
            closeWindow = {
                events += "lock"
                vaultGuard { c.vault.credentialLock() }
                windowUntil = null
            },
        )
        val draft = RuleDraft(
            "", tags = listOf("keys"), agentId = AGENT_NOT_PAIRED, perHour = 60, perDay = 1_000, statusTtl = 900,
            expiresAt = Instant.now().plusSeconds(30 * DAY),
        )

        // An unknown agent is `not_found`, dry run or not. This proves only that the app's dry run does not touch the window; that the
        // vault answers a dry run without the window is shown below, with a paired agent.
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

        // A dry run for a paired agent needs no unlock window (0.83.0): matches come back, never `credential_locked`.
        val preview = sharing.preview(own)
        check(preview.total >= 0) { "dry run $preview" }
        check(events.isEmpty() && windowUntil == null) { "the dry run touched the window: $events" }
        log("dry run for agent $agent: ${preview.total} matches, no window")

        // A save while the window is already open: the password is asked all the same, and the window is left open.
        windowUntil = Instant.parse(c.vault.credentialUnlock(pw))
        val openSave = sharing.saveAgentRule(own, pw)
        check(events == listOf("unlock")) { "window events with the window open: $events" }
        check(windowUntil != null) { "a window that was open was locked" }
        c.vault.credentialLock()
        windowUntil = null
        sharing.deleteRule(openSave.ruleId)
        events.clear()
        val saved = sharing.saveAgentRule(own, pw)
        check(saved.agentId == agent && saved.perHour == 60 && saved.perDay == 1_000) { "saved $saved" }
        check(events == listOf("unlock", "lock")) { "window events $events" }
        val listed = sharing.agentRules(agent).firstOrNull { it.ruleId == saved.ruleId }
        check(listed != null && listed.mode == ShareMode.ASK) { "a rule saved as ask is listed as $listed" }
        check(saved.mode == ShareMode.ASK) { "saved as ${saved.mode}" }
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
