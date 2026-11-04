package com.vettid.core.vault

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The requests of "Presence" (ANDROID-PLAN §4 "Presence"; VAULT-MESSAGING §10.17), beside [VaultApi] rather than in it. A
 * vault whose release does not list the feature answers `unsupported_type`; a held vault `owner_check_required` (the app
 * sends no `presence.*` then). The answer to a query comes later, as the ephemeral event `presence.result`.
 */

/** `presence.get{}`: `{version, state, share, except}`. */
suspend fun VaultApi.presenceGet(): JsonObject = device.op("presence.get")

/**
 * `presence.set{version, state?, share?, except?}`: `{version}`, or `conflict` when [version] is not the current one. A
 * member left out (null) keeps its value; [except] is sent as given (the caller deduplicates and sorts it, 0 to 1,024 ids).
 */
suspend fun VaultApi.presenceSet(version: Long, state: String?, share: String?, except: List<String>?): JsonObject =
    device.op(
        "presence.set",
        buildJsonObject {
            put("version", version)
            state?.let { put("state", it) }
            share?.let { put("share", it) }
            except?.let { put("except", JsonArray(it.map(::JsonPrimitive))) }
        },
    )

/** `presence.query{connection_id}`: `{ping_id, exp}`; `not_found`, `connection_unavailable`. */
suspend fun VaultApi.presenceQuery(connectionId: String): JsonObject =
    device.op("presence.query", buildJsonObject { put("connection_id", connectionId) })
