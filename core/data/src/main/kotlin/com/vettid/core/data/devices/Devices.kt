package com.vettid.core.data.devices

import com.vettid.core.crypto.IkFingerprint
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.deviceSessionEnd
import com.vettid.core.vault.deviceUnlink
import com.vettid.core.vault.leashAgentResume
import com.vettid.core.vault.leashGrantList
import com.vettid.core.vault.leashGrantRevoke
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * A device of the member's vault as `device.list` shows it (VAULT-MESSAGING §10.3; ANDROID-PLAN 0.1.53 "Devices &
 * agents"). [kind] is read as the role ([isAgent], [isDesktop]); §10.3 does not list its values, so any other value is
 * listed with the desktops under its own text (decision 14d). [name] is the device's own, self-asserted `name`
 * (`profile.name`): the app never renames a device (decision 11). Held in memory only, while the vault is open.
 */
data class DeviceInfo(
    val id: String,
    val kind: String,
    val name: String,
    /** The device's identity key as sent (base64); shown as [fingerprint]. */
    val ik: String?,
    val createdAt: Instant?,
    val lastActiveAt: Instant?,
    /** When the access session ends; null without one. Past it, the device has no access ([hasAccess]). */
    val sessionExpiresAt: Instant?,
    /** This phone's own entry: listed first, with no actions (it leaves only by a transfer or a recovery, §6.7). */
    val thisPhone: Boolean = false,
    /** The vault paused this agent (`leash.grant.list`'s `suspended`, §10.11). */
    val paused: Boolean = false,
) {
    val isAgent: Boolean get() = !thisPhone && kind == KIND_AGENT

    /** Everything that is neither this phone nor an agent: a desktop, or a kind this app does not know. */
    val isDesktop: Boolean get() = !thisPhone && kind != KIND_AGENT

    /** The key's fingerprint (8 groups of 4 hex digits, as on a connection's page); null when `ik` does not read. */
    val fingerprint: String? get() = ik?.let { IkFingerprint.formatB64(it) }

    /**
     * Whether End access and Remove are offered: never for this phone, nor for any other `app` entry (the vault refuses
     * `device.unlink` of the app with `forbidden`, §10.3).
     */
    val actionable: Boolean get() = !thisPhone && kind != KIND_APP

    /** Whether [sessionExpiresAt] is still ahead at [now]. */
    fun hasAccess(now: Instant): Boolean = sessionExpiresAt?.isAfter(now) == true

    companion object {
        const val KIND_AGENT = "agent"
        const val KIND_DESKTOP = "desktop"
        const val KIND_APP = "app"
    }
}

/**
 * `device.list` and `leash.grant.list` bodies (§10.3, §10.11) as models. A body that does not read yields no entry: the
 * list never shows what it cannot name, and never an entry without an `id`.
 */
object DeviceWire {
    /** The longest name shown (code points); longer ones are cut. A self-asserted name is data, not text to trust. */
    const val MAX_NAME = 64

    /**
     * [name] as shown: the one cleaning step for received text (`AccountNames.cleaned`, VAULT-MESSAGING 0.76.0 §10.8),
     * cut at [MAX_NAME] code points without splitting a grapheme cluster. Empty when nothing shows.
     */
    fun cleanName(name: String?): String = AccountNames.cleanedCapped(name, MAX_NAME).orEmpty()

    private fun JsonObject.s(k: String) = VaultJson.str(this, k)

    private fun instant(s: String?): Instant? = try {
        s?.let { Instant.parse(it) }
    } catch (_: DateTimeParseException) {
        null
    }

    /** The entries of a `device.list` answer, in the vault's order; [self] is this phone's device id. */
    fun devices(body: JsonObject, self: String?, paused: Set<String> = emptySet()): List<DeviceInfo> =
        (body["devices"] as? JsonArray).orEmpty().mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val id = d.s("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val kind = d.s("kind").orEmpty()
            val profileName = (d["profile"] as? JsonObject)?.s("name")
            DeviceInfo(
                id = id,
                kind = kind,
                name = cleanName(AccountNames.cleaned(d.s("name")) ?: profileName),
                ik = d.s("ik")?.takeIf { it.isNotEmpty() },
                createdAt = instant(d.s("created_at")),
                lastActiveAt = instant(d.s("last_active_at")),
                sessionExpiresAt = instant(d.s("session_expires_at")),
                thisPhone = self != null && id == self,
                paused = kind == DeviceInfo.KIND_AGENT && id in paused,
            )
        }

