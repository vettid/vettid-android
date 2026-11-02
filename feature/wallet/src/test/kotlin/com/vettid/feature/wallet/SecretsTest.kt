package com.vettid.feature.wallet

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsTest {
    @Test
    fun wipeWhenDoneWipesAfterTheBodyAndAlsoWhenCancelledBeforeItStarts() = runTest {
        val ran = "secret".toCharArray()
        launch { assertTrue(ran.any { it != '\u0000' }) }.wipeWhenDone(ran).join()
        assertTrue(ran.all { it == '\u0000' })
        val never = "secret".toCharArray()
        val pin = "123456".toCharArray()
        val job = launch(start = CoroutineStart.LAZY) { error("never runs") }.wipeWhenDone(never, pin, null)
        job.cancel()
        job.join()
        assertTrue(never.all { it == '\u0000' } && pin.all { it == '\u0000' })
    }

    @Test
    fun takenIsACopyThatTheStateStringDoesNotShare() {
        val text = "pw"
        val taken = Taken.of(text)
        taken.fill('\u0000')
        assertTrue(text == "pw")
    }
}
