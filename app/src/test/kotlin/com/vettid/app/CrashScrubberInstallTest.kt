package com.vettid.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.vettid.core.data.vault.CrashScrubber
import com.vettid.core.data.vault.RelaySafetyNet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The owner's rule (2026-10-23): the app's first code, in every process, installs the handler that scrubs a crash
 * before Android's handler sees it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CrashScrubberInstallTest {
    private var before: Thread.UncaughtExceptionHandler? = null
    private val received = mutableListOf<Throwable>()

    @Before
    fun setUp() {
        before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> received += e }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(before)
    }

    @Test
    fun attachBaseContextInstallsTheHandler() {
        val app = VettIdApplication()
        val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        // VettIdApplication's own override (the method is looked up on ContextWrapper, called virtually).
        attach.invoke(app, RuntimeEnvironment.getApplication())

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(handler is CrashScrubber)
        handler!!.uncaughtException(Thread.currentThread(), IllegalStateException("Alice Example"))
        val s = received.single()
        assertTrue(s is RelaySafetyNet.Scrubbed)
        assertEquals(IllegalStateException::class.java.name, s.message)
    }
}
