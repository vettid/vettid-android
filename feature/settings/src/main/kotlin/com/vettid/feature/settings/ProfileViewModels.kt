package com.vettid.feature.settings

import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.ItemsRepository
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.Sensitivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.NameChangeOutcome
import com.vettid.core.data.vault.NameRequestView
import com.vettid.core.data.vault.OwnProfile
import com.vettid.core.data.vault.ProfileRepository
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Immutable UI state of the shared-profile screen (VAULT-MESSAGING 0.18.0 §10.8). */
data class SharedProfileUiState(
    val account: AccountInfo? = null,
    val profile: OwnProfile? = null,
    /** The display name being edited; follows the vault's until the member types. */
    val displayName: String = "",
    val edited: Boolean = false,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val error: FailureKind? = null,
    /** The limit a `limit` error named (VAULT-MESSAGING 0.21.0 §10.1: `profile_items`, `profile_size`). */
    val limit: com.vettid.core.data.vault.VaultLimit? = null,
    /** A photo taken and encoded (base64 JPEG, at most 65,536 bytes), shown as a preview until saved or dropped. */
    val pendingPhoto: String? = null,
    /** The taken photo is being encoded, or the photo is being saved. */
    val photoBusy: Boolean = false,
    /** The taken photo could not be encoded. */
    val photoUnreadable: Boolean = false,
    val photoSaved: Boolean = false,
    /** The member's items tagged `@profile` (§10.8): every connection sees them in the shared profile. */
    val profileItems: List<ItemSummary> = emptyList(),
    /** Standard items that could join the profile (`@profile` is only on `data` items). */
    val candidates: List<ItemSummary> = emptyList(),
    val pickingItem: Boolean = false,
    val itemsBusy: Boolean = false,
) {
    /** The photo to show: the one being previewed, else the vault's. */
    val shownPhoto: String? get() = pendingPhoto ?: profile?.photo

    /** The display name's limit (§10.8: at most 128 bytes). */
    val tooLong: Boolean get() = displayName.trim().toByteArray(Charsets.UTF_8).size > MAX_DISPLAY_NAME_BYTES

    val saveAllowed: Boolean
        get() = !busy && edited && !tooLong && profile != null && displayName.trim() != profile.displayName

    /** The names on the account: the snapshot's (the account sheet's source), else the profile's core. */
    val fullName: String? get() = account?.fullName ?: profile?.fullName

    companion object {
        const val MAX_DISPLAY_NAME_BYTES = 128
    }
}

