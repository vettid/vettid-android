package com.vettid.core.data.devices

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.social.ApprovalParser
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.devicePairApprove
import com.vettid.core.vault.devicePairCreateAgent
import com.vettid.core.vault.devicePairReject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

/**
 * A pairing code the vault made for an agent (`device.pair.create`): [link] is the bare pairing payload, a bearer
 * secret for its few minutes (VAULT-MESSAGING §6.4; ANDROID-PLAN 0.1.53 AA2). Its text form never carries the link.
 */
class PairingCode(val pairingId: String, val link: String, val exp: Instant) {
    override fun toString(): String = "PairingCode(exp=$exp)"

    override fun equals(other: Any?): Boolean =
        other is PairingCode && other.pairingId == pairingId && other.link == link && other.exp == exp

    override fun hashCode(): Int = pairingId.hashCode()
}

/**
 * A pairing the agent has answered (`device.pair.pending`): what the member compares. [name] is what the agent says
 * about itself, sanitised (never trusted); [sas] is the six digits, or null when the event did not carry six digits or
 * was not for an agent, so that it cannot be compared and only rejected. [ends] is when this phone stops offering the
 * approval (10 minutes after the event, never later than the vault's own end).
 */
class PendingPairing(val pairingId: String, val name: String, val sas: String?, val ends: Instant) {
    /** An approval is offered only for a code that can be compared. */
    val comparable: Boolean get() = sas != null

    override fun toString(): String = "PendingPairing(ends=$ends)"

    override fun equals(other: Any?): Boolean =
        other is PendingPairing && other.pairingId == pairingId && other.name == name && other.sas == sas && other.ends == ends

    override fun hashCode(): Int = pairingId.hashCode()
}

/** Where a pairing is (ANDROID-PLAN 0.1.53 AA2). Held in memory only. */
sealed interface PairingStage {
    /** Nothing held. */
    data object Idle : PairingStage

    /** `device.pair.create` is on its way. */
    data object Creating : PairingStage

    /** Step 1: the code is shown. */
    data class Code(val code: PairingCode) : PairingStage

    /**
     * The code expired unused: nothing was paired. The pairing's id and end are kept, only to take a late answer of the
     * agent (a pending up to [LATE] after [exp]); the code itself is gone, and the comparison is still required.
     */
    data class CodeExpired(val pairingId: String, val exp: Instant) : PairingStage {
        companion object {
            val LATE: Duration = Duration.ofSeconds(60)
        }
    }

    /** Step 2: the agent has answered; the member compares the code. */
    data class Compare(val pending: PendingPairing) : PairingStage

    /** The member rejected it. [told]: the vault took the rejection (the agent is told); false when the request failed. */
    data class Rejected(val told: Boolean) : PairingStage

    /** The approval went through. [deviceId] is the new agent's id when known (null: the list opens instead). */
    data class Paired(val name: String, val deviceId: String?) : PairingStage

    /** The pairing ended without an approval (its time ran out, or the vault refused the approval): nothing is paired. */
    data object Ended : PairingStage
}

/** The pairing as the screen sees it: the stage, a request in flight, and the last request's failure. */
data class PairingState(
    val stage: PairingStage = PairingStage.Idle,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val errorCode: String? = null,
    /** With [PairingStage.Ended]: the approval's outcome is unknown (no answer), so the agent may be in the list. */
    val unknownOutcome: Boolean = false,
    /** With [unknownOutcome]: the name the agent gave itself (sanitised), for "If <name> is listed…". */
    val agentName: String? = null,
)

/** `device.pair.*` and the vault's device list, so that [PairingManager] can be tested without a vault. */
interface PairingOps {
    suspend fun create(): JsonObject

    suspend fun approve(pairingId: String, sessionSeconds: Int?)

    suspend fun reject(pairingId: String)
}

/** [PairingOps] over the vault client. */
internal class VaultPairingOps(private val api: VaultApi) : PairingOps {
    override suspend fun create(): JsonObject = api.devicePairCreateAgent()

