package com.vettid.app.ui

import com.vettid.core.data.items.TagRegistry
import com.vettid.core.data.items.TagView
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeVault
import com.vettid.core.ui.theme.TagColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Tag colours while the vault is open (owner decision 2026-10-09): read, assigned, never while the owner check is due. */
@OptIn(ExperimentalCoroutinesApi::class)
class TagColorsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val vault = FakeVault(AppPhase.Unlocked)
    private val sharing = FakeSharing().apply {
        registry = TagRegistry(
            3,
            listOf(TagView("@profile", 1), TagView("travel", 1), TagView("medical", 2), TagView("money", 1), TagView("family", 1), TagView("work", 1, color = "#123456")),
        )
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theRegistryIsReadWhenTheVaultOpensAndEveryTagGetsADistinctColour() = runTest(dispatcher) {
        val vm = TagColorsViewModel(sharing, vault)
        advanceUntilIdle()
        assertTrue("refreshTags" in sharing.calls)
        val c = vm.colors.value
        assertNull(c["@profile"])
        assertEquals("#123456", c["work"])
        val mine = listOf("travel", "medical", "money", "family").map { TagColors.slotOf(c[it]) }
        assertTrue(mine.all { it >= 0 })
        assertEquals(4, mine.distinct().size)
    }

    @Test
    fun aDroppedRegistryIsReadAgainAndANewTagColoured() = runTest(dispatcher) {
        val vm = TagColorsViewModel(sharing, vault)
        advanceUntilIdle()
        sharing.registry = sharing.registry.copy(tags = sharing.registry.tags + TagView("garden", 1))
        sharing.tags.value = null // a tag.changed, or a tag first used on an item
        advanceUntilIdle()
        assertTrue(TagColors.slotOf(vm.colors.value["garden"]) >= 0)
    }

    @Test
    fun nothingIsStoredWhileTheOwnerCheckIsDue() = runTest(dispatcher) {
        vault.ownerCheck.value = OwnerCheckView(OwnerCheckState.HELD, Instant.EPOCH, 86_400, 0, hold = true, holdOffUntil = null)
        val vm = TagColorsViewModel(sharing, vault)
        advanceUntilIdle()
        assertTrue(sharing.calls.none { it == "assignColors" })
        assertEquals(mapOf("work" to "#123456"), vm.colors.value)
    }

    @Test
    fun aFailedReadKeepsTheHashColours() = runTest(dispatcher) {
        sharing.fail["refreshTags"] = VaultFailure(FailureKind.NETWORK)
        val vm = TagColorsViewModel(sharing, vault)
        advanceUntilIdle()
        assertTrue(vm.colors.value.isEmpty())
        assertTrue(sharing.calls.none { it == "assignColors" })
    }

    @Test
    fun thePolicyIsTagColorsNextOverTheRegistry() {
        assertEquals(TagColors.next(listOf("a" to null)), TagColorsViewModel.next(listOf(TagView("a"))))
        assertNull(TagColorsViewModel.next(listOf(TagView("@profile"))))
    }
}
