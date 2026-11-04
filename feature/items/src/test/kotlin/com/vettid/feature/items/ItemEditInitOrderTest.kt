// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.items

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.ItemFieldView
import com.vettid.core.testing.FakeItems
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * viewModelScope runs on Main.immediate: the load `init` starts can finish inside the constructor (here `items.get`
 * never suspends). What it stored must survive the rest of the constructor, so an untouched field is still sent as stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ItemEditInitOrderTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val items = FakeItems()

    @Test
    fun theStoredFieldsSurviveALoadThatFinishesInTheConstructor() = runTest {
        items.add(
            FakeItems.item("01P", "Mum").copy(fields = listOf(ItemFieldView("f1", "Phone", "phone", FieldValue.Text("+44 20 7946 0958")))),
        )
        val vm = ItemEditViewModel(
            SavedStateHandle(mapOf(ItemEditRoute.ARG_ITEM to "01P")),
            items,
            RuntimeEnvironment.getApplication(),
            com.vettid.core.testing.FakeSharing(),
            com.vettid.core.testing.FakeSocial(),
        )
        vm.setName("Mother")
        vm.save()
        advanceUntilIdle()
        assertEquals(FieldValue.Text("+44 20 7946 0958"), items.stored.getValue("01P").fields.single().value)
    }
}
