package com.vettid.app.features.voting

import com.google.gson.JsonObject

/**
 * Parse a proposal from the DynamoDB JSON shape returned by the
 * vault's `vote.list`. Mirrors the version inside ProposalsViewModel
 * so other parts of the app (FeedViewModel's badge refresh) can
 * populate the proposals cache without depending on that VM.
 *
 * The legacy in-VM copy is kept as a thin wrapper for now to avoid
 * churning two files in one change; both paths must stay in sync
 * until the duplicate is removed.
 */
fun parseProposalFromJsonForBadge(json: JsonObject): Proposal {
    val choices = json.getAsJsonArray("choices")?.map { choiceEl ->
        val c = choiceEl.asJsonObject
        VoteChoice(
            id = c.get("id")?.asString ?: "",
            label = c.get("label")?.asString ?: "",
            description = c.get("description")?.asString,
        )
    } ?: emptyList()

    val statusStr = json.get("status")?.asString ?: "upcoming"
    val status = when (statusStr) {
        "active" -> ProposalStatus.ACTIVE
        "upcoming" -> ProposalStatus.UPCOMING
        "ended" -> ProposalStatus.ENDED
        "finalized" -> ProposalStatus.FINALIZED
        "cancelled" -> ProposalStatus.CANCELLED
        else -> ProposalStatus.UPCOMING
    }

    return Proposal(
        id = json.get("proposal_id")?.asString ?: json.get("id")?.asString ?: "",
        organizationId = json.get("organization_id")?.asString ?: "",
        title = json.get("proposal_title")?.asString ?: json.get("title")?.asString ?: "",
        description = json.get("proposal_text")?.asString ?: json.get("description")?.asString ?: "",
        choices = choices,
        votingStartsAt = json.get("opens_at")?.asString ?: json.get("voting_starts_at")?.asString ?: "",
        votingEndsAt = json.get("closes_at")?.asString ?: json.get("voting_ends_at")?.asString ?: "",
        status = status,
        signature = json.get("signature")?.asString ?: "",
        signatureKeyId = json.get("signature_key_id")?.asString ?: "",
        createdAt = json.get("created_at")?.asString ?: "",
        userHasVoted = json.get("user_has_voted")?.asBoolean ?: false,
        proposalNumber = json.get("proposal_number")?.asString,
        category = json.get("category")?.asString,
        quorumType = json.get("quorum_type")?.asString,
        quorumValue = json.get("quorum_value")?.asString,
    )
}
