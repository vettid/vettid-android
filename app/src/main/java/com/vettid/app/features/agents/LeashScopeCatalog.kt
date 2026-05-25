package com.vettid.app.features.agents

/**
 * LEASH scope vocabulary for the mint dialog.
 *
 * LEASH scopes are resource:action[:qualifier] (see
 * vettid-dev/docs/LEASH-TOKEN-FORMAT.md §"Scope grammar"). They are
 * deliberately distinct from agent capability tokens (Contract.Scope,
 * AgentCapabilityCatalog.kt): capabilities gate vault OPS while LEASH
 * scopes describe what an external resource server should accept the
 * LEASH for. A user can mint a LEASH that lets the agent read a
 * specific profile field without granting any vault capability at all.
 *
 * The list here is the curated common-case starter set rendered as
 * pre-defined toggles in the mint dialog. Custom tokens conforming to
 * the grammar can be entered free-form in the dialog's "add custom
 * scope" field; the vault validates them server-side.
 */
data class LeashScopeMeta(
    val token: String,
    val label: String,
    val description: String,
    val defaultGranted: Boolean = false,
)

/** Canonical curated LEASH scopes. Order matches dialog display. */
val LeashCommonScopes: List<LeashScopeMeta> = listOf(
    LeashScopeMeta(
        token = "profile.email:read",
        label = "Read email",
        description = "Lets the agent read your email field from your VettID profile",
        defaultGranted = true,
    ),
    LeashScopeMeta(
        token = "profile.name:read",
        label = "Read name",
        description = "Display name from your VettID profile",
        defaultGranted = true,
    ),
    LeashScopeMeta(
        token = "credential:list",
        label = "List credentials",
        description = "Enumerate credential names (no values)",
    ),
    LeashScopeMeta(
        token = "wallet.balance:read",
        label = "Read wallet balance",
        description = "View balance of your connected wallet",
    ),
    LeashScopeMeta(
        token = "message:send",
        label = "Send messages",
        description = "Post messages on your behalf to recipients you have connections with",
    ),
)

/**
 * Validate a LEASH scope token against the grammar in
 * LEASH-TOKEN-FORMAT.md. The vault re-validates, so this is purely a
 * UX check to surface bad input before submit.
 *
 * Grammar:
 *   scope-token := resource ":" action [ ":" qualifier ]
 *   resource    := identifier ( "." identifier )*
 *   action      := read | write | use | list | sign | *
 *   qualifier   := identifier
 *   identifier  := [a-zA-Z][a-zA-Z0-9_-]*
 */
private val identifierPattern = Regex("^[a-zA-Z][a-zA-Z0-9_-]*$")
private val resourcePattern = Regex("^[a-zA-Z][a-zA-Z0-9_-]*(\\.[a-zA-Z*][a-zA-Z0-9_-]*)*$")
private val validActions = setOf("read", "write", "use", "list", "sign", "*")

fun isValidLeashScope(token: String): Boolean {
    if (token == "*:*") return false  // verifiers reject unscoped
    val parts = token.split(":")
    if (parts.size !in 2..3) return false
    val (resource, action) = parts[0] to parts[1]
    if (!resourcePattern.matches(resource)) return false
    if (action !in validActions) return false
    if (parts.size == 3 && !identifierPattern.matches(parts[2])) return false
    return true
}