    override suspend fun approve(pairingId: String, sessionSeconds: Int?) = api.devicePairApprove(pairingId, sessionSeconds)

    override suspend fun reject(pairingId: String) = api.devicePairReject(pairingId)
}

/** Pairing an agent (ANDROID-PLAN 0.1.53 AA2), as the screens use it. Every failure is a [VaultFailure] in the state. */
interface PairingRepository {
    val state: StateFlow<PairingState>

    /** Step 1: `device.pair.create{role: "agent"}`; also "New code". A pairing already shown or compared is kept. */
    suspend fun start()

    /** Step 3: `device.pair.approve`, with the first access length in seconds (null: no access yet); never any grants. */
    suspend fun approve(sessionSeconds: Int?)

    /** "They don't match": `device.pair.reject`; the stage becomes [PairingStage.Rejected]. */
    suspend fun reject()

    /** Cancel on the code screen, or leaving the compare screen: a pairing not approved is rejected, and nothing is held. */
    suspend fun cancel()

    /** The screen left without an answer: the same as [cancel], without waiting for it. */
    fun leave()

    /** A final stage was read (rejected, paired, ended, expired): back to nothing held. */
    fun acknowledge()
}

/**
 * [PairingRepository] over [PairingOps]. Fails secure: nothing is approved except by the member's tap on a code that
 * can be compared, within the time the vault allows; an expired code, a rejected, abandoned or failed pairing grants
 * nothing. Memory only: [clear] (lock, hold, wipe, deletion) drops all of it. Events reach it through [onEvent] only
 * after the app's lock guard took them.
 *
 * [knownDeviceIds] (null while the list was never read) and [readDevices] find the new agent's `device_id` when the
 * `device.paired` feed item does not arrive in time ([ID_WAIT]).
 */