/**
 * Settings → Shared profile (ANDROID-PLAN 0.1.10): the account's names read-only (changed only through
 * [ChangeNameViewModel]), the display name edited with `profile.set`, which never names the core.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class SharedProfileViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val accounts: AccountRepository,
    private val items: ItemsRepository,
) : ViewModel() {
    private val state = MutableStateFlow(SharedProfileUiState(account = accounts.account.value, profile = profiles.profile.value))
    val uiState: StateFlow<SharedProfileUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { accounts.account.collect { a -> state.update { it.copy(account = a) } } }
        viewModelScope.launch {
            profiles.profile.collect { p ->
                state.update { it.copy(profile = p, displayName = if (it.edited) it.displayName else p?.displayName ?: "") }
            }
        }
        viewModelScope.launch {
            try {
                profiles.refreshProfile()
            } catch (e: VaultFailure) {
                state.update { it.copy(error = e.kind) }
            }
        }
        viewModelScope.launch { accounts.refreshAccount() }
        viewModelScope.launch {
            items.items.collect { l ->
                val sorted = l.sortedBy { it.name.lowercase() }
                state.update { s ->
                    s.copy(
                        profileItems = sorted.filter { ItemChecks.PROFILE_TAG in it.tags },
                        candidates = sorted.filter { it.sensitivity == Sensitivity.DATA && ItemChecks.PROFILE_TAG !in it.tags },
                    )
                }
            }
        }
        if (items.load.value == ListLoad.NOT_LOADED) viewModelScope.launch { runCatching { items.refresh() } }
    }

    fun pickItem(show: Boolean) = state.update { it.copy(pickingItem = show) }

    /** Tags a standard item `@profile` (§10.8: at most 32; the vault answers `limit` beyond). */
    fun addToProfile(i: ItemSummary) = retag(i, i.tags + ItemChecks.PROFILE_TAG)

    fun removeFromProfile(i: ItemSummary) = retag(i, i.tags - ItemChecks.PROFILE_TAG)

    private fun retag(i: ItemSummary, tags: List<String>) {
        state.update { it.copy(itemsBusy = true, pickingItem = false, error = null) }
        viewModelScope.launch {
            try {
                items.setTags(i.itemId, i.version, tags)
                state.update { it.copy(itemsBusy = false) }
            } catch (e: VaultFailure) {
                state.update { it.copy(itemsBusy = false, error = e.kind, limit = e.limit) }
            }
        }
    }

    fun setDisplayName(v: String) = state.update { it.copy(displayName = v, edited = true, saved = false, error = null) }

    fun save() {
        val s = state.value
        if (!s.saveAllowed) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                profiles.setDisplayName(s.displayName.trim())
                state.update { it.copy(busy = false, edited = false, saved = true, displayName = s.displayName.trim()) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    fun dismiss() = state.update { it.copy(saved = false, error = null, photoUnreadable = false, photoSaved = false) }

    /** The taken photo is being encoded (off the main thread, by the screen). */
    fun photoEncoding() = state.update { it.copy(photoBusy = true, photoUnreadable = false, photoSaved = false, error = null) }

    /**
     * The photo the member took and accepted ("Use photo"), encoded (`ProfilePhotos.encodeBase64`), sent with
     * `profile.set{photo}`; it stays as a preview (with "Save photo") if the vault refuses. Null: it could not be
     * encoded.
     */
    fun photoTaken(base64: String?) {
        state.update { it.copy(pendingPhoto = base64, photoBusy = false, photoUnreadable = base64 == null) }
        if (base64 != null) sendPhoto(base64)
    }

    fun discardPhoto() = state.update { it.copy(pendingPhoto = null, photoUnreadable = false) }

    /** `profile.set{photo}` with the previewed photo. */
    fun savePhoto() {
        val photo = state.value.pendingPhoto ?: return
        sendPhoto(photo)
    }

    /** `profile.set{photo: ""}`: no photo. */
    fun removePhoto() = sendPhoto("")

    private fun sendPhoto(photo: String) {
        if (state.value.photoBusy) return
        state.update { it.copy(photoBusy = true, error = null, photoSaved = false) }
        viewModelScope.launch {
            try {
                profiles.setPhoto(photo)
                state.update { it.copy(photoBusy = false, pendingPhoto = null, photoSaved = true) }
            } catch (e: VaultFailure) {
                state.update { it.copy(photoBusy = false, error = e.kind) }
            }
        }
    }
}

/** The steps of a name change (ANDROID-PLAN 0.1.10 "Change name"). */
enum class ChangeNameStep {
    /** The two names, checked against the registration rule before anything else. */
    NAMES,

    /** The PIN and the credential password, on one screen, as for the owner check. */
    CONFIRM,

    /** Sent: the request's state (pending, then applied or refused) as the account reports it. */
    SENT,
}

/** What the confirm step says about the last answer. */
sealed interface ChangeNameMessage {
    data class BadPin(val checksLeft: Int?) : ChangeNameMessage

    data class BadPassword(val checksLeft: Int?) : ChangeNameMessage

    data object Backoff : ChangeNameMessage

    /** The vault (or VettID) did not accept the names. */
    data object Invalid : ChangeNameMessage

    data class Failed(val kind: FailureKind) : ChangeNameMessage
}

/** Immutable UI state of the change-name flow. */
data class ChangeNameUiState(
    val step: ChangeNameStep = ChangeNameStep.NAMES,
    val account: AccountInfo? = null,
    val first: String = "",
    val last: String = "",
    /** Set once the member asked to continue: the field errors are shown from then on. */
    val checked: Boolean = false,
    val pin: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val message: ChangeNameMessage? = null,
    val waitSeconds: Long = 0,
    /** `too_soon` from the vault: when the next change may be applied (null: not said). */
    val tooSoon: Boolean = false,
    val allowedAfter: Instant? = null,
    /** The request sent from this screen, updated from the account (`name_request`). */
    val request: NameRequestView? = null,
) {
    val firstCheck: AccountNames.Check get() = AccountNames.check(first)
    val lastCheck: AccountNames.Check get() = AccountNames.check(last)

    /** Both names equal the current ones (the vault answers `bad_request`). */
    val same: Boolean
        get() = account?.firstName != null && AccountNames.normalize(first) == account.firstName &&
            AccountNames.normalize(last) == account.lastName

    val namesValid: Boolean get() = firstCheck == AccountNames.Check.OK && lastCheck == AccountNames.Check.OK && !same

    /** "First Last" as it will be sent. */
    val requestedName: String get() = "${first.trim(' ')} ${last.trim(' ')}"

    val submitAllowed: Boolean
        get() = step == ChangeNameStep.CONFIRM && !busy && waitSeconds == 0L && pin.length >= MIN_PIN && password.isNotEmpty()

    override fun toString(): String = "ChangeNameUiState(step=$step, busy=$busy, message=$message, wait=$waitSeconds, request=$request)"

    companion object {
        const val MIN_PIN = 6
        const val MAX_PIN = 32
    }
}

/**
 * Changing the account's first and last name (VAULT-MESSAGING 0.18.0 §10.8, ANDROID-PLAN 0.1.10): the two names,
 * checked with the registration rule before the PIN and the password are asked for; then the PIN and the credential
 * password on one screen, sent with the names in one `account.name.set` and dropped once the vault answered; then the
 * request as the account reports it: pending, applied or refused. `too_soon` (from the snapshot, the vault or the
 * member API) says when the next change may be made.
 */
@HiltViewModel
class ChangeNameViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val state = MutableStateFlow(initial(accounts.account.value))
    val uiState: StateFlow<ChangeNameUiState> = state.asStateFlow()
    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            accounts.account.collect { a ->
                state.update { s ->
                    val ours = s.request
                    val latest = a?.nameRequest?.takeIf { ours != null && it.seq == ours.seq }
                    s.copy(account = a, request = latest ?: ours)
                }
            }
        }
        viewModelScope.launch { accounts.refreshAccount() }
    }

    private fun initial(a: AccountInfo?) = ChangeNameUiState(account = a, first = a?.firstName ?: "", last = a?.lastName ?: "")

    /** Whether the snapshot says a change now would be refused (`name_change.allowed_after` ahead). */
    fun tooSoonBySnapshot(now: Instant = Instant.now()): Boolean = state.value.account?.nameChangeTooSoon(now) == true

    fun setFirst(v: String) = state.update { it.copy(first = v.take(MAX_INPUT)) }

    fun setLast(v: String) = state.update { it.copy(last = v.take(MAX_INPUT)) }

    /** From the names to the PIN and password, only with names the rule accepts. */
    fun next() {
        state.update { it.copy(checked = true) }
        val s = state.value
        if (!s.namesValid || tooSoonBySnapshot()) return
        state.update { it.copy(step = ChangeNameStep.CONFIRM, message = null) }
    }

    /** Back from the PIN and password to the names; nothing entered there is kept. */
    fun back() {
        ticker?.cancel()
        state.update { it.copy(step = ChangeNameStep.NAMES, pin = "", password = "", message = null, waitSeconds = 0) }
    }

    /** Leaves the flow: forgets the PIN and the password. */
    fun cancel() {
        ticker?.cancel()
        state.update { it.copy(pin = "", password = "", message = null) }
    }

    fun setPin(v: String) = state.update {
        it.copy(pin = v.filter { c -> c.isDigit() }.take(ChangeNameUiState.MAX_PIN), message = null)
    }

    fun setPassword(v: String) = state.update { it.copy(password = v, message = null) }

    fun submit() {
        val s = state.value
        if (!s.submitAllowed) return
        state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val r = try {
                profiles.changeName(s.pin, s.password, s.first, s.last)
            } catch (e: VaultFailure) {
                NameChangeOutcome.Failed(e.kind, e.code)
            }
            // Neither secret is kept beyond the answer (as for the owner check, §3.6.5).
            state.update { it.copy(busy = false, pin = "", password = "") }
            apply(r)
        }
    }

    private fun apply(r: NameChangeOutcome) {
        when (r) {
            is NameChangeOutcome.Requested -> state.update { it.copy(step = ChangeNameStep.SENT, request = r.request) }
            is NameChangeOutcome.TooSoon ->
                state.update { it.copy(step = ChangeNameStep.NAMES, tooSoon = true, allowedAfter = r.allowedAfter) }
            NameChangeOutcome.Invalid -> state.update { it.copy(step = ChangeNameStep.NAMES, message = ChangeNameMessage.Invalid) }
            is NameChangeOutcome.BadPin -> state.update { it.copy(message = ChangeNameMessage.BadPin(r.checksLeft)) }
            is NameChangeOutcome.BadPassword -> state.update { it.copy(message = ChangeNameMessage.BadPassword(r.checksLeft)) }
            is NameChangeOutcome.Backoff -> {
                state.update { it.copy(message = ChangeNameMessage.Backoff) }
                startBackoff(r.retryAfterSeconds)
            }
            is NameChangeOutcome.Failed -> state.update { it.copy(message = ChangeNameMessage.Failed(r.kind)) }
        }
    }

    private fun startBackoff(seconds: Long) {
        if (seconds <= 0) return
        ticker?.cancel()
        state.update { it.copy(waitSeconds = seconds) }
        ticker = viewModelScope.launch {
            while (state.value.waitSeconds > 0) {
                delay(TICK_MS)
                state.update { it.copy(waitSeconds = (it.waitSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    private companion object {
        const val TICK_MS = 1000L

        /** Generous input bound; the rule's 40 is checked with its own message. */
        const val MAX_INPUT = 80
    }
}
