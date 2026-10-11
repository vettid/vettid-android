// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.vault

import com.vettid.core.vault.VaultJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The vault's signer binding as the app reads it (VAULT-MESSAGING 0.27.0 §10.3, §11.7.1; ANDROID-PLAN 0.1.36 §7). */
class SignerTest {
    private val digest = "31a19613aa10f209e08945f947f94f7ce3e6e5ac342457ff9969a67986928e65"

    private fun list(appExtra: String) = VaultJson.parseObject(
        """{"devices":[{"id":"d-desktop","kind":"desktop","state":"active","name":"Laptop","ik":"x"},{"id":"d-app","kind":"app","state":"active","name":"Pixel","ik":"y"$appExtra}]}""".toByteArray(),
    )

    @Test
    fun theSignerIsReadFromThisAppsEntry() {
        for ((wire, cls) in listOf("vettid" to SignerClass.VETTID, "google_play" to SignerClass.GOOGLE_PLAY, "development" to SignerClass.DEVELOPMENT)) {
            val l = list(""","signer":{"class":"$wire","digest":"$digest"}""")
            assertEquals(SignerWire.Own.Bound(SignerView(cls, digest)), SignerWire.own(l, "d-app"))
            assertEquals("the one app entry, when the id is not known", SignerWire.Own.Bound(SignerView(cls, digest)), SignerWire.own(l, null))
            assertEquals("a desktop's entry has none", SignerWire.Own.Unbound, SignerWire.own(l, "d-desktop"))
        }
    }

    /** iOS, an unbound vault, a vault release before 0.27.0: no member, and the unlocks keep sending the chain. */
    @Test
    fun anEntryWithoutSignerIsUnbound() {
        assertEquals(SignerWire.Own.Unbound, SignerWire.own(list(""), "d-app"))
        assertEquals(SignerWire.Own.Unbound, SignerWire.own(list(""","push":{"platform":"fcm","app_id":"com.vettid.app.staging","state":"active"}"""), "d-app"))
    }

    /** Nothing is learnt from an answer that does not hold this app's entry: the stop condition does not move. */
    @Test
    fun anAnswerWithoutThisAppsEntryTeachesNothing() {
        assertEquals(SignerWire.Own.NoEntry, SignerWire.own(list(""), "d-other"))
        assertEquals(SignerWire.Own.NoEntry, SignerWire.own(VaultJson.parseObject("{}".toByteArray()), "d-app"))
        assertEquals(SignerWire.Own.NoEntry, SignerWire.own(VaultJson.parseObject("""{"devices":[]}""".toByteArray()), null))
    }

    /** A signer this version cannot name is still a recorded signer (no chain any more), with no line to show. */
    @Test
    fun aSignerThisVersionCannotNameIsBoundWithoutALine() {
        assertEquals(SignerWire.Own.BoundUnknown, SignerWire.own(list(""","signer":{"class":"app_gallery","digest":"$digest"}"""), "d-app"))
        assertEquals(SignerWire.Own.BoundUnknown, SignerWire.own(list(""","signer":{"class":"vettid","digest":"${digest.uppercase()}"}"""), "d-app"))
        assertEquals(SignerWire.Own.BoundUnknown, SignerWire.own(list(""","signer":{"class":"vettid","digest":"abcd"}"""), "d-app"))
        assertEquals(SignerWire.Own.BoundUnknown, SignerWire.own(list(""","signer":{"class":"vettid"}"""), "d-app"))
        assertEquals(SignerWire.Own.BoundUnknown, SignerWire.own(list(""","signer":"vettid""""), "d-app"))
    }

    @Test
    fun theClassesAreTheSpecsIdentifiers() {
        assertEquals(listOf("vettid", "google_play", "development"), SignerClass.entries.map { it.wire })
        assertNull(SignerClass.of("unknown"))
        assertNull(SignerClass.of(null))
        assertNull(SignerClass.of("VETTID"))
    }

    /** `signer_unconfirmed` and an absent feature's `unsupported_type` are plain refusals: no retry is implied by their kind. */
    @Test
    fun theNewCodesMapToPlainFailures() {
        assertEquals(FailureKind.OTHER, VaultManager.opFailure(SignerWire.CODE_SIGNER_UNCONFIRMED))
        assertEquals(FailureKind.NOT_SUPPORTED, VaultManager.opFailure("unsupported_type"))
        assertEquals("signer_refused", SignerWire.CODE_SIGNER_REFUSED)
    }
}
