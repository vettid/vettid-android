package com.vettid.feature.settings

import androidx.annotation.StringRes
import com.vettid.core.data.vault.RecoveryStatusView

/** A line of the status screen: a string and its argument, if any. */
data class StatusText(@StringRes val res: Int, val arg: Int? = null)

/**
 * The Recovery line of Settings → Vault status from `vault.status` (ANDROID-PLAN 0.1.40 "Owner check and status"):
 * on with the attempts used, the used-attempts warning, all ten used, set up at the next check, off, a new password
 * needed, and "cannot be checked right now" for `unknown`, never "0 of 10".
 */
fun recoveryStatusText(r: RecoveryStatusView): StatusText {
    val used = r.used
    return when (r.state) {
        "armed" -> when {
            used == null -> StatusText(R.string.settings_recovery_status_unknown)
            used >= ATTEMPTS -> StatusText(R.string.settings_recovery_status_exhausted)
            used > 0 -> StatusText(R.string.settings_recovery_status_used, used)
            else -> StatusText(R.string.settings_recovery_status_armed, 0)
        }
        "change_required" -> if (used != null && used >= ATTEMPTS) {
            StatusText(R.string.settings_recovery_status_exhausted)
        } else {
            StatusText(R.string.settings_recovery_status_change_required)
        }
        "needs_check" -> StatusText(R.string.settings_recovery_status_needs_check)
        "off" -> StatusText(R.string.settings_recovery_status_off)
        else -> StatusText(R.string.settings_recovery_status_unknown)
    }
}

private const val ATTEMPTS = 10
