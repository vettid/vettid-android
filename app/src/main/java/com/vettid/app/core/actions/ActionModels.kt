package com.vettid.app.core.actions

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * Mirror of the vault-manager's Phase-1 shared-action layer. The catalog
 * itself is defined in the vault (action_catalog.go); this file carries
 * only the wire shapes the Kotlin app needs to render lists, validate
 * params, send invocations, and display results.
 *
 * Hard cutover per release — when the vault returns
 * ERR_ACTION_VERSION:current=N, the app prompts the user to upgrade
 * rather than continuing with a stale schema.
 */

enum class ActionScope(val wire: String) {
    @SerializedName("read") READ("read"),
    @SerializedName("read-write") READ_WRITE("read-write"),
    @SerializedName("propose") PROPOSE("propose");
}

enum class ActionAuthMode(val wire: String) {
    @SerializedName("default-deny") DEFAULT_DENY("default-deny"),
    @SerializedName("allowlist") ALLOWLIST("allowlist"),
    @SerializedName("prompt-each-time") PROMPT_EACH_TIME("prompt-each-time"),
    @SerializedName("default-allow") DEFAULT_ALLOW("default-allow");
}

/** Catalog entry as the vault advertises it. param_schema/result_schema are JSON Schema 2020-12 strings. */
data class ActionDef(
    val id: String,
    val version: Int,
    val label: String,
    val description: String,
    val icon: String,
    val scope: ActionScope,
    @SerializedName("default_auth_mode")
    val defaultAuthMode: ActionAuthMode,
    @SerializedName("param_schema")
    val paramSchema: String,
    @SerializedName("result_schema")
    val resultSchema: String,
)

/** Owner-side enabled state for one action. */
data class EnabledAction(
    val id: String,
    val mode: ActionAuthMode,
    val allowlist: List<String> = emptyList(),
    @SerializedName("owner_params")
    val ownerParams: Map<String, Any?> = emptyMap(),
    @SerializedName("updated_at")
    val updatedAt: Long = 0,
)

/**
 * action.list-mine response shape. Combines catalog + per-action enabled state.
 */
data class MyActionsResponse(
    @SerializedName("catalog_version")
    val catalogVersion: Int,
    val actions: List<MyActionEntry>,
)

data class MyActionEntry(
    val def: ActionDef,
    val enabled: EnabledAction?,
)

/** Subset of ActionDef visible on a peer's published profile. */
data class PublishedAction(
    val id: String,
    val version: Int,
    val label: String,
    val description: String,
    val icon: String,
    val scope: ActionScope,
    @SerializedName("auth_mode")
    val authMode: ActionAuthMode,
    @SerializedName("param_schema")
    val paramSchema: String,
    @SerializedName("result_schema")
    val resultSchema: String,
    @SerializedName("available_to_me")
    val availableToMe: Boolean = true,
)

/** Wire shape for an inbound pending approval (peer → me). */
data class PendingActionApproval(
    @SerializedName("invocation_id") val invocationId: String,
    @SerializedName("action_id") val actionId: String,
    @SerializedName("action_version") val actionVersion: Int,
    @SerializedName("invoker_guid") val invokerGuid: String,
    @SerializedName("invoker_pubkey") val invokerPubkey: String,
    @SerializedName("connection_id") val connectionId: String,
    val params: JsonElement,
    @SerializedName("invoked_at") val invokedAt: String,
    val status: String,
    @SerializedName("created_at") val createdAt: Long,
)

/** Wire shape for an inbound result envelope (peer → me, after I invoked). */
data class ActionInvocationResult(
    @SerializedName("invocation_id") val invocationId: String,
    @SerializedName("action_id") val actionId: String,
    val status: String, // ok | denied | failed | pending_approval | expired
    val result: JsonElement? = null,
    val error: String? = null,
    @SerializedName("decided_at") val decidedAt: String,
    @SerializedName("peer_guid") val peerGuid: String,
    @SerializedName("peer_sig") val peerSig: String,
)

/** Builders for outbound NATS payloads. */
object ActionRequests {
    fun listMine() = JsonObject()

    fun listOnPeer(connectionId: String) = JsonObject().apply {
        addProperty("connection_id", connectionId)
    }

    fun setEnabled(
        actionId: String,
        mode: ActionAuthMode,
        allowlist: List<String> = emptyList(),
        ownerParams: Map<String, Any?>? = null,
    ) = JsonObject().apply {
        addProperty("action_id", actionId)
        addProperty("mode", mode.wire)
        add("allowlist", com.google.gson.Gson().toJsonTree(allowlist))
        if (ownerParams != null) add("owner_params", com.google.gson.Gson().toJsonTree(ownerParams))
    }

    fun invokeOnPeer(
        connectionId: String,
        actionId: String,
        actionVersion: Int,
        params: JsonElement,
    ) = JsonObject().apply {
        addProperty("connection_id", connectionId)
        addProperty("action_id", actionId)
        addProperty("action_version", actionVersion)
        add("params", params)
    }

    fun listPending() = JsonObject()

    fun approve(
        invocationId: String,
        ownerNote: String? = null,
        ownerOverrides: Map<String, Any?>? = null,
    ) = JsonObject().apply {
        addProperty("invocation_id", invocationId)
        if (ownerNote != null) addProperty("owner_note", ownerNote)
        if (ownerOverrides != null) add("owner_overrides", com.google.gson.Gson().toJsonTree(ownerOverrides))
    }

    fun deny(invocationId: String, ownerNote: String? = null) = JsonObject().apply {
        addProperty("invocation_id", invocationId)
        if (ownerNote != null) addProperty("owner_note", ownerNote)
    }
}
