package com.vettid.feature.history

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** The member-facing title of an audit kind ([AuditKinds]), in Compose and from a [Context] (search). */
object HistoryText {
    fun title(context: Context, kind: String): String = when (val t = AuditKinds.title(kind)) {
        is AuditKinds.Title.Known -> context.getString(t.res)
        is AuditKinds.Title.Summary -> context.getString(R.string.history_kind_summary, context.getString(t.res))
        is AuditKinds.Title.Dropped -> context.getString(R.string.history_kind_dropped, t.reason)
        is AuditKinds.Title.Unknown -> context.getString(R.string.history_kind_unknown, t.kind)
    }

    @Composable
    fun title(kind: String): String = when (val t = AuditKinds.title(kind)) {
        is AuditKinds.Title.Known -> stringResource(t.res)
        is AuditKinds.Title.Summary -> stringResource(R.string.history_kind_summary, stringResource(t.res))
        is AuditKinds.Title.Dropped -> stringResource(R.string.history_kind_dropped, t.reason)
        is AuditKinds.Title.Unknown -> stringResource(R.string.history_kind_unknown, t.kind)
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
