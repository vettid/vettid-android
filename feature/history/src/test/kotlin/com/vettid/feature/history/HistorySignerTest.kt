package com.vettid.feature.history

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * History's texts for the signer entries (VAULT-MESSAGING 0.27.0 §10.9, §11.7.1): plain words from the classes
 * of the `ref`, in the wording of ANDROID-PLAN 0.1.36 §7; never an unknown kind.
 */
@RunWith(RobolectricTestRunner::class)
class HistorySignerTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun detail(kind: String, ref: String) = HistorySigner.text(context, AuditKinds.signerDetail(kind, ref)!!)

    @Test
    fun theTitlesArePlainText() {
        assertEquals("Version of VettID recorded for this vault", HistoryText.title(context, "device.signer_bound"))
        assertEquals("Vault moved to another version of VettID", HistoryText.title(context, "device.signer_changed"))
        assertEquals("An installation of VettID was refused", HistoryText.title(context, "device.signer_refused"))
    }

    @Test
    fun theRefIsReadInWords() {
        assertEquals("F-Droid or direct (signed by VettID)", detail("device.signer_bound", "vettid"))
        assertEquals("Google Play (signed by Google)", detail("device.signer_bound", "google_play"))
        assertEquals("A development build", detail("device.signer_bound", "development"))
        assertEquals(
            "From F-Droid or direct (signed by VettID) to Google Play (signed by Google)",
            detail("device.signer_changed", "vettid:google_play"),
        )
        assertEquals(
            "From a development build to F-Droid or direct (signed by VettID)",
            detail("device.signer_changed", "development:vettid"),
        )
        assertEquals("From another signer to Google Play (signed by Google)", detail("device.signer_changed", "unknown:google_play"))
        assertEquals("This vault release does not accept its signer", detail("device.signer_refused", "unpinned"))
        assertEquals("No signer was recorded for it", detail("device.signer_refused", "unbound"))
    }
}
