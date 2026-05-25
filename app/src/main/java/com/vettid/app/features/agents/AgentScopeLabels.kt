package com.vettid.app.features.agents

/**
 * Agent capability vocabulary. Mirrors enclave/vault-manager/
 * agent_capabilities.go — the vault enforces these tokens directly,
 * so the phone-side picker must match the constants in that file.
 *
 * Contract.Scope is now pure capabilities. Categories (CREDIT_CARD,
 * API_KEY, …) used to appear here as filter tokens — they don't
 * anymore. Per-secret visibility is the owner's Discoverability
 * decision on each item, surfaced via the per-secret editor and the
 * bulk "Visibility" section on Agent Details.
 */
data class ScopeMeta(
    /** Short label rendered next to the toggle. */
    val label: String,
    /** One-line caption explaining what the agent gets if granted. */
    val description: String,
    /**
     * True for capabilities the owner should think twice about.
     * Renders the toggle row with a warning accent. Currently: anything
     * that lets the agent read secret values or send on the owner's
     * behalf.
     */
    val sensitive: Boolean = false,
    /** Default-on state when the owner first opens the picker. */
    val defaultGranted: Boolean = true,
)

/** Canonical capability tokens. Must match agent_capabilities.go. */
const val CAP_SECRETS_CATALOG_READ = "secrets.catalog.read"
const val CAP_SECRETS_GET = "secrets.get"
const val CAP_SECRETS_ACTION = "secrets.action"
const val CAP_MESSAGE_SEND = "message.send"
const val CAP_MESSAGE_RECV = "message.recv"

/**
 * Capability tokens shown on the AuthorizeAgentScreen picker. The
 * picker renders every entry here regardless of what the agent
 * requested — the owner gets the full menu.
 */
val AgentCapabilityCatalog: List<Pair<String, ScopeMeta>> = listOf(
    CAP_SECRETS_CATALOG_READ to ScopeMeta(
        label = "Read secret catalog",
        description = "See the list of secrets you've made discoverable, without their values",
    ),
    CAP_SECRETS_GET to ScopeMeta(
        label = "Read secret values",
        description = "Retrieve the value of a specific secret (still per-op approval)",
        sensitive = true,
    ),
    CAP_SECRETS_ACTION to ScopeMeta(
        label = "Use secrets for actions",
        description = "Sign / derive / use a secret without ever seeing its value",
        sensitive = true,
    ),
    CAP_MESSAGE_SEND to ScopeMeta(
        label = "Send messages",
        description = "Post messages to you (chat content, approval requests)",
    ),
    CAP_MESSAGE_RECV to ScopeMeta(
        label = "Receive your replies",
        description = "Deliver your chat replies and approval responses",
    ),
)

private val AgentCapabilityMap: Map<String, ScopeMeta> = AgentCapabilityCatalog.toMap()

/**
 * Look up display metadata for a capability token. Falls back gracefully
 * for parameterized tokens (e.g. `agent.action.<tool>`) and unknown
 * tokens — the latter still render so an owner can deny something the
 * vault hasn't been taught about yet.
 */
fun scopeMeta(token: String): ScopeMeta {
    AgentCapabilityMap[token]?.let { return it }
    if (token.startsWith("agent.action.")) {
        val tool = token.removePrefix("agent.action.")
        return ScopeMeta(
            label = "Run tool: $tool",
            description = "Invoke the agent's $tool tool on your behalf",
            sensitive = true,
        )
    }
    return ScopeMeta(
        label = token,
        description = "Custom permission requested by the agent",
        sensitive = true,
    )
}
