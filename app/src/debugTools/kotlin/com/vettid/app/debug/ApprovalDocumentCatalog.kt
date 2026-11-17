// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.runtime.Composable
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.json.Jcs
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalDocument
import com.vettid.core.data.social.AuthPurpose
import com.vettid.feature.approvals.ApprovalDetailScreen
import com.vettid.feature.approvals.ApprovalDetailUiState
import com.vettid.feature.approvals.DecisionActions
import com.vettid.feature.approvals.DocumentPresentation
import com.vettid.feature.approvals.DocumentStep
import java.time.Instant

/**
 * An approval's document (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents"): a form (`proposal.create`), the raw view with
 * a right-to-left override in a string, an unknown action, "This request can't be shown", and the document loading.
 */
internal object ApprovalDocumentCatalog {
    private val now = Instant.now()

    private const val PROPOSAL = """{"action":"proposal.create","payload":{"title":"Adopt the 2027 budget","text":"The cooperative adopts the 2027 budget as circulated.\n\nSpending over the line needs a new vote.","choices":[{"label":"Yes"},{"label":"No"}],"quorum":{"text":"At least a third of members.","min_ballots":12},"binding":true,"removed_may_vote":false,"opens_at":1796700000,"closes_at":1797304800,"outage_rule":"extend"},"summary":"Vote on the 2027 budget"}"""

    private const val RAW = """{"action":"admin.repin","payload":{"admin":"Dana Levi","note":"key \u202Ecba\u202C replaced","pin":"9f86d081884c7d65","count":10,"count_text":"10","active":true},"summary":"Re-pin Dana's key after a phone change"}"""

    private const val UNKNOWN = """{"action":"treasury.pay_out","payload":{"amount":"40.00","to":"Example Supplies"},"summary":"Pay the supplier"}"""

    private fun request(json: String): Pair<Approval.Authentication, ApprovalDocument> {
        val b = Jcs.canonical(StrictJson.parseObject(json))
        val sha = Base64s.encodeStd(Bytes.sha256(b))
        val r = Approval.Authentication(
            "a1", "c1", "Approve this action \u2014 ref " + ApprovalDocument.ref(Bytes.sha256(b)), now, now.plusSeconds(600),
            "Example Cooperative", AuthPurpose.APPROVAL, connectionService = true, documentSha256 = sha, documentSize = b.size, documentRequired = true,
        )
        val d = (ApprovalDocument.check(Base64s.encodeStd(b), sha, b.size, r.context, r.requestId) as ApprovalDocument.Check.Shown).document
        return r to d
    }

    @Composable
    private fun shown(json: String) {
        val (r, d) = request(json)
        val step = DocumentStep.Shown(d, DocumentPresentation.of(d, d.context)!!)
        ApprovalDetailScreen(ApprovalDetailUiState(r.key, r, document = step, documentGeneration = 1), DecisionActions())
    }

    @Composable
    private fun step(step: DocumentStep) {
        val (r, _) = request(PROPOSAL)
        ApprovalDetailScreen(ApprovalDetailUiState(r.key, r, document = step), DecisionActions())
    }

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "approvals.document_form" to { shown(PROPOSAL) },
        "approvals.document_raw" to { shown(RAW) },
        "approvals.document_unknown" to { shown(UNKNOWN) },
        "approvals.document_cant_show" to { step(DocumentStep.Unshowable(ApprovalDocument.Failure.HASH)) },
        "approvals.document_loading" to { step(DocumentStep.Loading) },
    )
}
