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
        // The opening's mark: the phone's clock, until the first answer of this opening (never a vault time learnt before it)
        // puts it on the vault's clock, the clock of the messages' `ts` it is compared with (ANDROID-PLAN 0.1.134).
        val open = block("private val phaseFlow = PhaseHolder(")
        open.has("onOpen = { continued ->")
        // The continue-or-new decision is OpeningSteps.onOpen's (OpeningStepsTest drives it).
        open.has("openingSteps.onOpen(continued, Instant.now())?.let { opening ->")
        open.has("session?.device?.beginOpening { at -> feed.openingMarked(at, opening) }")
        assertTrue("no vault time learnt before the opening", open.none { it.contains("vaultNow") })
        block("private val phaseFlow = PhaseHolder(").has("releaseUpdate.vaultOpened()")
    }

    @Test
    fun aLockEndsTheCheckAndTheWaitingReads() {
        val clear = block("clearReleaseCheck = {")
        clear.has("phaseFlow.unlessOpen { releaseUpdate.vaultClosed() }")
        clear.has("releasePause.reset()")
        clear.has("openingSteps.dropReplayOnClose(p)")
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
        block("val feed: FeedManager = FeedManager(").has("gated = { featuresGated() },")
        block("val feed: FeedManager = FeedManager(").has("held = { releaseUpdate.checking.value },")
        block("val feed: FeedManager = FeedManager(").has("ownerGated = { ownerCheck.ownerCheck.value?.gated(Instant.now()) == true },")
        // What sends waits for the release check too.
        code.has("private fun sendsGated(): Boolean = sendsGated(featuresGated(), releaseUpdate.checking.value)")
        block("val presence = PresenceManager(").has("closed = { phaseFlow.value != AppPhase.Unlocked || sendsGated() },")
        block("val location = LocationSharing(").has("due = { sendsGated() },")
        // The votes' sync runs and event-started checks too.
        block("val votes = VotesManager(").has("sendsGated = { sendsGated() },")
        // Photos: nothing sent, fetched or kept while the vault is not open, the check is due or held, or the release is
        // checked or paused (no availability probe: every vault the app talks to has attachments).
        block("val attachments = com.vettid.core.data.social.AttachmentManager(")
            .has("vaultClosed = { phaseFlow.value != AppPhase.Unlocked || sendsGated() },")
    }

    /**
     * `AttachmentsRepository.closed()` turns true before the attachments' clear of a lock, the hold or due state and a wipe
     * (the forget paths drop the session first; callers carry the clear mark into `begin` for those).
     */
    @Test
    fun theVaultIsClosedBeforeTheLockHoldAndWipeClearPhotos() {
        // A lock: the clears run on the phase already published (the phase's collector).
        code.has("clearAttachments = { attachments.clear() },")
        assertTrue(block("phaseFlow.asStateFlow().collectSurviving(\"phase\") {").any { it.startsWith("phaseSteps.onPhase(it) {") })
        // The hold or due state: on an owner-check value that is already held (held means gated).
        val held = block("ownerCheck.ownerCheck.collectSurviving(\"owner check\") { v ->")
        held.has("val now = OwnProfileStep.held(v)")
        val opens = held.indexOf("if (now && !wasHeld) {")
        assertTrue(opens >= 0)
        assertTrue(held.subList(opens, held.size).contains("steps.step { attachments.clear() }"))
        // A wipe: the release gate's clear (a pause) is its first step, before the attachments'.
        val wipe = block("private suspend fun eraseAfterMark()")
        assertTrue(wipe.indexOf("steps.step { releaseUpdate.clear() }") in 0 until wipe.indexOf("steps.step { attachments.forget() }"))
    }

    @Test
    fun noAttachmentProbeAndTheClearsStay() {
        assertTrue("no probe", code.none { it.contains("attachments.probe") })
        assertTrue("no reset", code.none { it.contains("attachments.reset") })
        // The lock's clear (PhaseSteps) and the hold's keep the cleanups for the next opening; signing out, both deletions
        // and the wipe forget them.
        code.has("clearAttachments = { attachments.clear() },")
        assertEquals(1, code.count { it == "steps.step { attachments.clear() }" })
        assertEquals(4, code.count { it == "steps.step { attachments.forget() }" })
        listOf("dropSessionLocked(", "closeForDeletion(", "closeForApiDeleted(", "eraseAfterMark()").forEach { f ->
            block("private suspend fun $f").has("steps.step { attachments.forget() }")
        }
    }

    /** Photos have no watch of their own: they are told the vault opened where presence starts again. */
    @Test
    fun photosAreToldEachOpening() {
        val pause = block("private val releasePause = ReleasePauseSteps(")
        pause.subList(pause.indexOf("sends = {"), pause.size).has("attachments.opened()")
        block("phaseSteps.onPhase(it) {").has("attachments.opened()")
        val held = block("ownerCheck.ownerCheck.collectSurviving(\"owner check\") { v ->")
        assertEquals(2, held.count { it == "steps.step { attachments.opened() }" })
        assertEquals(held.count { it == "steps.step { presence.start() }" }, held.count { it == "steps.step { attachments.opened() }" })
        assertEquals(4, code.count { it.contains("attachments.opened()") })
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
        // The reserved feed read gives its place back; the waiting-asks replay is kept for the lift, its window open (never
        // ended here).
        assertEquals("read.drop()", hold[2])
        assertEquals("if (gen != null) heldAskReplay.hold(HeldAskReplay.Due(gen, listMark, token))", hold[3])
        assertEquals("return", hold[4])
        code.has(
            "afterCredentialReset(releaseUpdate.paused.value, { refreshAlarm(s) }, { ownerCheck.onOpened() }, " +
                "{ items.refreshQuietly() })",
        )
    }

    /**
     * (Rule 3, ANDROID-PLAN 0.1.134) A passed check's read is counted among the feed's reads before it is launched, so the
     * lift's sends (which release what the release check held) wait for it; every launch goes that way, and the place is
     * given back however the launch ends. Its waiting-asks replay is judged on the first `pending.list` merged after the
     * check, never the latest.
     */
    @Test
    fun aPassedChecksReadIsCountedBeforeItIsLaunched() {
        val launch = block("private fun launchAfterHoldPassed(")
        launch.has("val read = feed.reserveRefresh()")
        launch.has("scope.launch { afterHoldPassed(gen, listMark, token, read) }.invokeOnCompletion { read.drop() }")
        val reserved = launch.indexOf("val read = feed.reserveRefresh()")
        assertTrue("reserved before the launch", reserved < launch.indexOfFirst { it.contains("scope.launch") })
        assertEquals("no launch without the reservation", 1, code.count { it.contains("scope.launch { afterHoldPassed(") })
        val hold = block("private suspend fun afterHoldPassed(")
        hold.has("runCatching { read.run() }")
        assertTrue("its replay uses the first list after the check", hold.any { it.contains("social.firstListedSince(listMark)") })
        assertTrue("never the latest list", hold.none { it.contains("listedNow()") })
    }

    /** (ANDROID-PLAN 0.1.134 "A push wake") Only a push that came while the phase was unknown makes held-back events a wake's. */
    @Test
    fun onlyAPushStartedProcessesHeldBackEventsAreAWakes() {
        block("override suspend fun collectForWake()").has("if (heldBack.wakeIfUnknown(phaseFlow.value)) refresh()")
        // An activity started: a push from then on did not start the process.
        assertEquals("heldBack.uiStarted()", block("override fun onForeground()")[1])
        code.has("override fun onUiCreated() = heldBack.uiStarted()")
        code.has("replay = { p -> heldBack.releaseHeld(p).let { r -> replayHeldBack(r.events, r.wake) } },")
        block("private fun replayHeldBack(").has("events.forEach { e -> runCatching { feed.replay(e, wake) } }")
    }

    /**
     * (ANDROID-PLAN 0.1.134) A status read that could not be made keeps what the release check holds for a continued opening;
     * the hold's or due state's end marks the lift before any read; the push flag follows the activities.
     */
    @Test
    fun aBlipKeepsWhatIsHeldAndTheLiftIsMarked() {
        // Soft for Unreachable, nothing for a close reached after a newer opening (OpeningStepsTest drives both).
        code.has("clearFeed = { p -> openingSteps.clearFeed(p) },")
        // The lift is marked by the owner check before its value is published, with an opening current.
        block("OwnerCheckManager(").has("onLift = { at ->")
        code.has("val opening = phaseFlow.value.let { it == AppPhase.Unlocked || it is AppPhase.Unreachable }")
        code.has("val vaultNow = session?.device?.vaultNowIfKnown()")
        code.has("if (opening) feed.ownerCheckLifted(listOfNotNull(at, vaultNow).minOrNull())")
        // The hold's clear keeps the opening (a blip after it continues it).
        code.has("steps.step { feed.clear(endsOpening = false) }")
        // A lift the collector did not see as one still gets its full read.
        code.has("val feedOwed = phaseFlow.value == AppPhase.Unlocked && !releaseUpdate.paused.value && !feed.loaded")
        code.has("} else if (!gated && feedOwed) {")
        // A continued opening owes only what follows its own reads.
        block("private val releasePause = ReleasePauseSteps(").has("owed = { afterGatedReads() },")
        // A replay a pause kept across Unreachable runs when the opening continues.
        code.has("if (heldAskReplay.held()) releasePause.owedReads()")
        // An identity switch keeps nothing of the feed or a replay.
        block("private suspend fun switchIdentity(").has("heldAskReplay.drop()")
        block("private suspend fun switchIdentity(").has("runCatching { feed.clear() }")
        // A due state the clock alone began counts (pauseLifted), and a held one too.
        code.has("val checkEnded = lifted || !now && wasHeld")
        // The held waiting-asks replay is kept across Unreachable, dropped by a close (OpeningStepsTest).
        code.has("openingSteps.dropReplayOnClose(p)")
        // A pass learnt during Unreachable is kept for the continued opening, not launched.
        code.has("} else if (replayFor != null && unreachable) {")
        code.has("WaitingAsksReplay.due(before, v, at, unlocked = phaseFlow.value == AppPhase.Unlocked || unreachable)")
        code.has("if (!openingSteps.holdForTheContinuedOpening(due)) launchAfterHoldPassed(replayFor, listMark, replayToken)")
        // Every lift with the vault open reads the feed in full, also one with no replay and no hold.
        code.has("} else if (checkEnded && phaseFlow.value == AppPhase.Unlocked) {")
        code.has("steps.step { launchAfterHoldPassed(null, listMark, replayToken) }")
        // A run waits through Unreachable; a new opening drops a replay kept across it.
        code.has("paused = { phaseFlow.value is AppPhase.Unreachable && record.generation == gen && feed.replayOwns(token) },")
        assertTrue(block("private val phaseFlow = PhaseHolder(").any { it.startsWith("openingSteps.onOpen(") })
        code.has("override fun onUiStopped() = heldBack.uiStopped()")
    }

    /** Nothing of the feed is kept while the vault is not open on this phone; a hold, which keeps it open, keeps nothing out. */
    @Test
    fun theFeedKeepsNothingOutsideAnOpenVault() {
        block("val feed: FeedManager = FeedManager(").has("vaultOpen = { phaseFlow.value == AppPhase.Unlocked },")
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
        reads.has("afterGatedReads()")
        val after = block("private fun afterGatedReads()")
        after.has("sharing.invalidateTags()")
        after.has("heldAskReplay.take()?.let { d -> launchAfterHoldPassed(d.gen, d.listMark, d.token) }")
        val sends = pause.subList(pause.indexOf("sends = {"), pause.size)
        listOf("votes.startSync()", "presence.start()", "location.resume()", "feed.releaseHeld()").forEach { sends.has(it) }
        val opened = block("private fun gatedReads()")
        listOf("social.refreshAllQuietly()", "items.refreshQuietly()", "feed.open()").forEach { opened.has(it) }
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
        assertEquals("releaseUpdate.relayReconnected()", inner[2])
        assertEquals("}", inner[3])
    }
}
