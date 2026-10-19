package com.vettid.core.testing

import com.vettid.core.data.vault.FailureKind
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
    override val offerKnown = MutableStateFlow(offer != null)
    override val progress = MutableStateFlow<UpdateProgress?>(null)
    var checkDue = false

    /** Why the update is held (a change undecided, the PIN uncertain); null when it is not. */
    override val changeHold = MutableStateFlow<FailureKind?>(null)
    var refreshed = 0

    /** What [start] answers when the check is not due. */
    var startResult = true

    /** What [pinGeneration] reads; [generations] records the reading each PIN was handed over with (null: none). */
    override var pinGeneration = 0
    val generations = mutableListOf<Int?>()

    override suspend fun refreshOffer() {
        refreshed++
        offerKnown.value = true
    }

    override fun ownerCheckDue(): Boolean = checkDue

    override fun start(pin: String, offer: ReleaseUpdateOffer, generation: Int?): Boolean {
        calls += "start:$pin:${offer.target.number}"
        generations += generation
        return startResult && !checkDue
    }

    override fun retry(pin: String, generation: Int?) {
        calls += "retry:$pin"
        generations += generation
    }

    override fun abandon(pin: String, generation: Int?) {
        calls += "abandon:$pin"
        generations += generation
    }

    override fun finish() {
        calls += "finish"
        progress.value = null
    }
}
