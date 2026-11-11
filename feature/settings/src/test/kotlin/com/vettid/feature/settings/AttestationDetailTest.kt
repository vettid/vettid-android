package com.vettid.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.attestation.EnclaveCheck
import com.vettid.core.attestation.EnclaveRecord
import com.vettid.core.attestation.EnclaveRefusal
import com.vettid.core.attestation.manifest.ReleaseStatus
import com.vettid.core.attestation.nitro.NitroRoot
import com.vettid.core.data.vault.AttestationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * ANDROID-PLAN 0.1.124 §4 "Attestation in detail", its tests: "Passed" only with a recorded pass; a failure at each check
 * shows it "Failed" and the later ones "Not checked"; a refusal after line 9 shows every line "Passed" and its reason; a
 * failed manifest shows only line 1 "Failed", without the key id and serial; values resting on a failed check say "Not
 * verified"; no record is the empty state; the fingerprint is the pinned root's; "Copy all" holds the three sections and
 * nothing of the phone or the account.
 */
@RunWith(RobolectricTestRunner::class)
class AttestationDetailTest {
    @get:Rule
    val rule = createComposeRule()

    private val at = Instant.parse("2026-11-11T12:00:00Z")
    private val info = AttestationInfo(
        "production", true, "STRONG_BOX", true, 400, "Verified", true, "4e8e e8f7 1c2d 3e4f", 4, "0101 0101",
        appVersion = "1.2.3 (45)", releaseTagPrefix = "release/prod/",
    )
    private val passed = EnclaveRecord(
        at, EnclaveRecord.results(9, failed = false), "0123456789abcdef", 7, "ab".repeat(48), "11".repeat(48), "22".repeat(48),
        at.minusSeconds(60), at.plusSeconds(86_000), 4, ReleaseStatus.ACTIVE, null,
    )
    private val copied = mutableListOf<Pair<String, String>>()

    private fun show(r: EnclaveRecord?) = rule.setContent {
        AttestationContent(LoadState(false, info), onBack = {}, record = r, copy = { l, t -> copied += l to t })
    }

    private fun line(c: EnclaveCheck, state: String) = rule.onNodeWithContentDescription(
        "${CHECKS.getValue(c)}: $state",
    )

    @Test
    fun aPassShowsEveryCheckPassedAndTheValues() {
        show(passed)
        for (c in EnclaveCheck.entries) line(c, "Passed").performScrollTo()
        rule.onAllNodesWithText("Not verified").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        rule.onNodeWithText("Release 4 · Active").performScrollTo()
        rule.onNodeWithText("Key 0123456789abcdef · serial 7").performScrollTo()
        rule.onNodeWithText(NitroRoot.SHA256_HEX, substring = true).performScrollTo()
        rule.onNodeWithTag("attestation_source").performScrollTo()
        rule.onNodeWithText("release/prod/4").performScrollTo()
        rule.onNodeWithTag("attestation_failed").assertDoesNotExist()
    }

    @Test
    fun aFailureAtEachCheckIsFailedAndTheLaterOnesNotChecked() {
        var r by androidx.compose.runtime.mutableStateOf(passed)
        rule.setContent { AttestationContent(LoadState(false, info), onBack = {}, record = r) }
        for (failed in EnclaveCheck.entries) {
            r = passed.copy(results = EnclaveRecord.results(failed.ordinal, failed = true))
            rule.waitForIdle()
            for (c in EnclaveCheck.entries) {
                val want = when {
                    c.ordinal < failed.ordinal -> "Passed"
                    c == failed -> "Failed"
                    else -> "Not checked"
                }
                line(c, want).assertExists()
            }
            rule.onNodeWithTag("attestation_failed").assertExists()
            rule.onNodeWithText("The check that failed is marked below.", substring = true).assertExists()
            // The release rests on line 9.
            if (failed != EnclaveCheck.entries.last()) rule.onNodeWithTag("attestation_release").assertDoesNotExist()
        }
    }

    @Test
    fun aRefusalAfterLineNineShowsEveryCheckPassedAndItsReason() {
        show(passed.copy(refusal = EnclaveRefusal.OLDER_RELEASE))
        for (c in EnclaveCheck.entries) line(c, "Passed").assertExists()
        rule.onNodeWithText("Every check passed, but this phone refused the release:", substring = true).assertExists()
        rule.onNodeWithText(
            "Every check passed, but this phone refused the release: it's older than a release this phone has already used.",
        ).assertExists()
    }

