package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rule 3 (the release gate): how [VaultManager] wires it. [VaultManager] has no unit test harness, so its call sites are
 * read from the source (as `TransferInDeadlineCallTest` does); the rules themselves are tested in `ReleaseGateTest`.
 */
class ReleaseGateWiringTest {
    /** The source without comments, one trimmed line each. */
    private val code: List<String> = File("src/main/kotlin/com/vettid/core/data/vault/VaultManager.kt").readLines()
        .map { it.substringBefore("//").trim() }
        .filterNot { it.isEmpty() || it.startsWith("*") || it.startsWith("/*") }

    /** The lines from the one starting with [start] to the end of its braces. */
    private fun block(start: String): List<String> {
        val i = code.indexOfFirst { it.startsWith(start) }
        assertTrue("found: $start", i >= 0)
        var depth = 0
        var opened = false
        for (j in i until code.size) {
            for (c in code[j]) {
                if (c == '{' || c == '(') {
                    depth++
                    opened = true
                }
                if (c == '}' || c == ')') depth--
            }
            if (opened && depth <= 0) return code.subList(i, j + 1)
        }
        error("unclosed: $start")
    }

    private fun List<String>.has(line: String) = assertTrue("has: $line", any { it == line })

    @Test
    fun everyOpeningStartsTheCheckBeforeItShows() {
        code.has("private val phaseFlow = PhaseHolder(AppPhase.Starting, onOpen = { releaseUpdate.vaultOpened() })")
    }

    @Test
    fun aLockEndsTheCheckAndTheWaitingReads() {
        val clear = block("clearReleaseCheck = {")
        clear.has("phaseFlow.unlessOpen { releaseUpdate.vaultClosed() }")
        clear.has("releasePause.reset()")
    }

    @Test
    fun aWipeAndBothDeletionsClearTheCheck() {
        val wipe = block("private suspend fun eraseAfterMark()")
        val at = wipe.indexOf("steps.step { releaseUpdate.clear() }")
        assertTrue("the wipe's first step", at >= 0 && at < wipe.indexOf("steps.step { forgetDevices() }"))
        // Each deletion: the update closed, then the check cleared, right after.
        val closes = code.indices.filter { code[it] == "releaseUpdate.close()" }
        assertEquals(2, closes.size)
        closes.forEach { assertEquals("releaseUpdate.clear()", code[it + 1]) }
        assertEquals(3, code.count { it.contains("releaseUpdate.clear()") })
    }

    @Test
    fun theFeaturesAreGatedByThePause() {
        code.has(
            "fun featuresGated(): Boolean = featuresGated(ownerCheck.ownerCheck.value?.gated(Instant.now()) == true, " +
                "releaseUpdate.paused.value)",
        )
        block("val feed = FeedManager(").has("gated = { featuresGated() },")
        block("val presence = PresenceManager(").has("closed = { phaseFlow.value != AppPhase.Unlocked || featuresGated() },")
        block("val location = LocationSharing(").has("due = { featuresGated() },")
    }

    @Test
    fun eventsAreRoutedByThePause() {
        block("private suspend fun takeEvent(").has("takeIntoFeatures(m, releaseUpdate.paused.value)")
        val route = block("private suspend fun takeIntoFeatures(")
        assertEquals(EventTaker.entries.size, route.count { it.startsWith("if (takes(EventTaker.") })
    }

    @Test
    fun theOpeningsReadsAndTheChecksStepsWaitWhilePaused() {
        code.has("openedReads(releaseUpdate.paused.value) { gatedReads() }")
        code.has("onPassed = { if (!releaseUpdate.paused.value) social.refreshAllQuietly() },")
        code.has("if (!releaseUpdate.paused.value) steps.step { votes.startSync() }")
        code.has("if (!releaseUpdate.paused.value) steps.step { social.refreshAllQuietly() }")
        code.has("if (gatedEnded) steps.step { releasePause.ownerCheckLifted() }")
        val hold = block("private suspend fun afterHoldPassed(")
        assertEquals("if (releaseUpdate.paused.value) {", hold[1])
        code.has(
            "afterCredentialReset(releaseUpdate.paused.value, { refreshAlarm(s) }, { ownerCheck.onOpened() }, " +
                "{ items.refreshQuietly() })",
        )
    }

    @Test
    fun thePauseIsFollowed() {
        code.has("releaseUpdate.onPausedChange = { paused -> releasePause.onPaused(paused) }")
        val pause = block("private val releasePause = ReleasePauseSteps(")
        pause.has("unlocked = { phaseFlow.value == AppPhase.Unlocked && !phaseFlow.deleting },")
        pause.has("checkGated = { ownerCheck.ownerCheck.value?.gated(Instant.now()) == true },")
        pause.has("paused = { releaseUpdate.paused.value },")
        pause.has("stopVotes = { votes.stopSync() },")
        pause.has("gatedReads()")
        pause.has("sharing.invalidateTags()")
        val reads = block("private fun gatedReads()")
        listOf("social.refreshAllQuietly()", "items.refreshQuietly()", "votes.startSync()", "feed.open()").forEach { reads.has(it) }
        assertTrue(reads.any { it.contains("attachments.probe()") })
    }

    @Test
    fun theManifestIsReadFailingClosed() {
        val offer = block("override suspend fun offer(): ReleaseUpdateOffer? = guard {")
        offer.has("ReleaseGateOffer.checkNumber(st.releaseNumber)")
        offer.has("ReleaseGateOffer.of(st.release, st.releaseNumber, m.releases, m::byPcr0)")
        code.has("override fun releaseNumber(): Long? = session?.device?.altState?.releaseNumber")
    }
}
