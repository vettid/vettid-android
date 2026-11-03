package com.vettid.core.vault

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `leash.grant.issue` (VAULT-MESSAGING §10.11; ANDROID-PLAN 0.1.116 §4 AA3): the body of an agent's permission. Not built
 * into any screen yet (decision 3a: owner-type permissions stay hidden until the connector supports them); the core is
 * here so the editor can be added without touching the vault client. Never `items.read`: that is a share rule
 * (`share.rule.set`, AA4), and the vault refuses it with `bad_request`.
 *
 * Without [grantId] a new grant; with it a replacement of that grant, and [version] is then required (`conflict`).
 * [perHour] / [perDay] only with `approval: "auto"` (the vault refuses them for `ask`). [expiresAt] is an ISO instant.
 */
data class LeashGrantRequest(
    val agentId: String,
    val scope: String,
    val grantId: String? = null,
    val version: Long? = null,
    val approval: String? = null,
    val connections: List<String>? = null,
    val perHour: Int? = null,
    val perDay: Int? = null,
    val expiresAt: String? = null,
    val statusTtl: Int? = null,
) {
    /** Prints nothing but its type: a grant request names connections. */
    override fun toString(): String = "LeashGrantRequest"

    fun body(): JsonObject = buildJsonObject {
        put("agent_id", agentId)
        grantId?.let { put("grant_id", it) }
        version?.let { put("version", it) }
        put("scope", scope)
        approval?.let { put("approval", it) }
        connections?.let { c -> put("connections", JsonArray(c.map { JsonPrimitive(it) })) }
        perHour?.let { put("per_hour", it) }
        perDay?.let { put("per_day", it) }
        expiresAt?.let { put("expires_at", it) }
        statusTtl?.let { put("status_ttl", it) }
    }
}

/**
 * `leash.grant.issue` (app, within the unlock window): the `<grant>` it answers, with its `delegation` and `sig`.
 * `credential_locked` outside the window; the caller sends `credential.unlock` first.
 */
suspend fun VaultApi.leashGrantIssue(request: LeashGrantRequest): JsonObject = device.op("leash.grant.issue", request.body())
