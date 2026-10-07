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
