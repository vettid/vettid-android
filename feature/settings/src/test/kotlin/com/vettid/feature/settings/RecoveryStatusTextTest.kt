// Expected answers stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.feature.settings

import com.vettid.core.data.vault.RecoveryStatusView
import org.junit.Assert.assertEquals
import org.junit.Test

/** ANDROID-PLAN 0.1.40: the Recovery line, never "0 of 10" when the counter could not be read. */
class RecoveryStatusTextTest {
    @Test
    fun everyState() {
        assertEquals(StatusText(R.string.settings_recovery_status_armed, 0), recoveryStatusText(RecoveryStatusView("armed", 0, false)))
        assertEquals(StatusText(R.string.settings_recovery_status_used, 3), recoveryStatusText(RecoveryStatusView("armed", 3, false)))
        assertEquals(StatusText(R.string.settings_recovery_status_exhausted), recoveryStatusText(RecoveryStatusView("change_required", 10, false)))
        assertEquals(StatusText(R.string.settings_recovery_status_needs_check), recoveryStatusText(RecoveryStatusView("needs_check", null, false)))
        assertEquals(StatusText(R.string.settings_recovery_status_off), recoveryStatusText(RecoveryStatusView("off", null, false)))
        assertEquals(StatusText(R.string.settings_recovery_status_unknown), recoveryStatusText(RecoveryStatusView("unknown", 0, true)))
        assertEquals(StatusText(R.string.settings_recovery_status_unknown), recoveryStatusText(RecoveryStatusView("armed", null, true)))
    }
}
