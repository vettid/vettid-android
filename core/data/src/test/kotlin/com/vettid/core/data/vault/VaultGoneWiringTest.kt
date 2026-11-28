package com.vettid.core.data.vault

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * (#19721) How [VaultManager] wires [VaultGoneGate] and [VaultGoneWatch]: [VaultManager] has no unit test harness, so its
 * lines are read from the source (as [ReleaseGateWiringTest] does); the rules themselves are tested in [VaultGoneGateTest]
 * and [VaultGoneWatchTest]. Each test fails when the line it names is taken out or given another source.
 */
class VaultGoneWiringTest {
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
    fun theGateIsBuiltFromTheRealSources() {
        val w = block("private val vaultGone by lazy {")
        w.has("phase = { phaseFlow.value },")
        w.has("activityStarted = { heldBack.uiStartedNow },")
        w.has("unlockInFlight = { unlockNotices.inFlight() },")
        w.has("openings = { lockNotices.openings },")
        w.has("checkScreenProbing = { ownerCheck.screenProbing },")
    }

    @Test
    fun theWatchProbesThroughTheGateAndIgnoresTheOwnerChecksOwnRequest() {
        val w = block("private val vaultGone by lazy {")
        w.has("watching = gate::watching,")
        w.has("val mark = gate.begin()")
        w.has("ownerCheck.probeVault(lockCauseKnown = false) { gate.mayLock(mark) }")
        w.has("elapsedMs = { android.os.SystemClock.elapsedRealtime() },")
        w.has("ignored = setOf(VaultApi.TYPE_OWNER_CHECK),")
    }

    @Test
    fun everyDeviceTellsTheWatchOfUnansweredRequestsAndOfMessages() {
        code.has("device.onNoAnswer = vaultGone::onNoAnswer")
        code.has("device.onHeard = vaultGone::heard")
    }

    @Test
    fun everyOpeningStartsTheCountAgain() {
        block("suspend fun opened(afterMissedClose: Boolean) {").has("vaultGone.reset()")
    }

    @Test
    fun theNoticeIsTheWatchsSilenceWhileTheVaultIsOpen() {
        val f = block("override val vaultSilent: StateFlow<Boolean> by lazy {")
        f.has("combine(vaultGone.silent, phase) { silent, p -> silent && p == AppPhase.Unlocked }")
    }

    /**
     * "Unlock again" shows the unlock screen as the member's own lock here does, sends no lock, and only while the watch is
     * silent and the vault open (read directly, not through the derived flow).
     */
    @Test
    fun unlockAgainShowsTheUnlockScreenOnlyWhileSilentAndSendsNothing() {
        val u = block("override fun unlockAgain() {")
        val first = "if (!vaultGone.silent.value || phaseFlow.value != AppPhase.Unlocked) return"
        assertTrue("first: only while silent and open", u[1] == first)
        u.has("unlockAgainAsked = true")
        u.has("vaultGone.reset()")
        u.has("markLockedHere()")
        u.has("ownerCheck.onLockedHere()")
        u.has("lockedByNotice()")
        assertTrue("no lock sent", u.none { it.contains("lockKeeper") || it.contains("lockWith") || it.contains("beginLock") })
    }

    /** A refresh after "Unlock again" keeps the phone locked although the member API still says unlocked. */
    @Test
    fun aRefreshAfterUnlockAgainStaysLocked() {
        block("private suspend fun evaluate(): AppPhase = guard {")
            .has("if (!VaultDeletion.statusUnlocked(st.state) || unlockAgainAsked) AppPhase.Locked else afterUnlocked(s)")
    }

    /** The unlock's sealed ok result ends "Unlock again" and restarts the count before the phase is published. */
    @Test
    fun theUnlocksResultEndsUnlockAgainBeforeItOpens() {
        val u = block("private suspend fun unlockStepHeld(")
        val opened = u.indexOf("lockNotices.opened(r.at)")
        val cleared = u.indexOf("unlockAgainAsked = false")
        val reset = u.indexOf("vaultGone.reset()")
        val publish = u.indexOfFirst { it.startsWith("openAfterUnlock(") }
        assertTrue("$opened $cleared $reset $publish", opened in 0 until cleared && cleared < reset && reset < publish)
        block("suspend fun opened(afterMissedClose: Boolean) {").has("unlockAgainAsked = false")
    }
}
