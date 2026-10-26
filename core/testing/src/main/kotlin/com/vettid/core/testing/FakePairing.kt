package com.vettid.core.testing

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

    override suspend fun start() {
        calls += "start"
    }

    override suspend fun approve(sessionSeconds: Int?) {
        calls += "approve:$sessionSeconds"
    }

    override suspend fun reject() {
        calls += "reject"
        val s = state.value.stage
        if (s is PairingStage.Compare) state.value = PairingState(PairingStage.Rejected(told = true))
    }

    override suspend fun cancel() {
        calls += "cancel"
    }

    override fun leave() {
        calls += "leave"
    }

    override fun acknowledge() {
        calls += "acknowledge"
    }
}
