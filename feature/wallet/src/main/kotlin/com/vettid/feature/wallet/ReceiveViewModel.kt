package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.wallet.ReceiveResult
import com.vettid.core.wallet.WalletAddress
import com.vettid.core.wallet.WalletAvailability
import com.vettid.core.wallet.WalletsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why Receive shows no address, besides a failure and the backup gate. */
enum class ReceiveProblem {
    NOT_OFFERED,
    MISMATCH,

    /** The spending limit the member chose is not set yet (W1d): no address until it is ("Try again" sends it). */
    CAPS_PENDING,

    /** This phone cannot read its wallet settings, so no wallet is known to be free of a limit to set: no address ("Try again"). */
    SETTINGS_UNREADABLE,

    /** Wallet settings were reset on this phone and the vault shows no limit on this wallet: "Set a spending limit?" is asked first. */
    LIMITS_ASK,
}

/** Receive: the address the vault issued, the optional amount, and what is encoded. Prints its type name only. */
data class ReceiveUiState(
    val loading: Boolean = true,
    /** The phrase is not confirmed: the check is offered instead of an address. */
    val notBackedUp: Boolean = false,
    val network: Network? = null,
    val address: WalletAddress? = null,
    /** The amount field as typed (bitcoin). */
    val amountText: String = "",
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val closed: Boolean = false,
    /** A legacy `testnet` wallet whose test chain was not said yet: asked before any address. */
    val needsNetwork: Boolean = false,
    val networkSaveFailed: Boolean = false,
    val problem: ReceiveProblem? = null,
    /** The answer to "Set a spending limit?" as typed ([ReceiveProblem.LIMITS_ASK]). */
    val limits: LimitsForm = LimitsForm(),
    /** The member chose No limit or a limit on that question: nothing is preselected. */
    val limitsChosen: Boolean = false,
    /** "Reset wallet settings on this phone" is asked about (its confirm is up). */
    val resetAsk: Boolean = false,
    /** The reset, or the answer to the limit question, could not be kept on this phone. */
    val resetFailed: Boolean = false,
    val limitsNotSaved: Boolean = false,
) {
    val limitsParse: LimitsParse get() = LimitsInput.parse(limits)

    val amount: BtcFormat.Amount get() = BtcFormat.parse(amountText)

    /** The QR code's content: a BIP21 URI; an amount that is not valid is left out. */
    val uri: String? get() = address?.let { Bip21.uri(it.address, (amount as? BtcFormat.Amount.Valid)?.sats) }

    /**
     * What Share sends ([Bip21.share]): the URI with the amount, or the plain address when none is typed; null while the amount
     * is not valid (Share is off then, rather than silently dropping what was typed).
     */
    val shareText: String? get() = address?.let { a ->
        when (val m = amount) {
            BtcFormat.Amount.None -> Bip21.share(a.address)
            is BtcFormat.Amount.Valid -> Bip21.share(a.address, m.sats)
            BtcFormat.Amount.Invalid -> null
        }
    }

    override fun toString(): String = "ReceiveUiState"
}

/**
 * Receive (ANDROID-PLAN §4 screens table): the newest unused address of the receiving account ([WalletsManager.receiveAddress]), as
 * a QR code and as text; "New address" asks the vault for the next one. A wallet whose phrase is not confirmed asks for no address.
 */