    /** Each refusal's reason, word for word (owner-approved, GC #8641 a). */
    @Test
    fun eachRefusalHasItsOwnReason() {
        var r by androidx.compose.runtime.mutableStateOf(passed)
        rule.setContent { AttestationContent(LoadState(false, info), onBack = {}, record = r) }
        for ((why, text) in mapOf(
            EnclaveRefusal.NOT_ACTIVE to "it isn't offered to new phones.",
            EnclaveRefusal.OLDER_RELEASE to "it's older than a release this phone has already used.",
            EnclaveRefusal.OTHER_INSTANCE to "your vault's address named a different enclave.",
            EnclaveRefusal.TIME_SCALE to "its time setting is for test builds, not this app.",
        )) {
            r = passed.copy(refusal = why)
            rule.waitForIdle()
            rule.onNodeWithText("Every check passed, but this phone refused the release: $text").assertExists()
        }
    }

    @Test
    fun aRefusedManifestShowsOnlyLineOneFailedWithoutItsKey() {
        show(EnclaveRecord.manifestFailed(at))
        line(EnclaveCheck.MANIFEST, "Failed").assertExists()
        for (c in EnclaveCheck.entries.drop(1)) line(c, "Not checked").assertExists()
        rule.onNodeWithText("serial", substring = true).assertDoesNotExist()
        // The Enclave section shows only the environment.
        rule.onNodeWithText("production").assertExists()
        val absent = listOf(
            "attestation_PCR0", "attestation_attested_at", "attestation_valid_until", "attestation_release", "attestation_source",
        )
        for (t in absent) {
            rule.onNodeWithTag(t).assertDoesNotExist()
        }
    }

    @Test
    fun valuesRestingOnAFailedCheckSayNotVerified() {
        // Line 4 failed: the PCRs and the time stand (lines 2 and 3 passed); Valid until rests on line 4.
        show(passed.copy(results = EnclaveRecord.results(3, failed = true), releaseNumber = null, releaseStatus = null))
        rule.onNodeWithTag("attestation_valid_until").performScrollTo()
        rule.onNodeWithText("· Not verified", substring = true).assertExists()
        assertEquals(1, rule.onAllNodesWithText("Not verified", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun noRecordIsTheEmptyStateWithThisPhoneOnly() {
        show(null)
        rule.onNodeWithTag("attestation_none").assertExists()
        rule.onNodeWithText("This phone hasn't verified your vault's enclave yet. It does at the next unlock.").assertExists()
        rule.onNodeWithTag("attestation_phone").assertExists()
        rule.onNodeWithTag("attestation_copy_all").assertDoesNotExist()
        rule.onNodeWithText("Checks", substring = true).assertDoesNotExist()
    }

    @Test
    fun copyAllHoldsTheThreeSectionsAndNothingOfThePhone() {
        show(passed)
        rule.onNodeWithTag("attestation_copy_all").performScrollTo().performClick()
        val (label, text) = copied.single()
        assertEquals("Copy all", label)
        assertEquals(text, AttestationDetail.copyAll(passed, "production", "release/prod/"))
        assertTrue(text.startsWith("Enclave\n"))
        assertTrue("\nChecks\n" in text)
        assertTrue("\nRelease\n" in text)
        for (c in EnclaveCheck.entries) assertTrue(c.name, "${CHECKS.getValue(c)}: Passed" in text)
        assertTrue("the manifest key id is copied", "Key 0123456789abcdef · serial 7" in text)
        assertTrue(NitroRoot.SHA256_HEX in text)
        assertTrue("ab".repeat(48) in text)
        assertTrue("2026-11-11T11:59:00Z" in text)
        assertTrue("${AttestationDetail.REPOSITORY}/releases/tag/release/prod/4" in text)
        // Nothing of the phone, the check's time, the account.
        val ofThePhone = listOf(
            "This phone", "STRONG_BOX", "Verified boot", "4e8e", "1.2.3", "App version", "as of", "2026-11-11T12:00:00Z",
        )
        for (phone in ofThePhone) {
            assertFalse(phone, phone in text)
        }
        rule.onNodeWithText("Copied").assertExists()
    }

    @Test
    fun aPcrIsCopiedInFull() {
        show(passed)
        rule.onNodeWithTag("attestation_PCR0").performScrollTo()
        rule.onAllNodesWithText("Copy")[0].performClick()
        assertEquals("PCR0" to "ab".repeat(48), copied.single())
    }

    private companion object {
        val CHECKS = mapOf(
            EnclaveCheck.MANIFEST to "Release list signed by VettID",
            EnclaveCheck.CHAIN to "Certificate chain to the AWS Nitro root",
            EnclaveCheck.SIGNATURE to "Signed by AWS Nitro hardware",
            EnclaveCheck.KEY to "Covers the key this phone encrypts to",
            EnclaveCheck.RECENT to "Recent",
            EnclaveCheck.NOT_DEBUG to "Not a debug enclave",
            EnclaveCheck.OWN_RELEASE to "Names its own release",
            EnclaveCheck.VALIDITY to "Within its validity",
            EnclaveCheck.MATCH to "Measurements match a signed release",
        )
    }
}
