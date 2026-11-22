package com.vettid.core.data.devices

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.data.votes.WindowNotClosed
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultOpException
import com.vettid.core.vault.leashItemList
import com.vettid.core.vault.leashItemSet
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** The vault calls behind [ItemWaysManager]: the answers as their exact bytes, for the strict parser. */
interface ItemWaysOps {
    suspend fun list(agentId: String, itemId: String?): ByteArray

    suspend fun set(body: JsonObject): ByteArray
}

/** [ItemWaysOps] over the vault client. */
internal class VaultItemWaysOps(private val api: VaultApi) : ItemWaysOps {
    override suspend fun list(agentId: String, itemId: String?): ByteArray = api.leashItemList(agentId, itemId)

    override suspend fun set(body: JsonObject): ByteArray = api.leashItemSet(body)
}

/** What a save came to (ANDROID-PLAN 0.1.137 AA7 "Saving"), each with its own text on the item's page. */
sealed interface ItemWaysSave {
    /** "Saved": the page shows [read], the vault's answer, never its own copy. */
    data class Saved(val read: ItemWaysRead) : ItemWaysSave

    /** The save widens and no password was given: the unlock window prompt comes first. */
    data object NeedsPassword : ItemWaysSave

    /** `credential_locked` to a save the app judged narrowing: the prompt, then the same request once. */
    data object CredentialLocked : ItemWaysSave

    /** A second `credential_locked`: "Your credential locked again before the settings were saved. Try again." */
    data object LockedAgain : ItemWaysSave

    /** `credential_locked` to a read-only page's narrowing save: nothing is resent ("…so it was not saved. Update VettID…"). */
    data object ReadOnlyLocked : ItemWaysSave

    /** `conflict`: "Another device changed these settings. Here are the current ones." (the page reloads). */
    data object Conflict : ItemWaysSave

    /** `not_found`: "<agent> can no longer use this item: none of its rules includes it." */
    data object NotIncluded : ItemWaysSave

    /** `bad_request`: "These settings could not be saved. Your vault answered: <error text>." */
    data class BadRequest(val message: String) : ItemWaysSave

    /** `owner_check_required`: the owner-check screen. */
    data object OwnerCheck : ItemWaysSave

    /** An open credential alarm (the unlock's `credential_frozen` / `rotation_required`): "Settings can't be widened…". */
    data object AlarmOpen : ItemWaysSave

    /** The prompt's own refusals (a wrong password, a backoff): shown on the prompt, which stays. */
    data class PasswordRefused(val failure: VaultFailure) : ItemWaysSave

    /** Any other failure, with its error text. */
    data class Failed(val failure: VaultFailure) : ItemWaysSave
}

/**
 * How an agent may use its items (ANDROID-PLAN 0.1.137 AA7; VAULT-MESSAGING §10.11): `leash.item.list` and
 * `leash.item.set`. The list is held in memory only, per agent, and cleared at lock and at the hold ([clear]). A save that
 * widens ([ItemWaysRules.widens]) asks for the password every time and opens the unlock window with it (decision 4, as
 * for permissions), and the window is locked again after the save when the prompt opened it; a narrowing save is sent
 * without it.
 */
