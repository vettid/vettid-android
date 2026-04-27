package com.vettid.app.features.voting

import com.google.gson.JsonObject

/**
 * Single source of truth for parsing a proposal from the DynamoDB JSON shape
 * returned by the vault's `vote.list`. Used by both ProposalsViewModel and
 * FeedViewModel's badge refresh — earlier we had two parsers that drifted.
 */
fun parseProposalFromJson(json: JsonObject): Proposal {
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
        "closed" -> ProposalStatus.CLOSED
        // Legacy: treat ended/finalized as CLOSED until the migration Lambda
        // rewrites them. New rows never use these strings.
        "ended", "finalized" -> ProposalStatus.CLOSED
        "cancelled" -> ProposalStatus.CANCELLED
        else -> ProposalStatus.UPCOMING
    }

    val results = parseVoteResults(json)

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
        results = results,
        proposalNumber = json.get("proposal_number")?.asString,
        category = json.get("category")?.asString,
        quorumType = json.get("quorum_type")?.asString,
        quorumValue = json.get("quorum_value")?.asString,
    )
}

/** Backward-compatible alias for prior call sites. */
fun parseProposalFromJsonForBadge(json: JsonObject): Proposal = parseProposalFromJson(json)

/**
 * Pull the final-tally / Merkle fields off a closed proposal. Returns null
 * for proposals that haven't been closed/published yet.
 */
private fun parseVoteResults(json: JsonObject): VoteResults? {
    val total = json.get("final_total")?.asString?.toIntOrNull()
        ?: json.get("final_total")?.asInt
        ?: return null

    // vote_counts is a Map<choice_id, count> emitted by publishVoteResults.
    val counts = mutableMapOf<String, Int>()
    json.getAsJsonObject("vote_counts")?.let { vc ->
        vc.getAsJsonObject("counts")?.entrySet()?.forEach { (id, v) ->
            counts[id] = v.asString.toIntOrNull() ?: v.asInt
        }
        // Older rows may have just a counts object directly under vote_counts.
        if (counts.isEmpty()) {
            vc.entrySet().forEach { (id, v) ->
                if (id != "total" && id != "web_votes") {
                    counts[id] = v.asString?.toIntOrNull() ?: v.asInt
                }
            }
        }
    }
    // Fallback to the historical yes/no/abstain attributes for older rows.
    if (counts.isEmpty()) {
        json.get("final_yes")?.asString?.toIntOrNull()?.let { counts["yes"] = it }
        json.get("final_no")?.asString?.toIntOrNull()?.let { counts["no"] = it }
        json.get("final_abstain")?.asString?.toIntOrNull()?.let { counts["abstain"] = it }
    }

    return VoteResults(
        totalVotes = total,
        choiceCounts = counts,
        merkleRoot = json.get("merkle_root")?.asString ?: "",
        closedAt = json.get("closed_at")?.asString ?: "",
        resultsPublishedAt = json.get("results_published_at")?.asString ?: "",
        passed = json.get("passed")?.asBoolean ?: false,
        quorumMet = json.get("quorum_met")?.asBoolean ?: false,
        eligibleVoters = json.get("eligible_voters")?.asString?.toIntOrNull()
            ?: json.get("eligible_voters")?.asInt ?: 0,
    )
}
