// Fixtures stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.devices

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.testing.DevStack
import com.vettid.core.testing.ExitScenario
import com.vettid.core.testing.TestAndroidAttester
import com.vettid.core.vault.DeviceSecrets
import com.vettid.core.vault.InMemoryDeviceStateStore
import com.vettid.core.vault.LeashGrantRequest
import com.vettid.core.vault.leashGrantIssue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * AA3 (ANDROID-PLAN 0.1.116 §4 "An agent's permissions") through the app's own repository and answer mapping against the dev
 * stack, after the A2 exit scenario connected the app's vault to the vaultctl peer. Skipped when the stack is not running (CI):
 * `devstack/devstack.sh up`, then `./gradlew :core:data:testDebugUnitTest --tests '*AgentPermissionsDevStackTest*'`.
 *
 * **Status: the branch with a paired agent has not been run**, because no paired agent was available. What runs on any stack
 * (the unpaired part): the vault's `not_found` for an agent that is not paired, to `leash.grant.list`, `leash.agent.resume`,
 * `leash.grant.revoke` (a grant id nobody has: [PermissionRemoval.ALREADY_GONE]) and `leash.grant.issue` with the unlock
 * window open (mapped to [GrantIssueResult.NotPaired]), and `credential_locked` for an issue outside the window.
 *
 * With an agent paired to the app's vault (`device.list` answers a device of kind `agent`; the pairing is the AA2 flow with a
 * `vaultctl` agent) the rest runs: a `message.list` grant issued and listed under Other permissions, removed
 * ([PermissionRemoval.REMOVED]), removed again ([PermissionRemoval.ALREADY_GONE]), and `leash.agent.resume` of an agent that
 * is not paused ([AgentResumption.NOT_PAUSED]). **Never run, even with a paired agent:** the suspension itself (30 refusals
 * within an hour, which needs an agent that sends them), so the banner and a `RESUMED` answer have only unit tests.
 */
class AgentPermissionsDevStackTest {
    @Test
    fun anAgentsPermissionsAreListedRemovedAndResumedAgainstTheVault() = runBlocking {
        val stack = DevStack()
        assumeTrue("dev stack not running", stack.reachable())
        withTimeout(TIMEOUT_MS) {
            ExitScenario(stack, TestAndroidAttester(), DeviceSecrets.generate(), InMemoryDeviceStateStore()) { println("aa3: $it") }
                .run(this) { c -> scenario(c) }
        }
    }

    private suspend fun CoroutineScope.scenario(c: ExitScenario.Connected) {
        val log: (String) -> Unit = { println("aa3: $it") }
        val devices = DevicesManager(this, ops = { VaultDevicesOps(c.vault) })

        // An agent that is not paired: the vault says not_found to each call.
        try {
            devices.refreshPermissions(AGENT_NOT_PAIRED)
            error("the grants of an unpaired agent were read")
        } catch (e: VaultFailure) {
            check(e.kind == FailureKind.NOT_FOUND) { "list: ${e.kind}" }
        }
        check(devices.resume(AGENT_NOT_PAIRED) == AgentResumption.NOT_PAUSED) { "resume of an unpaired agent" }
        check(devices.removePermission(GRANT_NOBODY_HAS) == PermissionRemoval.ALREADY_GONE) { "revoke of a grant nobody has" }
        log("an unpaired agent and a grant nobody has: not_found, read as 'no longer paused' and 'already removed'")

        // The grant itself, outside the unlock window and then inside it, for an agent that is not paired.
        val request = LeashGrantRequest(AGENT_NOT_PAIRED, "message.list")
        val outside = issue(c, request)
        check(outside == GrantIssueResult.CredentialLocked || outside == GrantIssueResult.NotPaired) { "outside the window: $outside" }
        log("issue outside the window answered $outside")
        c.vault.credentialUnlock(ExitScenario.PASSWORD)
        try {
            val inside = issue(c, request)
            check(inside == GrantIssueResult.NotPaired) { "inside the window, unpaired: $inside" }
        } finally {
            c.vault.credentialLock()
        }

        val agent = pairedAgent(c)
        if (agent == null) {
            log("no agent is paired to this vault: issue, list, remove and resume were not run (pair a vaultctl agent first, AA2)")
            return
        }
        c.vault.credentialUnlock(ExitScenario.PASSWORD)
        val saved = try {
            issue(c, LeashGrantRequest(agent, "message.list"))
        } finally {
            c.vault.credentialLock()
        }
        check(saved is GrantIssueResult.Saved && saved.grant.scope == "message.list") { "issue: $saved" }
        devices.refresh()
        devices.refreshPermissions(agent)
        val listed = devices.permissions.value.getValue(agent)
        check(listed.others.any { it.grantId == saved.grant.grantId }) { "the issued grant is not listed under Other permissions" }
        check(devices.removePermission(saved.grant.grantId!!) == PermissionRemoval.REMOVED)
        devices.refreshPermissions(agent)
        check(devices.permissions.value.getValue(agent).others.none { it.grantId == saved.grant.grantId })
        check(devices.removePermission(saved.grant.grantId!!) == PermissionRemoval.ALREADY_GONE)
        check(devices.resume(agent) == AgentResumption.NOT_PAUSED) { "an agent that is not paused" }
        log("grant ${saved.grant.grantId} issued, listed, removed twice (removed, already gone) and resume answered 'no longer paused' for agent $agent")
    }

    /** `leash.grant.issue` through the app's mapping, as an editor would send it (first try, no resend). */
    private suspend fun issue(c: ExitScenario.Connected, request: LeashGrantRequest): GrantIssueResult = try {
        GrantIssueAnswers.saved(vaultGuard { c.vault.leashGrantIssue(request) })
    } catch (e: VaultFailure) {
        GrantIssueAnswers.refused(e, resent = false)
    }

    /** The id of a device of kind `agent` the app's vault lists, or null. */
    private suspend fun pairedAgent(c: ExitScenario.Connected): String? {
        val list: JsonObject = c.vault.deviceList()
        val devices = list["devices"] as? JsonArray ?: return null
        return devices.map { it.jsonObject }.firstOrNull { it["kind"]?.jsonPrimitive?.content == "agent" }
            ?.let { (it["id"] ?: it["device_id"])?.jsonPrimitive?.content }
    }

    private companion object {
        const val TIMEOUT_MS = 600_000L
        const val AGENT_NOT_PAIRED = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        const val GRANT_NOBODY_HAS = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
    }
}
