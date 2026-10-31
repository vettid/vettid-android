package com.vettid.feature.history

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.ui.format.LocalTimeScale
import com.vettid.core.ui.format.scaledDurationText

/** The names an entry's title may show (ANDROID-PLAN 0.1.52 §4 "Event titles"), as History resolves them for the row. */
data class EntryNames(
    val connection: String? = null,
    val device: String? = null,
    val item: String? = null,
    /** The title of the entry's vote, from the Votes list in memory (ANDROID-PLAN 0.1.102 "Votes"); null when not known. */
    val vote: String? = null,
)

/** The member-facing title of an audit entry ([AuditKinds]), in Compose and from a [Context] (search, export). */
@Suppress("SpreadOperator")
object HistoryText {
    /** [isolate] false for a file (an export): the names are cleaned but carry no bidi controls at all. */
    fun title(
        context: Context,
        kind: String,
        ref: String? = null,
        names: EntryNames = EntryNames(),
        isolate: Boolean = true,
    ): String =
        when (val t = AuditKinds.title(kind, ref)) {
            is AuditKinds.Title.Known -> context.getString(t.res)
            is AuditKinds.Title.Summary -> context.getString(R.string.history_kind_summary, context.getString(t.res))
            is AuditKinds.Title.Dropped -> context.getString(R.string.history_kind_dropped, t.reason)
            is AuditKinds.Title.Unknown -> context.getString(R.string.history_kind_unknown, t.kind)
            is AuditKinds.Title.Counted -> context.getString(t.res, t.n, t.of)
            is AuditKinds.Title.Named -> named(context, t, names, isolate)
            is AuditKinds.Title.Vote -> vote(t, names, isolate) { res, args -> context.getString(res, *args) }
        }

    @Composable
    fun title(kind: String, ref: String? = null, names: EntryNames = EntryNames()): String = when (val t = AuditKinds.title(kind, ref)) {
        is AuditKinds.Title.Known -> stringResource(t.res)
        is AuditKinds.Title.Summary -> stringResource(R.string.history_kind_summary, stringResource(t.res))
        is AuditKinds.Title.Dropped -> stringResource(R.string.history_kind_dropped, t.reason)
        is AuditKinds.Title.Unknown -> stringResource(R.string.history_kind_unknown, t.kind)
        is AuditKinds.Title.Counted -> stringResource(t.res, t.n, t.of)
        is AuditKinds.Title.Named -> {
            val name = when (t.who) {
                AuditKinds.Who.CONNECTION -> names.connection
                AuditKinds.Who.DEVICE -> names.device
                AuditKinds.Who.ITEM -> names.item
            }
            val clean = AccountNames.isolateOrNull(name)
            if (clean != null) stringResource(t.res, clean) else stringResource(t.anyRes)
        }
        is AuditKinds.Title.Vote -> {
            val (hasTitle, args) = voteArgs(
                t,
                names,
                isolate = true,
                aService = stringResource(R.string.history_vote_a_service),
                reason = t.reason?.let { stringResource(it) },
            )
            stringResource(if (hasTitle) t.res else t.anyRes, *args)
        }
    }

    /**
     * A title with the vote's name in curly quotes and, for some, the service and the reason of a refusal. Without the
     * vote's title (the Votes list is not in memory, or the vote is gone) the title says "a vote" by dropping the quoted
     * part; without the service's name it says "A service".
     */
    private fun vote(t: AuditKinds.Title.Vote, names: EntryNames, isolate: Boolean, get: (Int, Array<Any>) -> String): String {
        val reason = t.reason?.let { get(it, emptyArray()) }
        val (hasTitle, args) = voteArgs(t, names, isolate, get(R.string.history_vote_a_service, emptyArray()), reason)
        return get(if (hasTitle) t.res else t.anyRes, args)
    }

