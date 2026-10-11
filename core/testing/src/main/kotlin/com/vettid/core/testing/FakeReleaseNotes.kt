package com.vettid.core.testing

import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.ReleaseNotesRepository
import com.vettid.core.data.vault.ReleaseView
import kotlinx.coroutines.CompletableDeferred

/**
 * TEST ONLY. What's new for ViewModel tests: answers [answer] (or waits for [gate] when set); every look is recorded
 * in [asked] as the release number.
 */
class FakeReleaseNotes(var answer: ReleaseNotes = ReleaseNotes.Unavailable) : ReleaseNotesRepository {
    val asked = mutableListOf<Long>()
    var gate: CompletableDeferred<ReleaseNotes>? = null

    override suspend fun whatsNew(target: ReleaseView, between: List<ReleaseView>): ReleaseNotes {
        asked += target.number
        return gate?.await() ?: answer
    }
}
