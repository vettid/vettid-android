package com.vettid.core.testing

import com.vettid.core.data.vault.ReleaseUpdateOffer
import com.vettid.core.data.vault.ReleaseUpdateRepository
import com.vettid.core.data.vault.UpdateProgress
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * TEST ONLY. The release update for ViewModel tests: the offer and the progress are set by the test; every call is
 * recorded in [calls] (the PIN is recorded as given, to check what was approved).
 */
class FakeReleaseUpdates(offer: ReleaseUpdateOffer? = null) : ReleaseUpdateRepository {
    val calls = mutableListOf<String>()
    override val offer = MutableStateFlow(offer)
    override val progress = MutableStateFlow<UpdateProgress?>(null)
    var checkDue = false
    var refreshed = 0

    /** What [start] answers when the check is not due. */
    var startResult = true

    override suspend fun refreshOffer() {
        refreshed++
    }

    override fun ownerCheckDue(): Boolean = checkDue

    override fun start(pin: String, offer: ReleaseUpdateOffer): Boolean {
        calls += "start:$pin:${offer.target.number}"
        return startResult && !checkDue
    }

    override fun retry(pin: String) {
        calls += "retry:$pin"
    }

    override fun abandon(pin: String) {
        calls += "abandon:$pin"
    }

    override fun finish() {
        calls += "finish"
        progress.value = null
    }
}
