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
enum class ReceiveProblem { NOT_OFFERED, MISMATCH }

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
) {
    val amount: BtcFormat.Amount get() = BtcFormat.parse(amountText)

    /** The QR code's content: a BIP21 URI; an amount that is not valid is left out. */
    val uri: String? get() = address?.let { Bip21.uri(it.address, (amount as? BtcFormat.Amount.Valid)?.sats) }

    override fun toString(): String = "ReceiveUiState"
}

/**
 * Receive (ANDROID-PLAN §4 screens table): the newest unused address of the receiving account ([WalletsManager.receiveAddress]), as
 * a QR code and as text; "New address" asks the vault for the next one. A wallet whose phrase is not confirmed asks for no address.
 */
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
            // The gate stays up until the flag reads true (no spinner in between while it is still not backed up).
            if (!wallets.backedUp(walletId)) {
                mutable.update { it.copy(loading = false, notBackedUp = true) }
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
                }
            }
        } catch (e: VaultFailure) {
            mutable.update { it.copy(loading = false, busy = false, error = e.kind) }
        }
    }

    fun setAmount(text: String) = mutable.update { it.copy(amountText = BtcFormat.filter(text)) }
}
