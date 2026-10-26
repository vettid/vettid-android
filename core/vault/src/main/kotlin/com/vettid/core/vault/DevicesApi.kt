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

/*
 * Pairing an agent (ANDROID-PLAN 0.1.53 AA2; VAULT-MESSAGING §6.7, §10.3). Only an agent is ever paired from the app
 * (decision 9: no "Pair a desktop"), so the role is not a parameter.
 */

/** `device.pair.create{role: "agent"}` (§10.3): `{pairing_id, link, exp}`; the link is a bare pairing payload (§6.4). */
suspend fun VaultApi.devicePairCreateAgent(): JsonObject =
    device.op("device.pair.create", buildJsonObject { put("role", "agent") })

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