    /**
     * The order of the list (ANDROID-PLAN 0.1.53 AA1): this phone first, then agents, then desktops, each newest
     * `created_at` first (an entry without one last, keeping the vault's order).
     */
    fun ordered(devices: List<DeviceInfo>): List<DeviceInfo> {
        // A missing `created_at` compares lowest, so descending puts it last; the sort is stable.
        return devices.sortedWith(
            compareBy<DeviceInfo> { if (it.thisPhone) 0 else if (it.isAgent) 1 else 2 }.thenByDescending { it.createdAt },
        )
    }

    /** `suspended` of a `leash.grant.list` answer (agent ids). */
    fun suspended(body: JsonObject): Set<String> =
        (body["suspended"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()

    /**
     * The `status_ttl` of each grant in a `leash.grant.list{agent_id}` answer, in seconds (§10.11: 60-3,600, default
     * 900 when absent). A grant that does not read counts as the longest.
     */
    fun statusTtls(body: JsonObject): List<Long> = (body["grants"] as? JsonArray)?.map { g ->
        if (g is JsonObject) statusTtl(g) else MAX_STATUS_TTL
        // A body that does not read as a list of grants is the worst case, never "no permission".
    } ?: listOf(MAX_STATUS_TTL)

    /** One grant's `status_ttl` in seconds: 60-3,600, 900 when absent, the longest when it does not read. */
    fun statusTtl(g: JsonObject): Long {
        val ttl = g["status_ttl"] as? JsonPrimitive
        return when {
            ttl == null -> DEFAULT_STATUS_TTL
            ttl.isString -> MAX_STATUS_TTL
            else -> ttl.longOrNull?.coerceIn(MIN_STATUS_TTL, MAX_STATUS_TTL) ?: MAX_STATUS_TTL
        }
    }

    const val MIN_STATUS_TTL = 60L
    const val DEFAULT_STATUS_TTL = 900L
    const val MAX_STATUS_TTL = 3_600L

    /** §10.11's revocation bound: `status_ttl` plus 60 s of skew. */
    const val SKEW_SECONDS = 60L
}

/** The vault calls behind [DevicesManager] (§10.3, §10.11), so that it can be tested without a vault. */
interface DevicesOps {
    suspend fun list(): JsonObject

    /** `leash.grant.list{agent_id?}`. */
    suspend fun grants(agentId: String?): JsonObject

    suspend fun endAccess(deviceId: String)

    suspend fun unlink(deviceId: String)

    /** `leash.grant.revoke{grant_id}` (AA3). */
    suspend fun revokeGrant(grantId: String)

    /** `leash.agent.resume{agent_id}` (AA3). */
    suspend fun resumeAgent(agentId: String)
}

/** [DevicesOps] over the vault client. */
internal class VaultDevicesOps(private val api: VaultApi) : DevicesOps {
    override suspend fun list(): JsonObject = api.deviceList()

    override suspend fun grants(agentId: String?): JsonObject = api.leashGrantList(agentId)

    override suspend fun endAccess(deviceId: String) = api.deviceSessionEnd(deviceId)

    override suspend fun unlink(deviceId: String) = api.deviceUnlink(deviceId)

    override suspend fun revokeGrant(grantId: String) = api.leashGrantRevoke(grantId)

    override suspend fun resumeAgent(agentId: String) = api.leashAgentResume(agentId)
}

/**
 * The member's devices and agents (ANDROID-PLAN 0.1.53 "Devices & agents", AA1; VAULT-MESSAGING §10.3). Every failure
 * is a [VaultFailure].
 */
interface DevicesRepository {
    /** This phone, then agents, then desktops ([DeviceWire.ordered]); empty until read, and after a lock. */
    val devices: StateFlow<List<DeviceInfo>>

    val load: StateFlow<ListLoad>

    /** The device the member just removed, for the list's "<name> was removed"; until [dismissRemoved]. */
    val removed: StateFlow<DeviceInfo?>

    /** Reads the list (`device.list`, and `leash.grant.list` for the paused agents). Throws the vault's failure. */
    suspend fun refresh()

    /** A read in the background: at most one in flight and one queued behind it. */
    fun refreshQuietly()

    /** `device.session.end`: the device's access ends and its waiting requests are declined. */
    suspend fun endAccess(deviceId: String)

    /** `device.unlink`: the device loses access at once; its permissions and share rules go with it. */
    suspend fun remove(deviceId: String)

    /**
     * The longest time, in seconds, that a service shown one of the agent's permissions may still accept it after the
     * agent is removed: the longest `status_ttl` of its grants plus 60 s (§10.11); 0 when it has none. When the vault
     * cannot be asked, the worst case (3,600 s plus 60 s): the text is never shorter than the truth.
     */
    suspend fun removalBoundSeconds(agentId: String): Long

    fun dismissRemoved()

    /**
     * An agent's grants that are not share rules, and whether it is paused (`leash.grant.list{agent_id}`, AA3), by agent id;
     * memory only, cleared with the list. The agent's [DeviceInfo.paused] follows each read.
     */
    val permissions: StateFlow<Map<String, AgentPermissions>>

    /** What `sync.event`s say changed for an agent's page ([AgentChange]). */
    val changes: SharedFlow<AgentChange>

    /** Reads [agentId]'s grants into [permissions]. Throws the vault's failure; an older answer never replaces a newer one. */
    suspend fun refreshPermissions(agentId: String)

    /**
     * `leash.grant.revoke{grant_id}`: no unlock window, nothing signed. `not_found` is [PermissionRemoval.ALREADY_GONE].
     * The agent's grants are read again after either; any other failure is thrown and nothing is read.
     */
    suspend fun removePermission(agentId: String, grantId: String): PermissionRemoval

    /**
     * `leash.agent.resume{agent_id}`: no unlock window. `not_found` is [AgentResumption.NOT_PAUSED]. The grants are read
     * again after either; any other failure is thrown and nothing is read.
     */
    suspend fun resume(agentId: String): AgentResumption
}

/**
 * [DevicesRepository] over [DevicesOps]. The list is kept in memory only, while the vault is open (like the feed):
 * cleared on lock, on entering the hold, on a wipe and with the process (ANDROID-PLAN 0.1.53 AA1). It is re-read after
 * a `sync.event` of kind `device.paired`, `device.unlinked`, `device.session` or `leash.agent.suspended`
 * ([onEvent]), coalesced.
 *
 * [onDeviceGone] runs when a device's access ended or the device was removed here: its entries leave Approvals at once
 * (the vault declines them, §6.8).
 */
@Suppress("TooManyFunctions")
class DevicesManager(
    private val scope: CoroutineScope,
    private val ops: suspend () -> DevicesOps,
    private val self: () -> String? = { null },
    private val onDeviceGone: suspend (String) -> Unit = {},
    /** Also forgotten with the list (the pairing held for it, AA2): every lock, hold, wipe and deletion clears both. */
    private val onClear: () -> Unit = {},
) : DevicesRepository {
    private val lock = Any()
    private val list = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val loadState = MutableStateFlow(ListLoad.NOT_LOADED)
    private val removedFlow = MutableStateFlow<DeviceInfo?>(null)
    private val reads = Mutex()
    private val permissionFlow = MutableStateFlow<Map<String, AgentPermissions>>(emptyMap())
    private val changeFlow =
        MutableSharedFlow<AgentChange>(extraBufferCapacity = CHANGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The number of the latest permission read started, and of the one applied last, per agent (under [lock]). */
    private var permissionReads = 0L
    private val permissionApplied = mutableMapOf<String, Long>()

    /** Bumped by [clear]: a read that started before it is not applied after it. */
    private var generation = 0

    @Volatile
    private var queued = false

    override val devices: StateFlow<List<DeviceInfo>> = list.asStateFlow()
    override val load: StateFlow<ListLoad> = loadState.asStateFlow()
    override val removed: StateFlow<DeviceInfo?> = removedFlow.asStateFlow()
    override val permissions: StateFlow<Map<String, AgentPermissions>> = permissionFlow.asStateFlow()
    override val changes: SharedFlow<AgentChange> = changeFlow.asSharedFlow()

    /** Forgets everything: the vault locked, was held or wiped. */
    fun clear() {
        synchronized(lock) {
            generation++
            queued = false
            // Not loaded first, then empty, under the lock a read applies its result under: LOADED always means a list
            // that was read (a screen that sees an empty list while LOADED would take it for a session that ended).
            loadState.value = ListLoad.NOT_LOADED
            list.value = emptyList()
            removedFlow.value = null
            permissionFlow.value = emptyMap()
            permissionApplied.clear()
        }
        onClear()
    }

    /** `sync.event` of kinds that change the list (§10.1). */
    fun onEvent(m: VaultMessage) {
        if (m.type != "sync.event") return
        if (VaultJson.str(m.body, "kind") in LIST_EVENTS) refreshQuietly()
        // An open agent's page reads its own grants again (AA3); with none open nothing listens.
        AgentChange.of(m)?.let { changeFlow.tryEmit(it) }
    }

    override fun refreshQuietly() {
        if (queued) return
        queued = true
        scope.launch { runCatching { read(fromQueue = true) } }
    }

    override suspend fun refresh() = read(fromQueue = false)

    private suspend fun read(fromQueue: Boolean) {
        reads.withLock {
            if (fromQueue) queued = false
            val gen = synchronized(lock) { generation }
            if (list.value.isEmpty()) loadState.value = ListLoad.LOADING
            try {
                val body = vaultGuard(resend = true) { ops().list() }
                val me = self()
                val parsed = DeviceWire.devices(body, me)
                // The paused agents: only asked when there is an agent; a failure leaves the chips off, never the list.
                val paused = if (parsed.any { it.isAgent }) {
                    runCatching { DeviceWire.suspended(vaultGuard(resend = true) { ops().grants(null) }) }.getOrDefault(emptySet())
                } else {
                    emptySet()
                }
                val shown = DeviceWire.ordered(parsed.map { it.copy(paused = it.isAgent && it.id in paused) })
                synchronized(lock) {
                    if (gen != generation) return@withLock
                    list.value = shown
                    loadState.value = ListLoad.LOADED
                }
            } catch (e: VaultFailure) {
                synchronized(lock) { if (gen == generation) loadState.value = ListLoad.FAILED }
                throw e
            }
        }
    }

    override suspend fun endAccess(deviceId: String) {
        guardActionable(deviceId)
        val gen = synchronized(lock) { generation }
        vaultGuard { ops().endAccess(deviceId) }
        // A lock came meanwhile: the list is not this vault-open's any more; Approvals are still cleaned.
        synchronized(lock) {
            if (gen == generation) {
                list.value = list.value.map { if (it.id == deviceId) it.copy(sessionExpiresAt = null) else it }
            }
        }
        deviceGone(deviceId)
        refreshQuietly()
    }

    override suspend fun remove(deviceId: String) {
        guardActionable(deviceId)
        val known = list.value.firstOrNull { it.id == deviceId }
        val gen = synchronized(lock) { generation }
        vaultGuard { ops().unlink(deviceId) }
        synchronized(lock) {
            if (gen == generation) {
                list.value = list.value.filterNot { it.id == deviceId }
                removedFlow.value = known
            }
        }
        deviceGone(deviceId)
        refreshQuietly()
    }

    override suspend fun removalBoundSeconds(agentId: String): Long {
        val ttls = try {
            DeviceWire.statusTtls(vaultGuard(resend = true) { ops().grants(agentId) })
        } catch (_: VaultFailure) {
            return DeviceWire.MAX_STATUS_TTL + DeviceWire.SKEW_SECONDS
        }
        return if (ttls.isEmpty()) 0 else ttls.max() + DeviceWire.SKEW_SECONDS
    }

    override fun dismissRemoved() {
        removedFlow.value = null
    }

    override suspend fun refreshPermissions(agentId: String) {
        val (gen, mine) = synchronized(lock) { generation to ++permissionReads }
        val read = PermissionWire.permissions(vaultGuard(resend = true) { ops().grants(agentId) }, agentId)
        synchronized(lock) {
            // A lock came meanwhile, or a newer read was applied first: this answer is not shown.
            if (gen != generation || mine < (permissionApplied[agentId] ?: 0L)) return
            permissionApplied[agentId] = mine
            permissionFlow.value = permissionFlow.value + (agentId to read)
            list.value = list.value.map { if (it.id == agentId && it.isAgent) it.copy(paused = read.suspended) else it }
        }
    }

    override suspend fun removePermission(agentId: String, grantId: String): PermissionRemoval {
        val outcome = try {
            vaultGuard { ops().revokeGrant(grantId) }
            PermissionRemoval.REMOVED
        } catch (e: VaultFailure) {
            if (e.kind != FailureKind.NOT_FOUND) throw e
            PermissionRemoval.ALREADY_GONE
        }
        readAfter(agentId)
        return outcome
    }

    override suspend fun resume(agentId: String): AgentResumption {
        val outcome = try {
            vaultGuard { ops().resumeAgent(agentId) }
            AgentResumption.RESUMED
        } catch (e: VaultFailure) {
            if (e.kind != FailureKind.NOT_FOUND) throw e
            AgentResumption.NOT_PAUSED
        }
        readAfter(agentId)
        return outcome
    }

    /** The read after a change here: the change is done, so a read that fails must not turn it into a failed request. */
    private suspend fun readAfter(agentId: String) {
        try {
            refreshPermissions(agentId)
        } catch (_: VaultFailure) {
            // The next event or the next return to the page reads again.
        }
    }

    /** The app's own entry, and any other `app`, has no actions (`forbidden` from the vault): nothing is sent for it. */
    private fun guardActionable(deviceId: String) {
        val own = self()
        if (own != null && deviceId == own) throw VaultFailure(FailureKind.OTHER, "forbidden")
        if (list.value.firstOrNull { it.id == deviceId }?.actionable == false) throw VaultFailure(FailureKind.OTHER, "forbidden")
    }

    /** The vault has done it: a failure of the Approvals clean-up must not turn it into a failed request. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun deviceGone(id: String) {
        try {
            onDeviceGone(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next read of Approvals settles it.
        }
    }

    private companion object {
        const val CHANGE_BUFFER = 64
        val LIST_EVENTS = setOf("device.paired", "device.unlinked", "device.session", "leash.agent.suspended")
    }
}
