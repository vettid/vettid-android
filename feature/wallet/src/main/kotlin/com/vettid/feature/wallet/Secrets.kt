package com.vettid.feature.wallet

import kotlinx.coroutines.Job

/**
 * Taking a secret out of a state (N1): the password and the PIN are typed into `String`s, because Compose's text field needs one for
 * display, and the state is blanked at the moment they are taken ([Taken.of]); from there they travel as `CharArray`s, down to
 * `:core:wallet`, which wipes them when its call ends. These `String` copies cannot be wiped (the JVM gives no way to) and live until
 * the garbage collector takes them: the one the text field showed, the one in the state until it was blanked, and the one the
 * keyboard may keep (the screens use the password class with no learning). [wipeWhenDone] is the view model's own guarantee, for an
 * array whose coroutine is cancelled before it starts.
 */
internal object Taken {
    fun of(secret: String): CharArray = secret.toCharArray()
}

/** Wipes [secrets] (`fill('\u0000')`) when this job completes, however it ends, also when it was cancelled before it began. */
internal fun Job.wipeWhenDone(vararg secrets: CharArray?): Job {
    invokeOnCompletion { secrets.forEach { it?.fill('\u0000') } }
    return this
}
