package com.vettid.core.testing

import com.vettid.core.data.devices.PairRole
import com.vettid.core.data.devices.PairingRepository
import com.vettid.core.data.devices.PairingStage
import com.vettid.core.data.devices.PairingState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * TEST ONLY. A pairing (ANDROID-PLAN 0.1.53 AA2) for ViewModel tests: set [state] to put the screen at a stage; calls are
 * recorded in [calls] (approvals as `approve:<seconds>`).
 */
class FakePairing : PairingRepository {
    val calls = mutableListOf<String>()

    override val state = MutableStateFlow(PairingState())

    override suspend fun start(role: PairRole, owner: Any?) {
        calls += if (role == PairRole.AGENT) "start" else "start:${role.wire}"
        state.value = state.value.copy(role = role)
    }

    /** The pairing id the last approval was bound to. */
    var approvedId: String? = null

    override suspend fun approve(sessionSeconds: Int?, pairingId: String?) {
        calls += "approve:$sessionSeconds"
        approvedId = pairingId
    }

    override suspend fun reject() {
        calls += "reject"
        val s = state.value.stage
        if (s is PairingStage.Compare) state.value = PairingState(PairingStage.Rejected(told = true))
    }

    override suspend fun cancel() {
        calls += "cancel"
    }

    override fun leave(owner: Any?) {
        calls += "leave"
    }

    override fun acknowledge(owner: Any?) {
        calls += "acknowledge"
    }
}
