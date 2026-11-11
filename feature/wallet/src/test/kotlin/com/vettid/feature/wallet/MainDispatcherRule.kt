package com.vettid.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Sets [Dispatchers.Main] for a test, and resets it after. A ViewModel a test registers with [track] is cleared first and
 * its coroutines are let finish on the test's scheduler: one left running (work that returns from the IO dispatcher after
 * the test) would otherwise dispatch to Main while the next test sets it, and `TestMainDispatcher` throws there
 * ("used concurrently with setting it"), failing whichever test runs next (GC #7753).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    private val store = ViewModelStore()
    private val jobs = mutableListOf<Job>()

    /** Registers [vm] to be cleared, and its coroutines finished, before Main is reset. */
    fun <T : ViewModel> track(vm: T): T {
        store.put("vm${jobs.size}", vm)
        jobs += vm.viewModelScope.coroutineContext.job
        return vm
    }

    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) {
        try {
            store.clear()
            val deadline = System.nanoTime() + SETTLE_NANOS
            while (jobs.any { it.isActive } && System.nanoTime() < deadline) {
                dispatcher.scheduler.advanceUntilIdle()
                if (jobs.any { it.isActive }) Thread.sleep(POLL_MS)
            }
            dispatcher.scheduler.advanceUntilIdle()
        } finally {
            jobs.clear()
            Dispatchers.resetMain()
        }
    }

    private companion object {
        const val SETTLE_NANOS = 10_000_000_000L
        const val POLL_MS = 5L
    }
}
