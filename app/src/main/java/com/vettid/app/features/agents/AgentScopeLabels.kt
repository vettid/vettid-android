package com.vettid.app.features.agents

/**
 * Formal scope vocabulary for agent session authorization
 * (vettid-agent/docs/AGENT-PAIRING-FLOW.md §"Locked decisions" #2).
 *
 * The agent sends `requested_scope` as a hint; the phone is the sole
 * authority that writes the final ConnectionContract.Scope on the vault.
 * Tokens not in this map render with the raw token string and a generic
 * "tool-specific action" description so the owner still sees them — we
 * never silently drop a requested scope just because the phone hasn't
 * learned about it yet.
 */
data class ScopeMeta(
    /** Short label rendered next to the toggle. */
    val label: String,
    /** One-line caption explaining what the agent gets if granted. */
    val description: String,
    /**
     * True for scopes the owner should think twice about. Renders the
     * toggle row with a warning accent. Currently: anything that lets
     * the agent change vault state or send on the owner's behalf.
     */
    val sensitive: Boolean = false,
)

val AgentScopeLabels: Map<String, ScopeMeta> = mapOf(
    "secrets.catalog.read" to ScopeMeta(
        label = "Read secret catalog",
        description = "See the list of secret aliases without their values",
    ),
    "secrets.get" to ScopeMeta(
        label = "Read secret values",
        description = "Retrieve the value of a secret (still subject to per-op approval)",
        sensitive = true,
    ),
    "secrets.put" to ScopeMeta(
        label = "Write secrets",
        description = "Create or update secrets in the vault",
        sensitive = true,
    ),
    "message.send" to ScopeMeta(
        label = "Send messages",
        description = "Send messages to your connections on your behalf",
        sensitive = true,
    ),
    "message.recv" to ScopeMeta(
        label = "Receive messages",
        description = "Observe inbound messages from your connections",
    ),
    "call.history" to ScopeMeta(
        label = "Read call history",
        description = "See past calls — who, when, and duration",
    ),
    "connection.list" to ScopeMeta(
        label = "List connections",
        description = "See the names of people you're connected to",
    ),
    "connection.get" to ScopeMeta(
        label = "Read connection details",
        description = "View details about a specific connection",
    ),
)

/**
 * Look up display metadata for a scope token. Falls back gracefully for
 * parameterized tokens (e.g. `agent.action.<tool>`) and unknown tokens.
 */
fun scopeMeta(token: String): ScopeMeta {
    AgentScopeLabels[token]?.let { return it }
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
        sensitive = false,
    )
}
