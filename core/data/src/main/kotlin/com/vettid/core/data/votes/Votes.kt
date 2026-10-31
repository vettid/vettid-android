package com.vettid.core.data.votes

import com.vettid.core.data.account.AccountNames
import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/*
 * Votes as the vault shows them (VAULT-MESSAGING 0.81.3 §10.19.8; ANDROID-PLAN 0.1.108 §4 "Votes"). A vote is a service's
 * proposal; the vault does every check and signature. Held in memory only, while the vault is open. The member's choice
 * is in [VoteDetail] alone (`vote.get`), never in a list, an event, a feed item or a log line.
 */

/** A proposal's state in the vault (§10.19.3). A state this app does not know is [UNKNOWN] and is shown as being checked. */
enum class VoteState(val wire: String) {
    CHECKING("checking"),
    UPCOMING("upcoming"),
    OPEN("open"),
    CLOSED("closed"),
    REJECTED("rejected"),
    UNKNOWN(""),
    ;

    companion object {
        fun of(wire: String?): VoteState = entries.firstOrNull { it.wire == wire && wire != "" } ?: UNKNOWN
    }
}

/** The `ballot.state` of a vote (§10.19.3). */
enum class BallotState(val wire: String) {
    SENDING("sending"),
    WAITING("waiting"),
    RECORDED("recorded"),
    REFUSED("refused"),
    CONFLICT("conflict"),
    DISPUTED("disputed"),
    UNKNOWN(""),
    ;

    companion object {
        fun of(wire: String?): BallotState = entries.firstOrNull { it.wire == wire && wire != "" } ?: UNKNOWN
    }
}

/** The member's ballot as the list shows it; never the choice. [code] is a refusal's code, [reason] a `waiting` ballot's. */
data class VoteBallot(val state: BallotState, val code: String? = null, val reason: String? = null)

/** The `check.state` of the results check (§10.19.6). */
enum class CheckState(val wire: String) {
    PENDING("pending"),
    VERIFIED("verified"),
    UNCONFIRMED("unconfirmed"),
    FAILED("failed"),
    GAVE_UP("gave_up"),
    UNKNOWN(""),
    ;

    companion object {
        fun of(wire: String?): CheckState = entries.firstOrNull { it.wire == wire && wire != "" } ?: UNKNOWN
    }
}

data class VoteCheck(val state: CheckState, val reason: String? = null, val dueAt: Instant? = null)

/** The `site` comparison with the public copy (§10.19.4). */
enum class SiteState(val wire: String) {
    MATCHES("matches"),
    MISMATCH("mismatch"),
    NOT_LISTED("not_listed"),
    UNREACHABLE("unreachable"),
    UNPUBLISHED("unpublished"),
    ;

    companion object {
        fun of(wire: String?): SiteState? = entries.firstOrNull { it.wire == wire }
    }
}

/** One entry of `vote.list` (the summary). The pair [connectionId] and [proposalId] identifies a vote (0.81.2). */
data class VoteSummary(
    val proposalId: String,
    val connectionId: String,
    /** The service's words, cleaned to one line (control and bidirectional characters removed). */
    val title: String,
    val state: VoteState,
    /** A `checking` proposal's reason (`service_list`, `trust_root`, `clock`, `site`, `restore`) or a `rejected` one's. */
    val reason: String? = null,
    val opensAt: Instant? = null,
    val closesAt: Instant? = null,
    val ballot: VoteBallot? = null,
    val check: VoteCheck? = null,
    /** The service also showed a ballot under the member's code that the vault did not sign (0.81.3). */
    val conflictEvidence: Boolean = false,
    /** A restore waits for [VotesRepository.confirm] ("Is this your vote?"): no cast meanwhile. */
    val confirmPending: Boolean = false,
    val canCast: Boolean = false,
    val cannotCast: String? = null,
    val site: SiteState? = null,
) {
    /** The key of a vote in maps and routes: the pair, never the proposal id alone. */
    val key: String get() = VoteKeys.of(connectionId, proposalId)

    val closed: Boolean get() = state == VoteState.CLOSED
}

/** A vote with its text, choices and facts (`vote.get`). */
data class VoteDetail(
    val summary: VoteSummary,
    /** The service's words, cleaned (lines kept); shown as plain text. */
    val text: String,
    val choices: List<VoteChoice>,
    val quorumText: String?,
    val minBallots: Long?,
    val binding: Boolean?,
    /** The vault's tag for the member's row in the results (base64); not secret, needed for the results check. */
    val recordTag: String?,
    /** The member's own choice, once cast: `vote.get` alone has it. Kept out of every list and log. */
    val choiceId: String?,
    val extensions: Int,
    /** The verified results' tally (choice id to count), or null before the check. */
    val tally: Map<String, Long>?,
    val counts: VoteCounts?,
    /** The ballot of the service while a restore asks "Is this your vote?"; its choice id. */
    val serviceBallotChoiceId: String?,
) {
    override fun toString() = "VoteDetail(${summary.key})" // never the text, the choice or the tally in a log line
}

data class VoteChoice(val id: String, val label: String)

data class VoteCounts(val eligible: Long, val ballots: Long, val removed: Long, val unhandled: Long)

/** Proposals the vault dropped for its caps ([count]) from one service; acknowledged with `ack_dropped` once shown. */
data class DroppedNotice(val connectionId: String, val count: Long)