    /** Whether the vote's title is known, and the arguments the text takes: the service, the title, the reason. */
    private fun voteArgs(
        t: AuditKinds.Title.Vote,
        names: EntryNames,
        isolate: Boolean,
        aService: String,
        reason: String?,
    ): Pair<Boolean, Array<Any>> {
        val clean = { s: String? -> if (isolate) AccountNames.isolateOrNull(s) else AccountNames.cleaned(s) }
        val args = ArrayList<Any>()
        if (t.service) args += clean(names.connection) ?: aService
        val title = clean(names.vote)
        if (title != null) args += title
        if (reason != null) args += reason
        return (title != null) to args.toTypedArray()
    }

    /** Without the name (gone or not known), the title drops it. */
    private fun named(context: Context, t: AuditKinds.Title.Named, names: EntryNames, isolate: Boolean): String {
        val name = when (t.who) {
            AuditKinds.Who.CONNECTION -> names.connection
            AuditKinds.Who.DEVICE -> names.device
            AuditKinds.Who.ITEM -> names.item
        }
        val clean = if (isolate) AccountNames.isolateOrNull(name) else AccountNames.cleaned(name)
        return if (clean != null) context.getString(t.res, clean) else context.getString(t.anyRes)
    }

    /**
     * An entry's detail lines (ANDROID-PLAN 0.1.52 §4 "Event titles"). A duration the vault's time scale divides is said
     * in real time at scale 1, else as the vault runs it.
     */
    @Composable
    fun details(kind: String, ref: String?, outcome: AuditKinds.Outcome, connectionName: String? = null): List<String> {
        val scale = LocalTimeScale.current.scale
        return AuditKinds.details(kind, ref, outcome).mapNotNull { d ->
            when (d) {
                is AuditKinds.Detail.Line -> stringResource(d.res)
                is AuditKinds.Detail.Scaled ->
                    if (scale <= 1) stringResource(d.res) else stringResource(d.scaledRes, scaledDurationText(d.realSeconds))
                is AuditKinds.Detail.Named -> AccountNames.isolateOrNull(d.name)?.let { stringResource(d.res, it) }
                is AuditKinds.Detail.Connection -> AccountNames.isolateOrNull(connectionName)?.let { stringResource(d.res, it) }
            }
        }
    }
}

/** The signer entries' second line (VAULT-MESSAGING 0.27.0 §11.7.1), in the words of ANDROID-PLAN §7. */
object HistorySigner {
    fun text(context: Context, d: AuditKinds.SignerDetail): String = when (d) {
        is AuditKinds.SignerDetail.Bound -> context.getString(FeedKinds.signerLabel(d.signerClass)).replaceFirstChar { it.uppercase() }
        is AuditKinds.SignerDetail.Changed -> context.getString(
            com.vettid.core.data.R.string.data_feed_signer_from_to,
            context.getString(FeedKinds.signerLabel(d.from)), context.getString(FeedKinds.signerLabel(d.to)),
        )
        is AuditKinds.SignerDetail.Refused ->
            context.getString(if (d.unbound) R.string.history_signer_refused_unbound else R.string.history_signer_refused_unpinned)
    }
}

/**
 * The device of an audit entry (VAULT-MESSAGING 0.23.2, ANDROID-PLAN 0.1.27): its name from `device.list`, as the
 * export resolves it. Kept pure for tests.
 */
object HistoryDevice {
    /**
     * The name to show for [deviceId]: null for an entry without a device, or while the device list is not known
     * ([names] null: nothing rather than calling every device removed) or a listed device without a name; [removed]
     * for a device the list no longer holds.
     */
    fun name(deviceId: String?, names: Map<String, String>?, removed: String): String? =
        if (deviceId == null || names == null) null else names[deviceId].let { if (it == null) removed else it.ifEmpty { null } }

    /** A row's second line: the item, connection and device names where the entry has them, else [fallback]. */
    fun supporting(parts: List<String?>, fallback: String): String = parts.filterNotNull().joinToString(" · ").ifEmpty { fallback }
}
