package com.vettid.core.testing

import com.vettid.core.data.devices.AgentChange
import com.vettid.core.data.devices.AgentPermissions
import com.vettid.core.data.devices.AgentResumption
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.PermissionRemoval
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

    override suspend fun refresh() {
        try {
            call("refresh")
            load.value = ListLoad.LOADED
        } catch (e: VaultFailure) {
            load.value = ListLoad.FAILED
            throw e
        }
    }

    override fun refreshQuietly() {
        calls += "refreshQuietly"
    }

    /** When set, [endAccess] waits for it before it answers (a request in flight). */
    var endGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun endAccess(deviceId: String) {
        endGate?.await()
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

    // --- AA3: an agent's permissions ---

    /** The grants [refreshPermissions] reads, by agent id (what the vault "holds"). */
    val grantsHeld = mutableMapOf<String, AgentPermissions>()
    override val permissions = MutableStateFlow<Map<String, AgentPermissions>>(emptyMap())
    override val changes = kotlinx.coroutines.flow.MutableSharedFlow<AgentChange>(extraBufferCapacity = 16)

    /** What [removePermission] and [resume] answer when they do not fail. */
    var removal = PermissionRemoval.REMOVED
    var resumption = AgentResumption.RESUMED

    /** When set, [refreshPermissions] waits for the next gate before it answers (a slow older read). */
    val permissionGates = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Unit>>()

    override suspend fun refreshPermissions(agentId: String) {
        call("refreshPermissions:$agentId")
        val snapshot = grantsHeld[agentId] ?: AgentPermissions(emptyList(), false)
        permissionGates.removeFirstOrNull()?.await()
        permissions.value = permissions.value + (agentId to snapshot)
        devices.value = devices.value.map { if (it.id == agentId && it.isAgent) it.copy(paused = snapshot.suspended) else it }
    }

    override suspend fun removePermission(grantId: String): PermissionRemoval {
        call("removePermission:$grantId")
        grantsHeld.keys.toList().forEach { agent ->
            val held = grantsHeld.getValue(agent)
            grantsHeld[agent] = held.copy(others = held.others.filterNot { it.grantId == grantId })
        }
        return removal
    }

    override suspend fun resume(agentId: String): AgentResumption {
        call("resume:$agentId")
        grantsHeld[agentId]?.let { grantsHeld[agentId] = it.copy(suspended = false) }
        return resumption
    }
}
