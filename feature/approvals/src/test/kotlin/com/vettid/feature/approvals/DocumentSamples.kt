// Test data: documents stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.json.Jcs
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalDocument
import java.time.Instant

/** Documents (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents") checked as the app checks them, and their requests. */
object DocumentSamples {
    val now: Instant = Instant.parse("2026-11-17T12:00:00Z")

    const val EXTEND = """{"action":"vote.extend","payload":{"proposal_id":"p-17","closes_at":1796720400,"outage":{"from":1796700000,"to":1796703600,"source":"relay"}},"summary":"Extend the budget vote"}"""

    const val PROPOSAL = """{"action":"proposal.create","payload":{"title":"Adopt the 2027 budget","text":"Line one\nLine two","choices":[{"label":"Yes"},{"label":"No"}],"quorum":{"text":"","min_ballots":1},"binding":true,"removed_may_vote":false,"opens_at":1796700000,"closes_at":1796720400,"outage_rule":"extend"},"summary":"New vote"}"""

    /** Canonical bytes of [json] (members sorted as RFC 8785). */
    fun canonical(json: String): ByteArray = Jcs.canonical(StrictJson.parseObject(json))

    fun context(bytes: ByteArray, line: String = "Approve this"): String = "$line \u2014 ref " + ApprovalDocument.ref(Bytes.sha256(bytes))

    /** The request that announces [json]'s document, with its line. */
    fun request(json: String, id: String = "a1", name: String? = "Example Co-op", line: String = "Approve this"): Approval.Authentication {
        val b = canonical(json)
        return Approval.Authentication(
            id, "c1", context(b, line), now, null, name, com.vettid.core.data.social.AuthPurpose.APPROVAL,
            connectionService = true, documentSha256 = Base64s.encodeStd(Bytes.sha256(b)), documentSize = b.size, documentRequired = true,
        )
    }

    /** [json]'s document as checked for [r]. */
    fun document(json: String, r: Approval.Authentication = request(json)): ApprovalDocument {
        val b = canonical(json)
        val c = ApprovalDocument.check(Base64s.encodeStd(b), r.documentSha256, r.documentSize, r.context, r.requestId)
        return (c as ApprovalDocument.Check.Shown).document
    }

    fun check(json: String, r: Approval.Authentication = request(json)): ApprovalDocument.Check = ApprovalDocument.Check.Shown(document(json, r))

    /** A raw-view document with one string of [size] characters. */
    fun big(size: Int, ch: Char = 'x'): String =
        """{"action":"admin.repin","payload":{"note":"${ch.toString().repeat(size - 1)}Z"},"summary":"Big"}"""
}
