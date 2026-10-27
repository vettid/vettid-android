package com.vettid.core.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class ToneSlotTest {
    private val log = mutableListOf<String>()
    private var n = 0
    private var failStart = false

    private fun slot() = ToneSlot<String>(
        begin = { if (failStart) null else "tone${++n}".also { log += "start $it" } },
        end = { log += "stop $it" },
    )

    @Test
    fun sayingOnTwiceStartsOnce() {
        val s = slot()
        s.set(true)
        s.set(true)
        assertEquals(listOf("start tone1"), log)
    }

    @Test
    fun sayingOffWithoutOnDoesNothing() {
        val s = slot()
        s.set(false)
        s.set(false)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun offStopsOnceAndOnStartsANewOne() {
        val s = slot()
        s.set(true)
        s.set(false)
        s.set(false)
        s.set(true)
        assertEquals(listOf("start tone1", "stop tone1", "start tone2"), log)
    }

    @Test
    fun aToneThatCouldNotStartIsTriedAgain() {
        val s = slot()
        failStart = true
        s.set(true)
        failStart = false
        s.set(true)
        assertEquals(listOf("start tone1"), log)
    }
}