class ItemWaysManager(
    private val ops: suspend () -> ItemWaysOps,
    /** The credential's unlock window: until when it is open, as this app opened it. */
    private val windowUntil: () -> Instant? = { null },
    private val openWindow: suspend (String) -> Unit = {},
    private val closeWindow: suspend () -> Unit = {},
    /** The window's length, for the notice of a lock that failed. */
    private val unlockTtl: suspend () -> Int? = { null },
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private val held = MutableStateFlow<Map<String, List<ItemWaysRead>>>(emptyMap())
    private val notClosed = MutableStateFlow<WindowNotClosed?>(null)

    /** Bumped by [clear]: an answer to a request sent before it is not kept. */
    private var generation = 0

    /** The items each agent's rules include, as last read (agent id to its list). */
    val items: StateFlow<Map<String, List<ItemWaysRead>>> = held.asStateFlow()

    /** A save opened the window and could not lock it again (the votes' notice, with **Close it now**). */
    val windowNotClosed: StateFlow<WindowNotClosed?> = notClosed.asStateFlow()

    /** At lock, the hold and a wipe: nothing is kept, and nothing read before it is kept after it. */
    fun clear() {
        synchronized(lock) {
            generation++
            held.value = emptyMap()
        }
    }

    /** **Close it now**: locks the window; the notice goes only when that went through. */
    suspend fun closeWindowNow() {
        withContext(NonCancellable) { closeWindow() }
        notClosed.value = null
    }

    /** Reads the items [agentId]'s rules include (`leash.item.list{agent_id}`) and holds them. */
    suspend fun list(agentId: String): List<ItemWaysRead> {
        val gen = synchronized(lock) { generation }
        val read = ItemWaysRules.list(vaultGuard(resend = true) { ops().list(agentId, null) }, agentId) ?: throw unreadable()
        synchronized(lock) {
            if (gen != generation) throw VaultFailure(FailureKind.OTHER, CODE_VOIDED)
            held.value = held.value + (agentId to read)
        }
        return read
    }

    /** Reads one item's settings (`leash.item.list{agent_id, item_id}`): null when no rule of the agent includes it. */
    suspend fun read(agentId: String, itemId: String): ItemWaysRead? {
        val gen = synchronized(lock) { generation }
        val answer = vaultGuard(resend = true) { ops().list(agentId, itemId) }
        val read = (ItemWaysRules.list(answer, agentId) ?: throw unreadable()).firstOrNull { it.itemId == itemId }
        synchronized(lock) {
            if (gen != generation) throw VaultFailure(FailureKind.OTHER, CODE_VOIDED)
            val list = held.value[agentId]
            if (list != null) {
                val updated = if (read == null) list.filter { it.itemId != itemId } else list.map { if (it.itemId == itemId) read else it }
                held.value = held.value + (agentId to updated)
            }
        }
        return read
    }

    /**
     * Saves [next] over [cur] (AA7 "Saving"). A widening needs [password] ([ItemWaysSave.NeedsPassword] without it); a
     * narrowing is sent without one. [resent] is true when this request follows one prompt after `credential_locked`.
     */
    @Suppress("ReturnCount", "NestedBlockDepth") // each early answer is one of the plan's outcomes
    suspend fun save(cur: ItemWaysRead, next: ItemWays, password: String?, resent: Boolean = false): ItemWaysSave {
        // A read-only page saves only through turnOff: nothing is sent.
        val base = cur.ways ?: return ItemWaysSave.Failed(unreadable())
        // Settings of another item can't be compared with these: judged a widening (AA7: when the app cannot tell).
        val sameItem = next.agentId == base.agentId && next.itemId == base.itemId
        if (password == null && (!sameItem || ItemWaysRules.widens(base, next))) return ItemWaysSave.NeedsPassword
        // Open only with a margin (clock skew): a borderline window may end before the save, which then opens its own.
        val toLock = password != null && windowUntil()?.isAfter(now().plusSeconds(WINDOW_MARGIN_SECONDS)) != true
        var opened = toLock
        try {
            if (password != null) {
                try {
                    openWindow(password)
                } catch (e: VaultFailure) {
                    if (e.kind in DEFINITE_REFUSALS) opened = false
                    return unlockRefused(e)
                }
            }
            return send(next.setBody(), resent, readOnly = false)
        } finally {
            if (opened) closeOpenedWindow()
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun closeOpenedWindow() {
        withContext(NonCancellable) {
            try {
                closeWindow()
            } catch (e: Exception) {
                notClosed.value = WindowNotClosed(runCatching { unlockTtl() }.getOrNull()?.toLong())
            }
        }
    }

    /**
     * A read-only page's save (AA7 "Fail closed"): [way] turned off, everything else as read. It only narrows, so it is
     * never followed by the password prompt: `credential_locked` is [ItemWaysSave.ReadOnlyLocked] and nothing is resent.
     */
    suspend fun turnOff(cur: ItemWaysRead, way: String): ItemWaysSave {
        val body = ItemWaysRules.withoutWay(cur, way) ?: return ItemWaysSave.Failed(unreadable())
        return send(body, resent = false, readOnly = true)
    }

    @Suppress("ReturnCount")
    private suspend fun send(body: JsonObject, resent: Boolean, readOnly: Boolean): ItemWaysSave {
        val gen = synchronized(lock) { generation }
        val answer = try {
            vaultGuard { ops().set(body) }
        } catch (e: VaultFailure) {
            return refused(e, resent, readOnly)
        }
        val read = ItemWaysRules.one(answer) ?: return ItemWaysSave.Failed(unreadable())
        synchronized(lock) {
            // Saved either way (the vault answered); only the list held from before a clear is not touched.
            if (gen == generation) {
                held.value[read.agentId]?.let { list ->
                    held.value = held.value + (read.agentId to list.map { if (it.itemId == read.itemId) read else it })
                }
            }
        }
        return ItemWaysSave.Saved(read)
    }

    private fun refused(e: VaultFailure, resent: Boolean, readOnly: Boolean): ItemWaysSave = when {
        e.kind == FailureKind.CREDENTIAL_LOCKED -> when {
            readOnly -> ItemWaysSave.ReadOnlyLocked
            resent -> ItemWaysSave.LockedAgain
            else -> ItemWaysSave.CredentialLocked
        }
        e.kind == FailureKind.CONFLICT -> ItemWaysSave.Conflict
        e.kind == FailureKind.NOT_FOUND -> ItemWaysSave.NotIncluded
        e.kind == FailureKind.OWNER_CHECK_REQUIRED -> ItemWaysSave.OwnerCheck
        e.kind == FailureKind.OTHER && e.code == CODE_BAD_REQUEST ->
            ItemWaysSave.BadRequest((e.cause as? VaultOpException)?.detail.orEmpty())
        else -> ItemWaysSave.Failed(e)
    }

    private fun unlockRefused(e: VaultFailure): ItemWaysSave = when (e.kind) {
        FailureKind.CREDENTIAL_FROZEN, FailureKind.ROTATION_REQUIRED -> ItemWaysSave.AlarmOpen
        FailureKind.OWNER_CHECK_REQUIRED -> ItemWaysSave.OwnerCheck
        FailureKind.BAD_PASSWORD, FailureKind.BACKOFF -> ItemWaysSave.PasswordRefused(e)
        else -> ItemWaysSave.Failed(e)
    }

    companion object {
        private const val CODE_BAD_REQUEST = "bad_request"
        private const val CODE_UNREADABLE = "unreadable_settings"
        private const val CODE_VOIDED = "voided_by_lock"

        private fun unreadable() = VaultFailure(FailureKind.OTHER, CODE_UNREADABLE)

        /** The unlock window counts as open for a save only when it has more than this left (the phone's clock may be off). */
        private const val WINDOW_MARGIN_SECONDS = 30L

        /** A failure that says the vault did not open the window (it refused); any other may have followed the opening. */
        private val DEFINITE_REFUSALS = setOf(
            FailureKind.BAD_PASSWORD, FailureKind.BACKOFF, FailureKind.CREDENTIAL_FROZEN, FailureKind.ROTATION_REQUIRED,
            FailureKind.OWNER_CHECK_REQUIRED, FailureKind.CREDENTIAL_UNSETTLED,
        )
    }
}