// One function per action of the screen (they share its state).
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReceiveViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val wallets: WalletsManager,
) : ViewModel() {
    val walletId: String = saved.toRoute<ReceiveRoute>().walletId
    private val mutable = MutableStateFlow(ReceiveUiState())
    val state: StateFlow<ReceiveUiState> = mutable.asStateFlow()

    private var started = false
    private var seen = false

    init {
        viewModelScope.launch {
            combine(wallets.wallets, wallets.availability, wallets.flagsChanged) { list, availability, _ ->
                list.firstOrNull { it.id == walletId } to availability
            }.mapLatest { (w, availability) ->
                Triple(w, availability, w?.let { wallets.networkOf(it) })
            }.collect { (w, availability, network) ->
                if (w == null) {
                    if (seen || availability != WalletAvailability.UNKNOWN) mutable.update { it.copy(closed = true) }
                } else {
                    seen = true
                    mutable.update { it.copy(network = network, needsNetwork = network == null) }
                    when {
                        network == null -> Unit
                        !started -> {
                            started = true
                            load()
                        }
                        // The phrase was checked meanwhile (the backup flow ran on top of this screen): the address now.
                        mutable.value.notBackedUp -> load()
                    }
                }
            }
        }
    }

    /** "Which test network is this wallet on?": kept on this phone, then the address. */
    fun chooseTestNetwork(network: Network) {
        if (!mutable.value.needsNetwork) return
        viewModelScope.launch {
            val kept = wallets.setTestNetwork(walletId, network)
            mutable.update { it.copy(networkSaveFailed = !kept) }
        }
    }

    /** The address to show; from Try again too. */
    fun load() {
        viewModelScope.launch {
            // Settings that cannot be read come first: with them "Not backed up" is only a guess, and the way out (the reset) is here.
            if (WalletsManager.UNKNOWN in wallets.capsPending.value) {
                mutable.update {
                    it.copy(loading = false, notBackedUp = false, address = null, problem = ReceiveProblem.SETTINGS_UNREADABLE)
                }
                return@launch
            }
            // The gate stays up until the flag reads true (no spinner in between while it is still not backed up).
            if (!wallets.backedUp(walletId)) {
                mutable.update { it.copy(loading = false, notBackedUp = true, problem = null) }
                return@launch
            }
            mutable.update { it.copy(loading = true, error = null, notBackedUp = false) }
            ask { wallets.receiveAddress(walletId) }
        }
    }

    /** "New address". */
    fun newAddress() {
        if (mutable.value.busy || mutable.value.closed) return
        viewModelScope.launch { ask { wallets.newReceiveAddress(walletId) } }
    }

    private suspend fun ask(get: suspend () -> ReceiveResult) {
        mutable.update { it.copy(busy = true, error = null, problem = null) }
        try {
            val r = get()
            mutable.update {
                when (r) {
                    is ReceiveResult.Address -> it.copy(loading = false, busy = false, address = r.address)
                    ReceiveResult.NeedsNetwork -> it.copy(loading = false, busy = false, needsNetwork = true)
                    ReceiveResult.NotOffered -> it.copy(loading = false, busy = false, address = null, problem = ReceiveProblem.NOT_OFFERED)
                    ReceiveResult.Mismatch -> it.copy(loading = false, busy = false, address = null, problem = ReceiveProblem.MISMATCH)
                    ReceiveResult.CapsPending ->
                        it.copy(loading = false, busy = false, address = null, problem = ReceiveProblem.CAPS_PENDING)
                    ReceiveResult.SettingsUnreadable ->
                        it.copy(loading = false, busy = false, address = null, problem = ReceiveProblem.SETTINGS_UNREADABLE)
                    ReceiveResult.LimitsAsk ->
                        it.copy(loading = false, busy = false, address = null, problem = ReceiveProblem.LIMITS_ASK)
                }
            }
        } catch (e: VaultFailure) {
            mutable.update { it.copy(loading = false, busy = false, error = e.kind) }
        }
    }

    fun setLimitChoice(limit: Boolean) = mutable.update { it.copy(limits = it.limits.copy(limit = limit), limitsChosen = true) }

    fun setPerTx(v: String) = mutable.update { it.copy(limits = it.limits.copy(perTx = LimitsInput.filter(v, it.limits.btc))) }

    fun setPerPeriod(v: String) = mutable.update { it.copy(limits = it.limits.copy(perPeriod = LimitsInput.filter(v, it.limits.btc))) }

    fun toggleUnit() = mutable.update { it.copy(limits = it.limits.toggled()) }

    /**
     * "Continue" on "Set a spending limit?" after a reset: **No limit** is remembered as answered; a limit is kept as pending and
     * sent with `wallet.caps.set` (a failed send leaves it pending: Receive says so, with Try again). Then the address.
     */
    fun answerLimits() {
        val s = mutable.value
        val parse = s.limitsParse
        val ready = s.problem == ReceiveProblem.LIMITS_ASK && s.limitsChosen && parse.ok
        if (!ready || s.busy) return
        mutable.update { it.copy(busy = true, limitsNotSaved = false) }
        viewModelScope.launch {
            val kept = try {
                wallets.answerLimits(walletId, parse.caps)
            } catch (_: VaultFailure) {
                // Kept, but not sent yet: it is pending, and Receive says so.
                true
            }
            mutable.update { it.copy(busy = false, limitsNotSaved = !kept) }
            if (kept) load()
        }
    }

    /** "Reset wallet settings on this phone" (offered while the settings cannot be read): the confirm. */
    fun askReset() {
        if (mutable.value.problem == ReceiveProblem.SETTINGS_UNREADABLE) mutable.update { it.copy(resetAsk = true, resetFailed = false) }
    }

    fun cancelReset() = mutable.update { it.copy(resetAsk = false) }

    /** The reset confirmed: the local flags only, a fresh file; nothing is sent to the vault. Then Receive starts over. */
    fun resetSettings() {
        val s = mutable.value
        if (!s.resetAsk || s.problem != ReceiveProblem.SETTINGS_UNREADABLE || s.closed) return
        mutable.update { it.copy(resetAsk = false) }
        viewModelScope.launch {
            val done = wallets.resetSettings()
            mutable.update { it.copy(resetFailed = !done) }
            if (done) load()
        }
    }

    fun setAmount(text: String) = mutable.update { it.copy(amountText = BtcFormat.filter(text)) }
}
