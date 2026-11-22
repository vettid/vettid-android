// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.devices.AgentChange
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.HostProblem
import com.vettid.core.data.devices.ItemWay
import com.vettid.core.data.devices.ItemWays
import com.vettid.core.data.devices.ItemWaysRead
import com.vettid.core.data.devices.ItemWaysRepository
import com.vettid.core.data.devices.ItemWaysRules
import com.vettid.core.data.devices.ItemWaysSave
import com.vettid.core.data.devices.ProgramProblem
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.votes.WindowNotClosed
import com.vettid.core.testing.FakeDevices
import com.vettid.core.testing.FakeItems
import com.vettid.core.vault.VaultJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** ANDROID-PLAN 0.1.137 AA7: the item's page, its draft, its confirmations and its saves. */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemWaysViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    /** A save: the settings sent, the password (null: none) and whether it was the resend after `credential_locked`. */
    private data class Sent(val next: ItemWays, val password: String?, val resent: Boolean)

    private class Fake : ItemWaysRepository {
        override val items = MutableStateFlow<Map<String, List<ItemWaysRead>>>(emptyMap())
        override val windowNotClosed = MutableStateFlow<WindowNotClosed?>(null)
        var current: String? = """{"agent_id":"a1","item_id":"i1","version":1,"ways":["use"]}"""
        val saves = mutableListOf<Sent>()
        val answers = ArrayDeque<ItemWaysSave>()
        val offs = mutableListOf<String>()
        var reads = 0

        override suspend fun list(agentId: String): List<ItemWaysRead> = emptyList()

        override suspend fun read(agentId: String, itemId: String): ItemWaysRead? {
            reads++
            return current?.let { ItemWaysRules.one(it.toByteArray()) }
        }

        override suspend fun save(cur: ItemWaysRead, next: ItemWays, password: String?, resent: Boolean): ItemWaysSave {
            saves += Sent(next, password, resent)
            return answers.removeFirstOrNull() ?: ItemWaysSave.Saved(ItemWaysRules.one(VaultJson.bytes(next.setBody()))!!)
        }

        override suspend fun turnOff(cur: ItemWaysRead, way: String): ItemWaysSave {
            offs += way
            return answers.removeFirstOrNull() ?: ItemWaysSave.Saved(cur)
        }

        override suspend fun closeWindowNow() = Unit
    }

    private val repo = Fake()
    private val devices = FakeDevices().apply {
        devices.value = listOf(DeviceInfo("a1", "agent", "Claude", null, null, null, null))
    }
    private val items = FakeItems().apply {
        this.items.value = listOf(ItemSummary("i1", 1, "GitHub token", "secret", Sensitivity.SECRET))
    }

    private fun vm() = ItemWaysViewModel(SavedStateHandle(mapOf(ItemWaysRoute.AGENT to "a1", ItemWaysRoute.ITEM to "i1")), repo, devices, items)

    private val sha = "ab".repeat(32)

    /** The page's state once the test dispatcher has run (the state is combined on it). */
    private fun TestScope.st(m: ItemWaysViewModel): ItemWaysUiState {
        advanceUntilIdle()
        return m.uiState.value
    }

    @Test
    fun opensWithTheVaultsSettingsAndNames() = runTest(main.dispatcher) {
        val m = vm()
        advanceUntilIdle()
        val s = st(m)
        assertEquals("Claude", s.agentName)
        assertEquals("GitHub token", s.itemName)
        assertEquals(setOf(ItemWay.USE), s.draft!!.ways)
        assertFalse(s.dirty)
        assertFalse(s.canSave)
        assertFalse(s.holdsInput)
    }

    /** The reveal switch shows the history warning before it turns on; Cancel leaves it off; off asks nothing. */
    @Test
    fun revealAsksFirstAndCancelLeavesItOff() = runTest(main.dispatcher) {
        val m = vm()
        advanceUntilIdle()
        m.setReveal(true)
        assertEquals(WaysConfirm.RevealOn, st(m).confirm)
        assertFalse(ItemWay.REVEAL in st(m).draft!!.ways)
        m.dismissConfirm()
        assertFalse(ItemWay.REVEAL in st(m).draft!!.ways)
        m.setReveal(true)
        m.confirm()
        assertTrue(ItemWay.REVEAL in st(m).draft!!.ways)
        m.setReveal(false)
        assertNull(st(m).confirm)
        assertFalse(ItemWay.REVEAL in st(m).draft!!.ways)
    }

    /** A widening asks for the password; the prompt's request is the same save with it; the vault's answer is shown. */
    @Test
    fun aWideningSaveGoesThroughThePrompt() = runTest(main.dispatcher) {
        repo.answers += ItemWaysSave.NeedsPassword
        val m = vm()
        advanceUntilIdle()
        m.setReveal(true)
        m.confirm()
        assertTrue(st(m).canSave && st(m).holdsInput)
        m.save()
        advanceUntilIdle()
        assertTrue(st(m).prompt != null)
        m.setPassword("pw")
        m.submitPassword()
        advanceUntilIdle()
        assertEquals(listOf(null, "pw"), repo.saves.map { it.password })
        assertNull(st(m).prompt)
        assertEquals(WaysNotice.Saved, st(m).notice)
        assertFalse(st(m).dirty)
        assertTrue(ItemWay.REVEAL in st(m).read!!.ways!!.ways)
    }

    /** `credential_locked` after a save judged narrowing: the prompt, and one resend marked as such. */
    @Test
    fun credentialLockedPromptsAndResendsOnce() = runTest(main.dispatcher) {
        repo.current = """{"agent_id":"a1","item_id":"i1","version":1,"ways":["use","reveal"]}"""
        repo.answers += ItemWaysSave.CredentialLocked
        repo.answers += ItemWaysSave.LockedAgain
        val m = vm()
        advanceUntilIdle()
        m.setReveal(false)
        m.save()
        advanceUntilIdle()
        assertTrue(st(m).prompt!!.resend)
        m.setPassword("pw")
        m.submitPassword()
        advanceUntilIdle()
        assertEquals(listOf(null to false, "pw" to true), repo.saves.map { it.password to it.resent })
        assertEquals(WaysNotice.LockedAgain, st(m).notice)
        assertNull(st(m).prompt)
    }

    /** A widening's `credential_locked`: the prompt again, and the same save with the new password sent once more. */
    @Test
    fun aWideningLockedPromptsAndResendsOnce() = runTest(main.dispatcher) {
        repo.answers += ItemWaysSave.NeedsPassword
        repo.answers += ItemWaysSave.CredentialLocked
        val m = vm()
        advanceUntilIdle()
        m.setReveal(true)
        m.confirm()
        m.save()
        advanceUntilIdle()
        m.setPassword("pw")
        m.submitPassword()
        advanceUntilIdle()
        assertTrue(st(m).prompt!!.resend)
        assertEquals("", st(m).prompt!!.password)
        m.setPassword("pw2")
        m.submitPassword()
        advanceUntilIdle()
        assertEquals(listOf(null to false, "pw" to false, "pw2" to true), repo.saves.map { it.password to it.resent })
        assertEquals(WaysNotice.Saved, st(m).notice)
        assertNull(st(m).prompt)
        assertTrue(repo.saves.map { it.next }.distinct().size == 1)
    }

    /** The page resends once only: a second `credential_locked`, whatever the repository says, is "locked again". */
    @Test
    fun aSecondCredentialLockedIsNotPromptedAgain() = runTest(main.dispatcher) {
        repo.current = """{"agent_id":"a1","item_id":"i1","version":1,"ways":["use","reveal"]}"""
        repo.answers += ItemWaysSave.CredentialLocked
        repo.answers += ItemWaysSave.CredentialLocked
        val m = vm()
        advanceUntilIdle()
        m.setReveal(false)
        m.save()
        advanceUntilIdle()
        m.setPassword("pw")
        m.submitPassword()
        advanceUntilIdle()
        assertEquals(2, repo.saves.size)
        assertNull(st(m).prompt)
        assertEquals(WaysNotice.LockedAgain, st(m).notice)
        m.submitPassword()
        advanceUntilIdle()
        assertEquals("nothing more sent", 2, repo.saves.size)
    }

    @Test
    fun aConflictReloadsAndNotFoundLeaves() = runTest(main.dispatcher) {
        repo.answers += ItemWaysSave.Conflict
        val m = vm()
        advanceUntilIdle()
        m.setReveal(true)
        m.confirm()
        m.save()
        advanceUntilIdle()
        assertEquals(WaysNotice.Conflict, st(m).notice)
        assertEquals(2, repo.reads)
        assertFalse(st(m).dirty)
        repo.answers += ItemWaysSave.NotIncluded
        m.setReveal(true)
        m.confirm()
        m.save()
        advanceUntilIdle()
        assertTrue(st(m).notIncluded && st(m).leaving)
        assertFalse(st(m).canSave)
    }

    @Test
    fun eachAnswerHasItsNotice() = runTest(main.dispatcher) {
        repo.answers += ItemWaysSave.BadRequest("program: refused name")
        val m = vm()
        advanceUntilIdle()
        m.setReveal(true)
        m.confirm()
        m.save()
        advanceUntilIdle()
        assertEquals(WaysNotice.BadRequest("program: refused name"), st(m).notice)
        assertTrue("the draft is kept", st(m).dirty)
        repo.answers += ItemWaysSave.NeedsPassword
        repo.answers += ItemWaysSave.AlarmOpen
        m.save()
        advanceUntilIdle()
        m.setPassword("pw")
        m.submitPassword()
        advanceUntilIdle()
        assertTrue("the alarm stays on the prompt", st(m).prompt!!.alarm)
        m.dismissPrompt()
        repo.answers += ItemWaysSave.OwnerCheck
        m.save()
        advanceUntilIdle()
        assertTrue(st(m).ownerCheck)
    }

    /** Run settings: add (with each refusal), the program's page, variables, Ask me before each run, removing the last. */
    @Test
    fun programsAndTheirSettings() = runTest(main.dispatcher) {
        val m = vm()
        advanceUntilIdle()
        m.setRun(true)
        assertTrue(st(m).draft!!.run!!.each)
        m.setEach(false) // with no program it stays on (the switch is disabled); the page would not offer this
        assertEquals(WaysConfirm.EachOff, st(m).confirm)
        m.dismissConfirm()
        m.setProgramInput("bin/git")
        m.addProgram()
        assertEquals(ProgramProblem.NOT_PATH_OR_NAME, st(m).programProblem)
        m.setProgramInput("/usr/bin/git")
        m.addProgram()
        m.setProgramInput("GIT")
        m.addProgram()
        assertEquals(ProgramProblem.DUPLICATE, st(m).programProblem)
        m.setProgramInput("gh")
        m.addProgram()
        assertEquals(listOf("/usr/bin/git", "gh"), st(m).draft!!.run!!.programs.map { it.program })
        m.openProgram(0)
        m.setVariableInput("lower")
        m.addVariable()
        assertTrue(st(m).variableProblem != null)
        m.setVariableInput("GH_HOST")
        m.addVariable()
        assertEquals(listOf("GH_HOST"), st(m).draft!!.run!!.programs[0].env)
        // A name already listed is not added twice; the field empties as after any add.
        m.setVariableInput("GH_HOST")
        m.addVariable()
        assertEquals(listOf("GH_HOST"), st(m).draft!!.run!!.programs[0].env)
        assertEquals("", st(m).variableInput)
        assertNull(st(m).variableProblem)
        m.removeVariable("GH_HOST")
        assertTrue(st(m).draft!!.run!!.programs[0].env.isEmpty())
        // Removing a program while another stays asks nothing.
        m.removeProgram(0)
        assertNull(st(m).confirm)
        assertEquals(listOf("gh"), st(m).draft!!.run!!.programs.map { it.program })
        m.setEach(false)
        m.confirm()
        assertFalse(st(m).draft!!.run!!.each)
        // Removing the last asks first, and emptying the list turns Ask me before each run back on.
        m.removeProgram(0)
        assertEquals(WaysConfirm.RemoveLastProgram(0), st(m).confirm)
        m.confirm()
        assertTrue(st(m).draft!!.run!!.programs.isEmpty())
        assertTrue(st(m).draft!!.run!!.each)
    }

    /** Forget fingerprint asks first, then shows it is forgotten until Save, which sends no pin; editing forgets it too. */
    @Test
    fun fingerprints() = runTest(main.dispatcher) {
        repo.current = """{"agent_id":"a1","item_id":"i1","version":2,"ways":["use","run"],"run":{"programs":[{"program":"git","sha256":"$sha","path":"/usr/bin/git","env":["GH_HOST"]}],"each":true}}"""
        val m = vm()
        advanceUntilIdle()
        m.forgetPin(0)
        assertEquals(WaysConfirm.ForgetPin(0), st(m).confirm)
        m.confirm()
        val p = st(m).draft!!.run!!.programs[0]
        assertNull(p.sha256)
        assertTrue(st(m).pinForgotten(p))
        m.save()
        advanceUntilIdle()
        assertNull(repo.saves.single().next.run!!.programs[0].sha256)
        // Changing the program's text: removing it and adding another, its variables kept, no pin.
        repo.current = """{"agent_id":"a1","item_id":"i1","version":3,"ways":["use","run"],"run":{"programs":[{"program":"git","sha256":"$sha","path":"/usr/bin/git","env":["GH_HOST"]}],"each":true}}"""
        m.load()
        advanceUntilIdle()
        m.openProgram(0)
        m.setEditInput("bin/x")
        assertFalse(m.closeProgram())
        assertEquals(ProgramProblem.NOT_PATH_OR_NAME, st(m).editProblem)
        m.setEditInput("/usr/local/bin/git")
        assertTrue(m.closeProgram())
        val q = st(m).draft!!.run!!.programs[0]
        assertEquals(listOf("/usr/local/bin/git", null, listOf("GH_HOST")), listOf(q.program, q.sha256, q.env))
    }

    /** The API switch: defaults filled in, Add a host opened, no save until a host is there; each refusal; the last host stays. */
    @Test
    fun apiSettings() = runTest(main.dispatcher) {
        val m = vm()
        advanceUntilIdle()
        m.setProxy(true)
        val s = st(m)
        assertTrue(s.addingHost)
        assertEquals("Authorization", s.draft!!.proxy!!.header)
        assertEquals("Bearer ", s.draft!!.proxy!!.prefix)
        assertFalse("proxy needs a host", s.canSave)
        m.setHostInput("10.0.0.1")
        m.addHost()
        assertEquals(HostProblem.IP_ADDRESS, st(m).hostProblem)
        m.setHostInput(" API.Example.com ")
        m.addHost()
        assertEquals(listOf("api.example.com"), st(m).draft!!.proxy!!.hosts)
        assertTrue(st(m).canSave)
        m.removeHost("api.example.com")
        assertEquals(listOf("api.example.com"), st(m).draft!!.proxy!!.hosts)
        m.setHeader("Host")
        assertFalse(st(m).canSave)
        m.setHeader("X-Api-Key")
        m.setPrefix("x".repeat(33))
        assertFalse(st(m).canSave)
        m.setPrefix("Token ")
        assertTrue(st(m).canSave)
        // Turning it off asks first.
        m.setProxy(false)
        assertEquals(WaysConfirm.ProxyOff, st(m).confirm)
        m.confirm()
        assertNull(st(m).draft!!.proxy)
    }

    /** A read-only page offers only to turn a whole way off, saved at once, with its confirmation. */
    @Test
    fun aReadOnlyPageTurnsWaysOff() = runTest(main.dispatcher) {
        repo.current = """{"agent_id":"a1","item_id":"i1","version":2,"ways":["use","run","reveal"],"run":{"programs":[],"each":true,"x":1}}"""
        val m = vm()
        advanceUntilIdle()
        assertTrue(st(m).readOnly)
        assertNull(st(m).draft)
        m.setRun(false)
        assertEquals(WaysConfirm.RunOff, st(m).confirm)
        m.confirm()
        advanceUntilIdle()
        m.setReveal(false)
        advanceUntilIdle()
        assertEquals(listOf("run", "reveal"), repo.offs)
        assertTrue(repo.saves.isEmpty())
    }

    /** `leash.item.changed` while open: reread when nothing is edited; kept with the notice when it is; deleted settings said. */
    @Test
    fun changesFromAnotherDevice() = runTest(main.dispatcher) {
        val m = vm()
        advanceUntilIdle()
        repo.current = """{"agent_id":"a1","item_id":"i1","version":2,"ways":["use","reveal"]}"""
        devices.changes.emit(AgentChange.Item("a1", "i1", 2))
        advanceUntilIdle()
        assertEquals(2L, st(m).read!!.ways!!.version)
        m.setRun(true)
        devices.changes.emit(AgentChange.Item("a1", "i1", 3))
        advanceUntilIdle()
        assertTrue(st(m).changedElsewhere && st(m).dirty)
        devices.changes.emit(AgentChange.Item("a1", "other", 3))
        devices.changes.emit(AgentChange.Item("a1", "i1", null))
        advanceUntilIdle()
        assertTrue(st(m).notIncluded)
        assertFalse(st(m).canSave)
    }
}
