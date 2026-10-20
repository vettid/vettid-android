package com.vettid.feature.history

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.ui.format.LocalTimeScale
import com.vettid.core.ui.format.scaledDurationText

/** The names an entry's title may show (ANDROID-PLAN 0.1.52 §4 "Event titles"), as History resolves them for the row. */
data class EntryNames(val connection: String? = null, val device: String? = null, val item: String? = null)

/** The member-facing title of an audit entry ([AuditKinds]), in Compose and from a [Context] (search, export). */
object HistoryText {
    fun title(context: Context, kind: String, ref: String? = null, names: EntryNames = EntryNames()): String =
        when (val t = AuditKinds.title(kind, ref)) {
            is AuditKinds.Title.Known -> context.getString(t.res)
            is AuditKinds.Title.Summary -> context.getString(R.string.history_kind_summary, context.getString(t.res))
            is AuditKinds.Title.Dropped -> context.getString(R.string.history_kind_dropped, t.reason)
            is AuditKinds.Title.Unknown -> context.getString(R.string.history_kind_unknown, t.kind)
            is AuditKinds.Title.Counted -> context.getString(t.res, t.n, t.of)
            is AuditKinds.Title.Named -> named(context, t, names)
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
            if (name != null) stringResource(t.res, name) else stringResource(t.anyRes)
        }
    }

    /** Without the name (gone or not known), the title drops it. */
    private fun named(context: Context, t: AuditKinds.Title.Named, names: EntryNames): String {
        val name = when (t.who) {
            AuditKinds.Who.CONNECTION -> names.connection
            AuditKinds.Who.DEVICE -> names.device
            AuditKinds.Who.ITEM -> names.item
        }
        return if (name != null) context.getString(t.res, name) else context.getString(t.anyRes)
    }

    /**
     * An entry's detail lines (ANDROID-PLAN 0.1.52 §4 "Event titles"). A duration the vault's time scale divides is said
     * in real time at scale 1, else as the vault runs it.
     */
    @Composable
    fun details(kind: String, ref: String?, outcome: AuditKinds.Outcome): List<String> {
        val scale = LocalTimeScale.current.scale
        return AuditKinds.details(kind, ref, outcome).map { d ->
            when (d) {
                is AuditKinds.Detail.Line -> stringResource(d.res)
                is AuditKinds.Detail.Scaled ->
                    if (scale <= 1) stringResource(d.res) else stringResource(d.scaledRes, scaledDurationText(d.realSeconds))
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
     * ([names] null: nothing rather than calling every device removed); [removed] for a device the list no longer
     * holds.
     */
    fun name(deviceId: String?, names: Map<String, String>?, removed: String): String? =
        if (deviceId == null || names == null) null else names[deviceId] ?: removed

    /** A row's second line: the item, connection and device names where the entry has them, else [fallback]. */
    fun supporting(parts: List<String?>, fallback: String): String = parts.filterNotNull().joinToString(" · ").ifEmpty { fallback }
}
