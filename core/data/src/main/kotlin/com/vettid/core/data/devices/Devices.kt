package com.vettid.core.data.devices

import com.vettid.core.crypto.IkFingerprint
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.deviceSessionEnd
import com.vettid.core.vault.deviceUnlink
import com.vettid.core.vault.leashGrantList
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
     * [name] as shown, by code point (as `RecoveryRefs` does): control, format (bidi marks, tag characters), line and
     * paragraph separators and unassigned code points dropped, white space collapsed, trimmed, cut at [MAX_NAME].
     */
    fun cleanName(name: String?): String {
        val kept = name.orEmpty().codePoints().filter { !hidden(it) }.toArray()
        val collapsed = String(kept, 0, kept.size).replace(WHITE_SPACE, " ").trim()
        val capped = collapsed.codePoints().limit(MAX_NAME.toLong()).toArray()
        return String(capped, 0, capped.size).trim()
    }

    private val WHITE_SPACE = Regex("[\\s\\p{Z}]+")

    private fun hidden(cp: Int): Boolean {
        val type = Character.getType(cp)
        return Character.isISOControl(cp) || type == Character.FORMAT.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt() || type == Character.UNASSIGNED.toInt()
    }

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
                name = cleanName(d.s("name")?.takeIf { it.isNotBlank() } ?: profileName),
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
        val ttl = ((g as? JsonObject)?.get("status_ttl") as? JsonPrimitive)
        when {
            g !is JsonObject -> MAX_STATUS_TTL
            ttl == null -> DEFAULT_STATUS_TTL
            ttl.isString -> MAX_STATUS_TTL
            else -> ttl.longOrNull?.coerceIn(MIN_STATUS_TTL, MAX_STATUS_TTL) ?: MAX_STATUS_TTL
        }
        // A body that does not read as a list of grants is the worst case, never "no permission".
    } ?: listOf(MAX_STATUS_TTL)

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
}

/** [DevicesOps] over the vault client. */
internal class VaultDevicesOps(private val api: VaultApi) : DevicesOps {
    override suspend fun list(): JsonObject = api.deviceList()

    override suspend fun grants(agentId: String?): JsonObject = api.leashGrantList(agentId)

    override suspend fun endAccess(deviceId: String) = api.deviceSessionEnd(deviceId)

    override suspend fun unlink(deviceId: String) = api.deviceUnlink(deviceId)
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
class DevicesManager(
    private val scope: CoroutineScope,
    private val ops: suspend () -> DevicesOps,
    private val self: () -> String? = { null },
    private val onDeviceGone: suspend (String) -> Unit = {},
) : DevicesRepository {
    private val lock = Any()
    private val list = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val loadState = MutableStateFlow(ListLoad.NOT_LOADED)
    private val removedFlow = MutableStateFlow<DeviceInfo?>(null)
    private val reads = Mutex()

    /** Bumped by [clear]: a read that started before it is not applied after it. */
    private var generation = 0

    @Volatile
    private var queued = false

    override val devices: StateFlow<List<DeviceInfo>> = list.asStateFlow()
    override val load: StateFlow<ListLoad> = loadState.asStateFlow()
    override val removed: StateFlow<DeviceInfo?> = removedFlow.asStateFlow()

    /** Forgets everything: the vault locked, was held or wiped. */
    fun clear() {
        synchronized(lock) {
            generation++
            queued = false
            list.value = emptyList()
            removedFlow.value = null
        }
        loadState.value = ListLoad.NOT_LOADED
    }

    /** `sync.event` of kinds that change the list (§10.1). */
    fun onEvent(m: VaultMessage) {
        if (m.type != "sync.event") return
        if (VaultJson.str(m.body, "kind") in LIST_EVENTS) refreshQuietly()
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
        val LIST_EVENTS = setOf("device.paired", "device.unlinked", "device.session", "leash.agent.suspended")
    }
}
