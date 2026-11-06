package com.vettid.core.vault

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The requests of "Devices & agents" (ANDROID-PLAN 0.1.53 AA1; VAULT-MESSAGING §10.3, §10.11), beside [VaultApi] rather
 * than in it. Each goes through the client's own operation path, so a pending lock refuses it like any other.
 */

/** `device.session.end{device_id}` (§6.8, §10.3): the owner app ends a desktop's or agent's access session; `{}`. */
suspend fun VaultApi.deviceSessionEnd(deviceId: String) {
    device.op("device.session.end", buildJsonObject { put("device_id", deviceId) })
}

/** `device.unlink{device_id}` (§7.4, §10.3): removes a desktop or agent; `forbidden` for the app itself; `{}`. */
suspend fun VaultApi.deviceUnlink(deviceId: String) {
    device.op("device.unlink", buildJsonObject { put("device_id", deviceId) })
}

/**
 * `leash.grant.list{agent_id?}` (§10.11): `{grants, suspended}`; the app reads it for an agent's grants and, without
 * [agentId], for the agents the vault paused (`suspended`, agent ids). Grants carry their `delegation`; callers read
 * only what they show.
 */
suspend fun VaultApi.leashGrantList(agentId: String? = null): JsonObject =
    device.op("leash.grant.list", buildJsonObject { agentId?.let { put("agent_id", it) } })

/**
 * `leash.grant.revoke{grant_id}` (§10.11, ANDROID-PLAN 0.1.116 §4 AA3): `{}`; no unlock window, nothing signed. Revoking an
 * `items.read` grant deletes its share rule; `not_found` when the grant is already gone.
 */
suspend fun VaultApi.leashGrantRevoke(grantId: String) {
    device.op("leash.grant.revoke", buildJsonObject { put("grant_id", grantId) })
}

/** `leash.agent.resume{agent_id}` (§10.11, AA3): `{}`; `not_found` unless the agent is suspended. No unlock window. */
suspend fun VaultApi.leashAgentResume(agentId: String) {
    device.op("leash.agent.resume", buildJsonObject { put("agent_id", agentId) })
}

/*
 * Pairing an agent or a desktop (ANDROID-PLAN 0.1.53 AA2; VAULT-MESSAGING §6.7, §10.3). "Pair a desktop" (owner decision
 * 2026-11-05) reverses decision 9, so the role is a parameter; never `app` (that is a transfer, §6.7.1).
 */

/**
 * `device.pair.create{role}` with [role] `agent` or `desktop` (§10.3): `{pairing_id, link, exp}`; the link is a bare pairing
 * payload (§6.4).
 */
suspend fun VaultApi.devicePairCreate(role: String): JsonObject {
    require(role == "agent" || role == "desktop") { "pairing role" }
    return device.op("device.pair.create", buildJsonObject { put("role", role) })
}

/**
 * `device.pair.approve{pairing_id, session_seconds?}` (§10.3): `{}`. Never carries `grants` (decision 2: an agent's
 * reading permissions are share rules, which the approval refuses), so it needs no unlock window.
 */
suspend fun VaultApi.devicePairApprove(pairingId: String, sessionSeconds: Int?) {
    device.op(
        "device.pair.approve",
        buildJsonObject {
            put("pairing_id", pairingId)
            sessionSeconds?.let { put("session_seconds", it) }
        },
    )
}

/** `device.pair.reject{pairing_id}` (§10.3): `{}`; the vault tells the new device once it has scanned (§6.7). */
suspend fun VaultApi.devicePairReject(pairingId: String) {
    device.op("device.pair.reject", buildJsonObject { put("pairing_id", pairingId) })
}
