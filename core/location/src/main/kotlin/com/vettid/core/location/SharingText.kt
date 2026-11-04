package com.vettid.core.location

import android.content.Context
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.location.ServiceState
import com.vettid.core.data.location.textRes
import com.vettid.core.data.location.ShareTime
import java.time.Instant

/**
 * The words of the ongoing notification (ANDROID-PLAN §4 "Location", Texts): "Sharing your location with {First} until
 * {time}", "with {n} people", "…again, until {time}." after a resume, "Paused until your daily check." while the check is due
 * or held. [names] holds the first names of the connections (cleaned) that may be said now; it is empty while the app lock's
 * cover is up, and then no name is said.
 */
internal object SharingText {
    fun title(context: Context, state: ServiceState, names: Map<String, String>, now: Instant = Instant.now()): String {
        val targets = state.targets
        val first = targets.singleOrNull()?.let { t -> names[t.connectionId]?.let { AccountNames.isolate(it) } }
        return when {
            state.paused -> context.getString(com.vettid.core.data.R.string.data_location_paused)
            state.issue.textRes() != null -> context.getString(state.issue.textRes()!!)
            targets.size > 1 -> context.getString(R.string.core_location_notification_many, targets.size)
            targets.size == 1 && first != null -> {
                val until = ShareTime.format(context, targets.single().expiresAt, now)
                context.getString(
                    if (state.resumed) R.string.core_location_notification_resumed else R.string.core_location_notification_one,
                    first,
                    until,
                )
            }
            else -> context.getString(R.string.core_location_notification_unnamed)
        }
    }
}
