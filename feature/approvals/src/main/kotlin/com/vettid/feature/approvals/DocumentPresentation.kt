package com.vettid.feature.approvals

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.json.Jcs
import com.vettid.core.crypto.json.JsonArray
import com.vettid.core.crypto.json.JsonBool
import com.vettid.core.crypto.json.JsonNull
import com.vettid.core.crypto.json.JsonNumber
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.JsonString
import com.vettid.core.crypto.json.JsonValue
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.data.social.ApprovalDocument

/** A raw view's container: an object or an array. */
enum class Container { OBJECT, ARRAY }

/**
 * What the approval screen shows of a checked document (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents", "What the app
 * shows"), in its order: what is asked, the service's description (`context`), its summary, and the request, as a form for
 * a known action whose payload matches it exactly, otherwise the whole document's raw view. Every string is a [ShownText];
 * the labels are the screen's own (never a value), and no default value ever goes under one. As vettid-ios #300.
 */
data class DocumentPresentation(
    val asked: Asked,
    /** The service's own line (`context`), when the challenge has one. */
    val context: ShownText?,
    val summary: ShownText,
    val request: Request,
) {
    /** 1. What is asked: the app's own title for a known action, else the action's name with the warning. */
    sealed interface Asked {
        data class Known(val action: KnownAction) : Asked

        data class Unknown(val action: String) : Asked
    }

    /** The actions service-vault asks approval for (SERVICE-VAULT §7), with the app's title for each. */
    enum class KnownAction(val wire: String) {
        PROPOSAL_CREATE("proposal.create"),
        VOTE_EXTEND("vote.extend"),
        ADMIN_SET_ADD("admin_set.add"),
        ADMIN_SET_REMOVE("admin_set.remove"),
        ADMIN_REPIN("admin.repin"),
        CONTRACT_TEMPLATE_CHANGE("contract_template.change"),
        ASSIGNMENT_PUSH("assignment.push"),
        ;

        companion object {
            fun of(action: String): KnownAction? = entries.firstOrNull { it.wire == action }
        }
    }

    /** 4. The request. */
    sealed interface Request {
        data class Proposal(val form: ProposalForm) : Request

        data class Extend(val form: ExtendForm) : Request

        /** "Request details": the whole document, a line to a member or element. */
        data class Raw(val lines: List<RawLine>) : Request
    }

    /** `proposal.create`'s form, its fields in the screen's order. */
    data class ProposalForm(
        val title: ShownText,
        /** Motion; null when empty ("None"). */
        val text: ShownText?,
        val choices: List<ShownText>,
        val quorumBallots: Long,
        /** Quorum's text; null when empty. */
        val quorumText: ShownText?,
        val binding: Boolean,
        val removedMayVote: Boolean,
        val opensAt: Long,
        val closesAt: Long,
        val outageRule: ShownText,
    )

    /** `vote.extend`'s form. */
    data class ExtendForm(
        /** The vote, shown as it is. */
        val proposalId: ShownText,
        val closesAt: Long,
        val outageFrom: Long,
        val outageTo: Long,
        val outageSource: ShownText,
    )

    /**
     * One line of the raw view: indented by [depth], a member's [key] (null for an array element or the document itself),
     * its value or the bracket that opens or closes a container, and a comma after it when another member or element follows.
     */
    data class RawLine(val depth: Int, val key: ShownText?, val value: RawValue, val comma: Boolean)

    sealed interface RawValue {
        /** Shown with its quotation marks, outside its isolate. */
        data class Str(val text: ShownText) : RawValue

        data class Number(val raw: String) : RawValue

        data class Bool(val value: Boolean) : RawValue

        data object Null : RawValue

        /** `{` or `[` opening a container whose members follow; [Close] ends it. */
        data class Open(val container: Container) : RawValue

        data class Close(val container: Container) : RawValue

        /** `{}` or `[]`. */
        data class Empty(val container: Container) : RawValue
    }

    companion object {
        /** The latest time a form shows: 9999-12-31 23:59:59 UTC. */
        const val MAX_TIME = 253_402_300_799L

        /** Null only if the checked bytes no longer parse (they always do: they passed the same parser). */
        fun of(d: ApprovalDocument, context: String?): DocumentPresentation? {
            val known = KnownAction.of(d.action)
            val request = when {
                known == KnownAction.PROPOSAL_CREATE -> proposal(d.payload)?.let { Request.Proposal(it) }
                known == KnownAction.VOTE_EXTEND -> extend(d.payload)?.let { Request.Extend(it) }
                else -> null
            } ?: raw(d)?.let { Request.Raw(it) } ?: return null
            return DocumentPresentation(
                asked = known?.let { Asked.Known(it) } ?: Asked.Unknown(d.action),
                context = context?.let { ShownText.of(it) },
                summary = ShownText.of(d.summary),
                request = request,
            )
        }

        /** The object's members, when they are exactly [names]. */
        private fun exactly(v: JsonValue?, names: Set<String>): Map<String, JsonValue>? {
            val o = v as? JsonObject ?: return null
            return o.members.takeIf { it.keys == names }
        }

        private fun string(v: JsonValue?): String? = (v as? JsonString)?.value

        private fun bool(v: JsonValue?): Boolean? = (v as? JsonBool)?.value

        /** A JSON integer (§5.3: no sign, fraction or exponent, at most 2^53 - 1). */
        private fun integer(v: JsonValue?): Long? = (v as? JsonNumber)?.raw?.takeIf { Jcs.isInteger(it) }?.toLongOrNull()

        private fun time(v: JsonValue?): Long? = integer(v)?.takeIf { it in 0..MAX_TIME }

        private val PROPOSAL = setOf(
            "title", "text", "choices", "quorum", "binding", "removed_may_vote", "opens_at", "closes_at", "outage_rule",
        )

        @Suppress("ReturnCount", "CyclomaticComplexMethod") // any member that does not match is no form
        internal fun proposal(payload: JsonObject): ProposalForm? {
            val m = exactly(payload, PROPOSAL) ?: return null
            val quorum = exactly(m["quorum"], setOf("text", "min_ballots")) ?: return null
            val items = (m["choices"] as? JsonArray)?.items ?: return null
            val choices = items.map { c -> string(exactly(c, setOf("label"))?.get("label"))?.let { ShownText.of(it) } ?: return null }
            val text = string(m["text"]) ?: return null
            val quorumText = string(quorum["text"]) ?: return null
            return ProposalForm(
                title = ShownText.of(string(m["title"]) ?: return null),
                text = text.takeIf { it.isNotEmpty() }?.let { ShownText.of(it, lineBreaks = true) },
                choices = choices,
                quorumBallots = integer(quorum["min_ballots"]) ?: return null,
                quorumText = quorumText.takeIf { it.isNotEmpty() }?.let { ShownText.of(it, lineBreaks = true) },
                binding = bool(m["binding"]) ?: return null,
                removedMayVote = bool(m["removed_may_vote"]) ?: return null,
                opensAt = time(m["opens_at"]) ?: return null,
                closesAt = time(m["closes_at"]) ?: return null,
                outageRule = ShownText.of(string(m["outage_rule"]) ?: return null),
            )
        }

        @Suppress("ReturnCount") // any member that does not match is no form
        internal fun extend(payload: JsonObject): ExtendForm? {
            val m = exactly(payload, setOf("proposal_id", "closes_at", "outage")) ?: return null
            val outage = exactly(m["outage"], setOf("from", "to", "source")) ?: return null
            return ExtendForm(
                proposalId = ShownText.of(string(m["proposal_id"]) ?: return null),
                closesAt = time(m["closes_at"]) ?: return null,
                outageFrom = time(outage["from"]) ?: return null,
                outageTo = time(outage["to"]) ?: return null,
                outageSource = ShownText.of(string(outage["source"]) ?: return null),
            )
        }

        /**
         * The whole document, in its (canonical) order: the document's own object (`action`, `payload` and `summary`), the
         * checked bytes parsed again exactly as they were. Never the payload alone in its place.
         */
        internal fun raw(d: ApprovalDocument): List<RawLine>? {
            val o = try {
                StrictJson.parseObject(d.bytes, ApprovalDocument.MAX_DEPTH)
            } catch (_: CryptoException) {
                return null
            }
            val lines = mutableListOf<RawLine>()
            appendLines(o, key = null, depth = 0, comma = false, lines)
            return lines
        }

        private fun appendLines(v: JsonValue, key: String?, depth: Int, comma: Boolean, lines: MutableList<RawLine>) {
            val k = key?.let { ShownText.of(it, escaped = true) }
            fun line(value: RawValue, c: Boolean = comma, withKey: Boolean = true) =
                lines.add(RawLine(depth, if (withKey) k else null, value, c))
            when (v) {
                is JsonString -> line(RawValue.Str(ShownText.of(v.value, escaped = true)))
                is JsonNumber -> line(RawValue.Number(v.raw))
                is JsonBool -> line(RawValue.Bool(v.value))
                is JsonNull -> line(RawValue.Null)
                is JsonArray -> if (v.items.isEmpty()) {
                    line(RawValue.Empty(Container.ARRAY))
                } else {
                    line(RawValue.Open(Container.ARRAY), c = false)
                    v.items.forEachIndexed { i, e -> appendLines(e, null, depth + 1, i < v.items.size - 1, lines) }
                    line(RawValue.Close(Container.ARRAY), withKey = false)
                }
                is JsonObject -> if (v.members.isEmpty()) {
                    line(RawValue.Empty(Container.OBJECT))
                } else {
                    line(RawValue.Open(Container.OBJECT), c = false)
                    val last = v.members.size - 1
                    v.members.entries.forEachIndexed { i, (name, value) -> appendLines(value, name, depth + 1, i < last, lines) }
                    line(RawValue.Close(Container.OBJECT), withKey = false)
                }
            }
        }
    }
}
