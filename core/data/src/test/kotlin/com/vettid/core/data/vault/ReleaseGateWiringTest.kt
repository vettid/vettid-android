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
        clear.has("heldAskReplay.drop()")
    }

    @Test
    fun aWipeAndBothDeletionsClearTheCheck() {
        val wipe = block("private suspend fun eraseAfterMark()")
        val at = wipe.indexOf("steps.step { releaseUpdate.clear() }")
        val mailbox = wipe.indexOfFirst { it.contains("deleteMailbox()") }
        assertTrue("the wipe's first step, before the mailbox goes", at in 0 until mailbox)
        assertEquals("val steps = ClearSteps()", wipe[at - 1])
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
        block("val feed = FeedManager(").has("held = { releaseUpdate.checking.value },")
        // What sends waits for the release check too.
        code.has("private fun sendsGated(): Boolean = sendsGated(featuresGated(), releaseUpdate.checking.value)")
        block("val presence = PresenceManager(").has("closed = { phaseFlow.value != AppPhase.Unlocked || sendsGated() },")
        block("val location = LocationSharing(").has("due = { sendsGated() },")
    }

    @Test
    fun eventsAreRoutedByThePause() {
        block("private suspend fun takeEvent(").has("takeIntoFeatures(m, releaseUpdate.paused.value)")
        val route = block("private suspend fun takeIntoFeatures(")
        assertEquals(EventTaker.entries.size, route.count { it.startsWith("if (takes(EventTaker.") })
    }

    @Test
    fun theOpeningsReadsAndTheChecksStepsWaitWhilePaused() {
        // The opened steps read only what the end of this opening's check has not read already (once).
        block("phaseSteps.onPhase(it) {").has(
            "if (releaseUpdate.openingReadsDue()) openedReads(releaseUpdate.paused.value) { gatedReads() }",
        )
        code.has("onPassed = { if (!releaseUpdate.paused.value) social.refreshAllQuietly() },")
        code.has("if (!releaseUpdate.required.value) steps.step { votes.startSync() }")
        code.has("if (!releaseUpdate.paused.value) steps.step { social.refreshAllQuietly() }")
        code.has("if (gatedEnded) steps.step { releasePause.ownerCheckLifted() }")
        val hold = block("private suspend fun afterHoldPassed(")
        assertEquals("if (releaseUpdate.paused.value) {", hold[1])
        // The waiting-asks replay is kept for the lift, its window open (never ended here).
        assertEquals("if (gen != null) heldAskReplay.hold(HeldAskReplay.Due(gen, listMark, token))", hold[2])
        assertEquals("return", hold[3])
        code.has(
            "afterCredentialReset(releaseUpdate.paused.value, { refreshAlarm(s) }, { ownerCheck.onOpened() }, " +
                "{ items.refreshQuietly() })",
        )
    }

    @Test
    fun thePauseIsFollowed() {
        code.has("releaseUpdate.onGateChange = { c -> releasePause.onGate(c) }")
        val pause = block("private val releasePause = ReleasePauseSteps(")
        pause.has("unlocked = { phaseFlow.openNotDeleting },")
        pause.has("checkGated = { ownerCheck.ownerCheck.value?.gated(Instant.now()) == true },")
        pause.has("paused = { releaseUpdate.paused.value },")
        pause.has("required = { releaseUpdate.required.value },")
        pause.has("stopVotes = { votes.stopSync() },")
        val reads = pause.subList(pause.indexOf("reads = {"), pause.indexOf("sends = {"))
        reads.has("gatedReads()")
        reads.has("sharing.invalidateTags()")
        reads.has("heldAskReplay.take()?.let { d -> scope.launch { afterHoldPassed(d.gen, d.listMark, d.token) } }")
        val sends = pause.subList(pause.indexOf("sends = {"), pause.size)
        listOf("votes.startSync()", "presence.start()", "location.resume()", "feed.releaseHeld()").forEach { sends.has(it) }
        val opened = block("private fun gatedReads()")
        listOf("social.refreshAllQuietly()", "items.refreshQuietly()", "feed.open()").forEach { opened.has(it) }
        assertTrue(opened.any { it.contains("attachments.probe()") })
        assertTrue("the votes send: not before the check is over", opened.none { it.contains("votes.startSync()") })
        // Nothing in the opened steps starts the votes' work.
        assertTrue(block("phaseSteps.onPhase(it) {").none { it.contains("votes.startSync()") })
    }

    @Test
    fun theManifestIsReadFailingClosed() {
        val offer = block("override suspend fun offer(): ReleaseUpdateOffer? = guard {")
        offer.has("ReleaseGateOffer.checkNumber(st.releaseNumber)")
        offer.has("ReleaseGateOffer.of(st.release, st.releaseNumber, m.releases, m::byPcr0)")
        code.has("override fun releaseNumber(): Long? = session?.device?.altState?.releaseNumber")
        // No retries in the background after a verification failure: the app's foreground signal.
        code.has("override fun inFront(): Boolean = ownerCheck.inFront.value")
    }

    /** A relay reconnect reads the manifest again, with the feed's catch-up, while the vault is open. */
    @Test
    fun aRelayReconnectReadsTheManifest() {
        val restart = block("CollectorSupervisor(this, restart = {")
        val open = restart.indexOf("if (phaseFlow.value == AppPhase.Unlocked) {")
        assertTrue("under the open phase", open >= 0)
        val inner = restart.subList(open, restart.size)
        assertEquals("feed.catchUp()", inner[1])
        assertEquals("scope.launch { releaseUpdate.refreshOffer() }", inner[2])
        assertEquals("}", inner[3])
    }
}
