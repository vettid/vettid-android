package com.vettid.app.features.agents

/**
 * Data models for agent connection management.
 *
 * Agents are AI agent connectors (vettid-agent) that connect to a user's vault
 * to access secrets and perform actions on the user's behalf. The owner must
 * approve each request when approval_mode is "always_ask".
 */

// MARK: - Approval Models

/**
 * State for the agent approval screen.
 */
sealed class AgentApprovalState {
    object Loading : AgentApprovalState()
    data class Ready(val request: com.vettid.app.core.nats.AgentApprovalRequest) : AgentApprovalState()
    object ProcessingApproval : AgentApprovalState()
    object ProcessingDenial : AgentApprovalState()
    data class Approved(val message: String = "Request approved") : AgentApprovalState()
    data class Denied(val message: String = "Request denied") : AgentApprovalState()
    data class Error(val message: String) : AgentApprovalState()
}

/**
 * Events from the agent approval screen.
 */
sealed class AgentApprovalEvent {
    data class Load(val requestId: String) : AgentApprovalEvent()
    object Approve : AgentApprovalEvent()
    object Deny : AgentApprovalEvent()
    object Dismiss : AgentApprovalEvent()
}

/**
 * Side effects from the agent approval screen.
 */
sealed class AgentApprovalEffect {
    data class ShowError(val message: String) : AgentApprovalEffect()
    data class ShowSuccess(val message: String) : AgentApprovalEffect()
    object NavigateBack : AgentApprovalEffect()
}

// MARK: - Management Models

/**
 * An agent connection as returned by the vault.
 */
data class AgentConnection(
    val connectionId: String,
    val agentName: String,
    val agentType: String,
    val status: String,
    val approvalMode: String,
    val scope: List<String>,
    val connectedAt: String,
    val lastActiveAt: String?,
    val hostname: String?,
    val platform: String?
)

/**
 * State for the agent management screen.
 */
sealed class AgentManagementState {
    object Loading : AgentManagementState()
    data class Loaded(val agents: List<AgentConnection>) : AgentManagementState()
    object Empty : AgentManagementState()
    data class Error(val message: String) : AgentManagementState()
}

/**
 * Events from the agent management screen.
 */
sealed class AgentManagementEvent {
    object LoadAgents : AgentManagementEvent()
    data class RevokeAgent(val connectionId: String) : AgentManagementEvent()
    object CreateInvitation : AgentManagementEvent()
}

/**
 * Side effects from the agent management screen.
 */
sealed class AgentManagementEffect {
    data class ShowError(val message: String) : AgentManagementEffect()
    data class ShowSuccess(val message: String) : AgentManagementEffect()
    object NavigateToCreateInvitation : AgentManagementEffect()
}

// MARK: - Invitation Models

/**
 * State for the create agent invitation screen.
 */
sealed class CreateInvitationState {
    object Ready : CreateInvitationState()
    object Creating : CreateInvitationState()
    data class Created(
        val inviteToken: String,
        val connectionId: String,
        val shortLink: String
    ) : CreateInvitationState()
    data class Error(val message: String) : CreateInvitationState()
}

/**
 * Events from the create invitation screen.
 */
sealed class CreateInvitationEvent {
    data class Create(val name: String) : CreateInvitationEvent()
    object Dismiss : CreateInvitationEvent()
}

/**
 * Side effects from the create invitation screen.
 */
sealed class CreateInvitationEffect {
    data class ShowError(val message: String) : CreateInvitationEffect()
    object NavigateBack : CreateInvitationEffect()
}