/** A service at its cap of unresolved vote problems ([count]); freed by `vote.release` (§10.19.2). */
data class ProblemsNotice(val connectionId: String, val count: Long)

/** A person connection that VettID's service list names as a service (§10.19.8 "Reconnecting for votes"). */
data class ReconnectEntry(val connectionId: String, val serviceId: String, val name: String)

/** The whole `vote.list` answer. */
data class VoteListing(
    val votes: List<VoteSummary>,
    val dropped: List<DroppedNotice>,
    val problems: List<ProblemsNotice>,
    val reconnect: List<ReconnectEntry>,
    val listVersion: Long?,
    val listSha256: String?,
    val next: String?,
)

object VoteKeys {
    /** The pair as one key. The ids are the vault's (ULIDs and connection ids): `/` cannot occur in either. */
    fun of(connectionId: String, proposalId: String): String = "$connectionId/$proposalId"
}

/** `vote.*` bodies as models. A body that does not read yields no entry: nothing is shown that the app cannot name. */
object VoteWire {
    /** The longest title shown (code points). */
    const val MAX_TITLE = 80

    private fun JsonObject.s(k: String) = VaultJson.str(this, k)

    private fun instant(s: String?): Instant? = try {
        s?.let { Instant.parse(it) }
    } catch (_: DateTimeParseException) {
        null
    }

    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true

    /** A service's title: one line, cleaned, cut at [MAX_TITLE] code points. Empty when nothing shows. */
    fun cleanTitle(s: String?): String = AccountNames.cleanedCapped(s, MAX_TITLE).orEmpty()

    @Suppress("ReturnCount")
    fun summary(o: JsonObject): VoteSummary? {
        val id = o.s("proposal_id")?.takeIf { it.isNotEmpty() } ?: return null
        val conn = o.s("connection_id")?.takeIf { it.isNotEmpty() } ?: return null
        val ballot = (o["ballot"] as? JsonObject)?.let { VoteBallot(BallotState.of(it.s("state")), it.s("code"), it.s("reason")) }
        val check = (o["check"] as? JsonObject)?.let { VoteCheck(CheckState.of(it.s("state")), it.s("reason"), instant(it.s("due_at"))) }
        return VoteSummary(
            proposalId = id,
            connectionId = conn,
            title = cleanTitle(o.s("title")),
            state = VoteState.of(o.s("state")),
            reason = o.s("reason"),
            opensAt = instant(o.s("opens_at")),
            closesAt = instant(o.s("closes_at")),
            ballot = ballot,
            check = check,
            conflictEvidence = o.bool("conflict_evidence"),
            confirmPending = o.bool("confirm_pending"),
            canCast = o.bool("can_cast"),
            cannotCast = o.s("cannot_cast"),
            site = SiteState.of(o.s("site")),
        )
    }

    fun listing(o: JsonObject): VoteListing = VoteListing(
        votes = (o["votes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::summary) },
        dropped = (o["dropped"] as? JsonArray).orEmpty().mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            DroppedNotice(d.s("connection_id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null, d.long("count") ?: 0)
        },
        problems = (o["problems"] as? JsonArray).orEmpty().mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            ProblemsNotice(d.s("connection_id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null, d.long("count") ?: 0)
        },
        reconnect = (o["reconnect"] as? JsonArray).orEmpty().mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            ReconnectEntry(
                d.s("connection_id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                d.s("service_id").orEmpty(),
                AccountNames.cleaned(d.s("name")).orEmpty(),
            )
        },
        listVersion = o.long("list_version"),
        listSha256 = o.s("list_sha256"),
        next = o.s("next"),
    )

    fun detail(o: JsonObject): VoteDetail? {
        val summary = summary(o) ?: return null
        val quorum = o["quorum"] as? JsonObject
        val tally = (o["tally"] as? JsonObject)?.let { t ->
            t.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap()
        }
        val counts = (o["counts"] as? JsonObject)?.let {
            val eligible = it.long("eligible")
            val ballots = it.long("ballots")
            if (eligible == null || ballots == null) {
                null
            } else {
                VoteCounts(eligible, ballots, it.long("removed") ?: 0, it.long("unhandled") ?: 0)
            }
        }
        return VoteDetail(
            summary = summary,
            text = AccountNames.cleanedLines(o.s("text")).orEmpty(),
            choices = (o["choices"] as? JsonArray).orEmpty().mapNotNull { c -> choice(c) },
            quorumText = AccountNames.cleaned(quorum?.s("text")),
            minBallots = quorum?.long("min_ballots"),
            binding = (o["binding"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
            recordTag = o.s("record_tag"),
            choiceId = o.s("choice_id"),
            extensions = o.long("extensions")?.toInt() ?: 0,
            tally = tally,
            counts = counts,
            serviceBallotChoiceId = (o["service_ballot"] as? JsonObject)?.s("choice_id"),
        )
    }

    @Suppress("ReturnCount")
    private fun choice(e: JsonElement): VoteChoice? {
        val c = e as? JsonObject ?: return null
        val id = c.s("id")?.takeIf { it.isNotEmpty() } ?: return null
        // The label as sent, in the order sent; cleaned like any received text. A label that shows nothing is its id.
        return VoteChoice(id, AccountNames.cleaned(c.s("label")) ?: id)
    }
}
