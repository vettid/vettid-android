package com.vettid.core.vault

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The requests of "Votes" (ANDROID-PLAN 0.1.102 to 0.1.108 §4 "Votes"; VAULT-MESSAGING 0.81.3 §10.19.8), beside [VaultApi]
 * rather than in it. All are the owner app's alone (a desktop or an agent is `forbidden`). Every request about one
 * proposal names `connection_id` together with `proposal_id` (0.81.2): the vault keys its records by the pair. A vault
 * without the feature answers `unsupported_type`; a held vault `owner_check_required`.
 */

/** `vote.list{state?, after?, limit?, ack_dropped?}`: `{votes, next?, dropped, problems, reconnect, list_version?, list_sha256?}`. */
suspend fun VaultApi.voteList(
    state: String? = null,
    after: String? = null,
    limit: Int? = null,
    ackDropped: List<String> = emptyList(),
): JsonObject =
    device.op(
        "vote.list",
        buildJsonObject {
            state?.let { put("state", it) }
            after?.let { put("after", it) }
            limit?.let { put("limit", it) }
            if (ackDropped.isNotEmpty()) put("ack_dropped", JsonArray(ackDropped.map(::JsonPrimitive)))
        },
    )

/** `vote.get{connection_id, proposal_id}`: the vote, with its text and choices (and the member's `choice_id` once cast). */
suspend fun VaultApi.voteGet(connectionId: String, proposalId: String): JsonObject = device.op("vote.get", pair(connectionId, proposalId))

/**
 * `vote.cast{connection_id, proposal_id, choice_id}`: `{proposal_id, ballot: {state}}`. A repeat is safe (a second tap or a
 * timeout); `credential_locked` means the unlock window is closed (open it and send again).
 */
suspend fun VaultApi.voteCast(connectionId: String, proposalId: String, choiceId: String): JsonObject =
    device.op(
        "vote.cast",
        buildJsonObject {
            put("connection_id", connectionId)
            put("proposal_id", proposalId)
            put("choice_id", choiceId)
        },
    )

/** `vote.check{connection_id, proposal_id}`: asks the service `vote.status` now; `{}`. */
suspend fun VaultApi.voteCheck(connectionId: String, proposalId: String) {
    device.op("vote.check", pair(connectionId, proposalId))
}

/** `vote.confirm{connection_id, proposal_id, mine}`: the member's answer after a restore; `{ballot: {state}}`. */
suspend fun VaultApi.voteConfirm(connectionId: String, proposalId: String, mine: Boolean): JsonObject =
    device.op(
        "vote.confirm",
        buildJsonObject {
            put("connection_id", connectionId)
            put("proposal_id", proposalId)
            put("mine", mine)
        },
    )

/** `vote.evidence{connection_id, proposal_id}`: `{evidence: {…}}`, to be written unchanged where the member puts it. */
suspend fun VaultApi.voteEvidence(connectionId: String, proposalId: String): JsonObject =
    device.op("vote.evidence", pair(connectionId, proposalId))

/**
 * `vote.release{connection_id, proposal_id, utk_id, sealed}`: the vault PIN alone sealed to a UTK, as `audit.export` carries
 * it (§3.5.4). `evidence_not_exported` until `vote.evidence` was called after the record's last change.
 */
suspend fun VaultApi.voteRelease(connectionId: String, proposalId: String, pin: String) {
    credentialOps.sealedOpAt(
        "vote.release",
        { put("pin", pin) },
        {
            put("connection_id", connectionId)
            put("proposal_id", proposalId)
        },
    )
}

/**
 * `vote.site.sync{services?, services_confirm?, entries?}` (§10.19.8) with [body] as built by the caller, inline (at most
 * 245,760 bytes encoded); `{services?, services_confirm?, entries}`.
 */
suspend fun VaultApi.voteSiteSync(body: JsonObject): JsonObject = device.op("vote.site.sync", body)

/** `vote.site.sync{blob: {id, key, sha256, size}}` (§10.19.8, §5.5): a request over 245,760 bytes, uploaded as a claim-check blob. */
suspend fun VaultApi.voteSiteSyncBlob(blobId: String, keyB64: String, sha256B64: String, size: Int): JsonObject =
    device.op(
        "vote.site.sync",
        buildJsonObject {
            put(
                "blob",
                buildJsonObject {
                    put("id", blobId)
                    put("key", keyB64)
                    put("sha256", sha256B64)
                    put("size", size)
                },
            )
        },
    )

/** `vote.results.submit{connection_id, proposal_id, results, results_sig, bundle, row, row_proof}` (§10.19.6) as built by the caller. */
suspend fun VaultApi.voteResultsSubmit(body: JsonObject): JsonObject = device.op("vote.results.submit", body)

private fun pair(connectionId: String, proposalId: String) = buildJsonObject {
    put("connection_id", connectionId)
    put("proposal_id", proposalId)
}