class PairingManager(
    private val scope: CoroutineScope,
    private val ops: suspend () -> PairingOps,
    private val now: () -> Instant = Instant::now,
    private val knownDeviceIds: () -> Set<String>? = { null },
    private val readDevices: suspend () -> List<DeviceInfo> = { emptyList() },
    private val onPaired: () -> Unit = {},
) : PairingRepository {
    private val lock = Any()
    private val flow = MutableStateFlow(PairingState())
    private var generation = 0
    private var timer: Job? = null

    /** `device_id`s of `device.paired` feed items seen while an approval was in flight or just done. */
    private var arrivals = mutableListOf<String>()
    private var watching = false

    /** The ids listed before the approval, and when it started: an arrival that was listed before, or older, is not it. */
    private var baseline: Set<String>? = null
    private var watchFrom: Instant = Instant.EPOCH

    override val state: StateFlow<PairingState> = flow.asStateFlow()

    /** Forgets everything: the vault locked, was held or wiped. A request on its way is not applied afterwards. */
    fun clear() {
        synchronized(lock) {
            generation++
            timer?.cancel()
            timer = null
            arrivals = mutableListOf()
            watching = false
            flow.value = PairingState()
        }
    }

    /** `device.pair.pending` for the pairing shown, and the `device.paired` feed item that names the new agent. */
    fun onEvent(m: VaultMessage) {
        when (m.type) {
            "device.pair.pending" -> pending(m.body)
            "feed.event" -> if (VaultJson.str(m.body, "kind") == "device.paired") {
                val id = VaultJson.str(m.body, "device_id")?.takeIf { it.isNotEmpty() }
                val at = ApprovalParser.instant(VaultJson.str(m.body, "at"))
                synchronized(lock) {
                    // Only a new device, from after the approval started (a little slack for the clocks).
                    val fresh = at != null && !at.isBefore(watchFrom.minus(CLOCK_SLACK))
                    if (!watching || id == null) return@synchronized
                    val usable = fresh && baseline?.contains(id) != true
                    if (usable && id !in arrivals) arrivals.add(id)
                }
            }
        }
    }

    private fun pending(body: JsonObject) {
        val id = VaultJson.str(body, "pairing_id")?.takeIf { it.isNotEmpty() } ?: return
        val at = now()
        synchronized(lock) {
            val s = flow.value
            // Only the pairing this phone made, once: a second event for it, or one for another, changes nothing.
            if (!ours(s.stage, id, at)) return
            val role = VaultJson.str(body, "role")
            val sas = if (role == ROLE_AGENT) readSas(VaultJson.str(body, "sas")) else null
            val ends = at.plus(WINDOW)
            val p = PendingPairing(id, DeviceWire.cleanName(VaultJson.str(body, "name")), sas, ends)
            timer?.cancel()
            val gen = generation
            timer = scope.launch { endAt(ends, gen) { it is PairingStage.Compare && it.pending.pairingId == id } }
            flow.value = PairingState(PairingStage.Compare(p))
        }
    }

    /** Whether [stage] is the pairing [id] this phone made and still takes the agent's answer at [at]. */
    private fun ours(stage: PairingStage, id: String, at: Instant): Boolean = when (stage) {
        is PairingStage.Code -> stage.code.pairingId == id && stage.code.exp.isAfter(at)
        is PairingStage.CodeExpired -> stage.pairingId == id && !at.isAfter(stage.exp.plus(PairingStage.CodeExpired.LATE))
        else -> false
    }

    /** At [at] a stage that [still] holds ends: nothing was approved, so nothing is. */
    private suspend fun endAt(at: Instant, gen: Int, still: (PairingStage) -> Boolean) {
        val wait = Duration.between(now(), at)
        if (!wait.isNegative) delay(wait.toMillis() + 1)
        synchronized(lock) {
            val s = flow.value
            if (gen != generation || !still(s.stage)) return
            // An approval in flight settles it by its answer.
            if (s.busy) return
            val st = s.stage
            flow.value = if (st is PairingStage.Code) {
                PairingState(PairingStage.CodeExpired(st.code.pairingId, st.code.exp))
            } else {
                PairingState(PairingStage.Ended)
            }
        }
    }

    @Suppress("ReturnCount") // the guards of a state machine: each is one early exit
    override suspend fun start() {
        val gen: Int
        synchronized(lock) {
            val s = flow.value
            if (s.busy || s.stage is PairingStage.Creating || s.stage is PairingStage.Compare) return
            if (s.stage is PairingStage.Code && s.stage.code.exp.isAfter(now())) return
            gen = generation
            timer?.cancel()
            flow.value = PairingState(PairingStage.Creating, busy = true)
        }
        try {
            val code = sent { read(ops().create()) }
            synchronized(lock) {
                if (gen != generation) return
                timer = scope.launch { endAt(code.exp, gen) { it is PairingStage.Code } }
                flow.value = PairingState(PairingStage.Code(code))
            }
        } catch (e: VaultFailure) {
            synchronized(lock) {
                if (gen == generation) flow.value = PairingState(PairingStage.Idle, error = e.kind, errorCode = e.shownCode)
            }
        }
    }

    /** The answer as a code: a link that is not an agent pairing, or one that does not read, is never shown. */
    @Suppress("ThrowsCount")
    private fun read(body: JsonObject): PairingCode {
        val id = VaultJson.str(body, "pairing_id")?.takeIf { it.isNotEmpty() }
        val link = VaultJson.str(body, "link")?.takeIf { it.isNotEmpty() }
        if (id == null || link == null) throw VaultFailure(FailureKind.OTHER, "unreadable_result")
        val q = try {
            InviteQr.parseLink(link)
        } catch (e: CryptoException) {
            throw VaultFailure(FailureKind.OTHER, "unreadable_result", cause = e)
        } catch (e: IllegalArgumentException) {
            throw VaultFailure(FailureKind.OTHER, "unreadable_result", cause = e)
        }
        if (q.kind != InviteKind.AGENT) throw VaultFailure(FailureKind.OTHER, "unreadable_result")
        val at = now()
        val own = Instant.ofEpochSecond(q.exp)
        // The earliest of what the vault says and what the link says, and never longer than a code lives (§6.4).
        val exp = listOfNotNull(ApprovalParser.instant(VaultJson.str(body, "exp")), own, at.plus(CODE_MAX)).min()
        return PairingCode(id, link, exp)
    }

    @Suppress("ReturnCount")
    override suspend fun approve(sessionSeconds: Int?) {
        val gen: Int
        val p: PendingPairing
        synchronized(lock) {
            val s = flow.value
            val c = s.stage as? PairingStage.Compare ?: return
            // Never an approval of what cannot be compared, nor after this phone's time ran out, nor twice.
            if (s.busy || !c.pending.comparable || !c.pending.ends.isAfter(now())) return
            if (sessionSeconds != null && sessionSeconds !in SESSION_MIN..SESSION_MAX) return
            gen = generation
            p = c.pending
            arrivals = mutableListOf()
            watching = true
            // What the list shows now, and nothing is read first (the list may never have been loaded: then no guess).
            baseline = knownDeviceIds()
            watchFrom = now()
            flow.value = PairingState(c, busy = true)
        }
        // Once sent, everything up to the settled stage runs to its end: a screen that left (a cancelled caller) must not
        // strand the pairing busy and watching.
        withContext(NonCancellable) {
            try {
                settle(gen, p, sessionSeconds)
            } finally {
                synchronized(lock) { watching = false }
            }
        }
    }

    private suspend fun settle(gen: Int, p: PendingPairing, sessionSeconds: Int?) {
        try {
            sent { ops().approve(p.pairingId, sessionSeconds) }
        } catch (e: VaultFailure) {
            failed(gen, p, e)
            return
        }
        synchronized(lock) {
            if (gen != generation) return
            timer?.cancel()
            timer = null
            flow.value = PairingState(PairingStage.Paired(p.name, null))
        }
        runCatching { onPaired() }
        val id = findAgent(p)
        synchronized(lock) {
            if (gen == generation && flow.value.stage is PairingStage.Paired) flow.value = PairingState(PairingStage.Paired(p.name, id))
        }
    }

    @Suppress("ReturnCount")
    private suspend fun failed(gen: Int, p: PendingPairing, e: VaultFailure) {
        // No answer at all: the vault may have taken the approval. Look for the new agent before saying anything.
        val unknown = e.kind == FailureKind.NETWORK || e.kind == FailureKind.NO_RESPONSE
        val found = if (unknown) findAgent(p) else null
        synchronized(lock) {
            watching = false
            if (gen != generation) return
            if (found != null) {
                timer?.cancel()
                timer = null
                flow.value = PairingState(PairingStage.Paired(p.name, found))
                runCatching { onPaired() }
                return
            }
            // The daily check is due: the approval was not sent for it, and the same code may still be compared.
            if (e.kind == FailureKind.OWNER_CHECK_REQUIRED && p.ends.isAfter(now())) {
                flow.value = PairingState(PairingStage.Compare(p), error = e.kind, errorCode = e.shownCode)
                return
            }
            timer?.cancel()
            timer = null
            // Any other answer: the pairing may have ended (the app does not know when, §10.3), so nothing is paired
            // as far as this phone knows. When there was no answer at all the vault may have taken it: say so.
            flow.value = PairingState(
                PairingStage.Ended,
                error = e.kind,
                errorCode = e.shownCode,
                unknownOutcome = unknown,
                agentName = p.name,
            )
        }
    }

    /**
     * The new agent's `device_id` (ANDROID-PLAN 0.1.53 AA2 step 4): from the `device.paired` feed item, else within
     * [ID_WAIT] from the list, the one agent entry that was not listed before and carries the compared name. Null when
     * none or more than one fits: the list opens instead.
     */
    @Suppress("ReturnCount")
    private suspend fun findAgent(p: PendingPairing): String? {
        var waited = 0L
        while (waited < ID_WAIT.toMillis()) {
            synchronized(lock) { arrivals.singleOrNull()?.let { return it } }
            delay(POLL_MS)
            waited += POLL_MS
        }
        synchronized(lock) {
            if (arrivals.size == 1) return arrivals.single()
            if (arrivals.size > 1) return null
        }
        val known = synchronized(lock) { baseline } ?: return null
        val list = try {
            readDevices()
        } catch (_: VaultFailure) {
            return null
        }
        return list.filter { it.isAgent && it.id !in known && it.name == p.name }.singleOrNull()?.id
    }

    override suspend fun reject() {
        val p: PendingPairing
        synchronized(lock) {
            val c = flow.value.stage as? PairingStage.Compare ?: return
            if (flow.value.busy) return
            p = c.pending
            flow.value = PairingState(c, busy = true)
        }
        val told = sendReject(p.pairingId)
        synchronized(lock) {
            val s = flow.value.stage
            if (s is PairingStage.Compare && s.pending.pairingId == p.pairingId) {
                timer?.cancel()
                timer = null
                flow.value = PairingState(PairingStage.Rejected(told))
            }
        }
    }

    override suspend fun cancel() {
        val id: String
        synchronized(lock) {
            val s = flow.value
            id = when (val st = s.stage) {
                is PairingStage.Code -> st.code.pairingId
                is PairingStage.Compare -> if (s.busy) return else st.pending.pairingId
                // A late handshake of the expired code may still be live: the vault is told this one is not wanted.
                is PairingStage.CodeExpired -> st.pairingId
                else -> {
                    if (!s.busy) flow.value = PairingState()
                    return
                }
            }
            timer?.cancel()
            timer = null
            flow.value = PairingState()
        }
        // Nothing is held any more; the vault is told (best effort: the pairing ends by itself within 10 minutes).
        sendReject(id)
    }

    override fun leave() {
        scope.launch { cancel() }
    }

    override fun acknowledge() {
        synchronized(lock) {
            val s = flow.value
            if (s.busy) return
            when (s.stage) {
                is PairingStage.Rejected, is PairingStage.Paired, PairingStage.Ended, PairingStage.CodeExpired ->
                    flow.update { PairingState() }
                PairingStage.Idle -> if (s.error != null) flow.value = PairingState()
                // The daily check passed: the refused approval may be sent again for the same comparison.
                is PairingStage.Compare -> if (s.error != null) flow.value = PairingState(s.stage)
                else -> Unit
            }
        }
    }

    /** `device.pair.reject`; whether the vault took it. A failure is not an error to show: nothing is paired either way. */
    private suspend fun sendReject(id: String): Boolean = try {
        sent { ops().reject(id) }
        true
    } catch (_: VaultFailure) {
        false
    }

    /**
     * A request that is not abandoned half way: once it was sent, its answer is applied even if the screen that asked has
     * gone (a cancelled caller would leave the state waiting for an answer that nobody reads).
     */
    private suspend fun <T> sent(block: suspend () -> T): T = withContext(NonCancellable) { vaultGuard { block() } }

    private companion object {
        const val ROLE_AGENT = "agent"
        val WINDOW: Duration = Duration.ofMinutes(10)
        val CODE_MAX: Duration = Duration.ofMinutes(10).plusSeconds(60)
        val ID_WAIT: Duration = Duration.ofSeconds(10)
        const val POLL_MS = 250L
        val CLOCK_SLACK: Duration = Duration.ofSeconds(30)
        const val SESSION_MIN = 60
        const val SESSION_MAX = 86_400
        val SAS = Regex("[0-9]{6}")

        /** The six digits, from "123456" or "123 456"; null for anything else. */
        fun readSas(s: String?): String? = s?.replace(" ", "")?.takeIf { SAS.matches(it) }
    }
}
