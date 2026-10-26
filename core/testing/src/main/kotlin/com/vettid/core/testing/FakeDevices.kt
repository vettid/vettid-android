package com.vettid.core.testing

import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.DevicesRepository
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * TEST ONLY. An in-memory devices list (ANDROID-PLAN 0.1.53 AA1) for ViewModel tests: [devices] is what `device.list`
 * answers; calls are recorded in [calls]; [fail] makes the next call of a name throw; [bound] is the answer of
 * [removalBoundSeconds].
 */
class FakeDevices : DevicesRepository {
    val calls = mutableListOf<String>()
    val fail = mutableMapOf<String, VaultFailure>()
    var bound = 0L

    override val devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    override val load = MutableStateFlow(ListLoad.LOADED)
    override val removed = MutableStateFlow<DeviceInfo?>(null)

    private fun call(name: String) {
        calls += name
        fail.remove(name)?.let { throw it }
    }

    override suspend fun refresh() = call("refresh")

    override fun refreshQuietly() {
        calls += "refreshQuietly"
    }

    override suspend fun endAccess(deviceId: String) {
        call("endAccess:$deviceId")
        devices.value = devices.value.map { if (it.id == deviceId) it.copy(sessionExpiresAt = null) else it }
    }

    override suspend fun remove(deviceId: String) {
        call("remove:$deviceId")
        removed.value = devices.value.firstOrNull { it.id == deviceId }
        devices.value = devices.value.filterNot { it.id == deviceId }
    }

    override suspend fun removalBoundSeconds(agentId: String): Long {
        call("removalBound:$agentId")
        return bound
    }

    override fun dismissRemoved() {
        calls += "dismissRemoved"
        removed.value = null
    }
}
