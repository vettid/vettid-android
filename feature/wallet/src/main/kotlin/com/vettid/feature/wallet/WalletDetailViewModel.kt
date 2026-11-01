package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.wallet.WalletOpenRoute
import com.vettid.core.wallet.ChainServerSettings
import com.vettid.core.wallet.LookupResult
import com.vettid.core.wallet.VaultWallet
import com.vettid.core.wallet.WalletAvailability
import com.vettid.core.wallet.WalletLookup
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

/** Where the delete stands: nothing, the warning ("Delete {name}?"), or the password. */
enum class DeleteStep { NONE, CONFIRM, PASSWORD }

/** The wallet's address types as the vault names them (`wallet.update{address_type}`). */
object AddressTypes {
    const val TAPROOT = "p2tr"
    const val SEGWIT = "p2wpkh"
}

/**
 * What the wallet detail shows. Prints its type name only (a name, a balance and the password are in it).
 */
data class WalletDetailUiState(
    val loaded: Boolean = false,
    val name: String = "",
    val network: Network? = null,
    /** The receiving account's type, `p2tr` or `p2wpkh`. */
    val addressType: String = AddressTypes.TAPROOT,
    val backedUp: Boolean = true,
    /** The spending limit the member chose is not set yet (W1d): Receive is not offered until it is. */
    val capsPending: Boolean = false,
    /** This phone cannot read its wallet settings: no wallet is known to be free of a limit to set, so none has Receive. */
    val settingsUnreadable: Boolean = false,
    /** The wallet has a spending limit (`wallet.list`): its recovery phrase needs the PIN too. */
    val capped: Boolean = false,
    /** The pending limit was refused because the vault holds another copy of this wallet (`duplicate_wallet`). */
    val capsDuplicate: Boolean = false,
    /** The wallet's vault enforces spending limits: ⋯ → Spending limits is offered. */
    val capsSupported: Boolean = false,
    /** "Reset wallet settings on this phone" is asked about (its confirm is up). */
    val resetAsk: Boolean = false,
    /** The reset could not be written on this phone. */
    val resetFailed: Boolean = false,
    /** The chain server's privacy note is to be acknowledged before the wallet's first look-up. */
    val noteNeeded: Boolean = false,
    /** The chain server this wallet's network uses now (its host), named on the privacy note; null when none is set. */
    val serverHost: String? = null,
    /** "Change" on the testnet3 answer: the test-network question is asked again. */
    val askNetwork: Boolean = false,
    val lookingUp: Boolean = false,
    /** The last look-up that succeeded (its time, host and totals): kept while a later one fails or runs. */
    val last: WalletLookup? = null,
    /** What the latest look-up of this screen ended as; null before the first. */
    val lookup: LookupResult? = null,
    /** This phone could not record the test chain the member chose. */
    val networkSaveFailed: Boolean = false,
    val typeBusy: Boolean = false,
    val typeError: FailureKind? = null,
    val delete: DeleteStep = DeleteStep.NONE,
    val password: String = "",
    val deleting: Boolean = false,
    val deleteError: FailureKind? = null,
    /** The wallet is gone from this screen (deleted, locked, held, wiped): the screen leaves. */
    val closed: Boolean = false,
) {
    val idle: Boolean get() = !deleting && !typeBusy && !closed

    override fun toString(): String = "WalletDetailUiState"
}

/**
 * The wallet detail (ANDROID-PLAN §4 "Wallet", screens table): the balance from a look-up on the chain server, started when the
 * screen opens but, for a wallet whose privacy note was not shown yet, only after the member acknowledges it. Also the receiving
 * address type and the delete (warning, then the password). Nothing of the wallet is kept in saved state.
 */
// One function per action of the screen (they share its state).
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WalletDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val wallets: WalletsManager,
    private val settings: ChainServerSettings,
) : ViewModel() {
    val walletId: String = saved.toRoute<WalletOpenRoute>().walletId
    private val mutable = MutableStateFlow(WalletDetailUiState())
    val state: StateFlow<WalletDetailUiState> = mutable.asStateFlow()

    private var started = false
    private var seen = false

    /** The server setting at the last look-up: another one now (changed on Chain server) means the balance is out of date. */
    private var serverAtLookup: ChainServer? = null

    private class Seen(
        val wallet: VaultWallet?,
        val availability: WalletAvailability,
        val last: WalletLookup?,
        val network: Network?,
        val backedUp: Boolean,
        val capsPending: Boolean,
        val settingsUnreadable: Boolean,
    )

    init {
        viewModelScope.launch {
            wallets.capsDuplicate.collect { d -> mutable.update { it.copy(capsDuplicate = walletId in d) } }
        }
        viewModelScope.launch {
            combine(
                wallets.wallets, wallets.availability, wallets.lookups, wallets.flagsChanged, wallets.capsPending,
            ) { list, availability, lookups, _, pending ->
                val here = WalletsManager.isPending(pending, walletId) to (WalletsManager.UNKNOWN in pending)
                Triple(list.firstOrNull { it.id == walletId }, availability, lookups[walletId]) to here
            }.mapLatest { (seen, flagsState) ->
                val (pending, unreadable) = flagsState
                val (w, availability, last) = seen
                Seen(w, availability, last, w?.let { wallets.networkOf(it) }, wallets.backedUp(walletId), pending, unreadable)
            }.collect { show(it) }
        }
    }

    private fun show(s: Seen) {
        val w = s.wallet
        if (w == null) {
            // Gone after it was shown (deleted elsewhere, locked), or the vault answered and never had it.
            if (seen || s.availability != WalletAvailability.UNKNOWN) mutable.update { it.copy(closed = true, password = "") }
            return
        }
        seen = true
        mutable.update {
            it.copy(
                loaded = true, name = w.name, network = s.network, addressType = w.addressType, backedUp = s.backedUp,
                capsPending = s.capsPending, settingsUnreadable = s.settingsUnreadable, capped = w.capped, capsSupported = w.capsSupported,
                last = s.last ?: it.last,
            )
        }
        if (!started) {
            started = true
            viewModelScope.launch { begin() }
        }
    }

    /** The privacy note first if it was not shown for this wallet; otherwise the look-up. */
    private suspend fun begin() {
        if (wallets.privacyNoteShown(walletId)) {
            lookUp()
        } else {
            mutable.update { it.copy(noteNeeded = true, serverHost = hostNow()) }
        }
    }

    /** "Continue" on the privacy note: recorded (if this phone can), then the first look-up. */
    fun acknowledgeNote() {
        if (!mutable.value.noteNeeded) return
        viewModelScope.launch {
            wallets.setPrivacyNoteShown(walletId)
            mutable.update { it.copy(noteNeeded = false) }
            lookUp()
        }
    }

    /** Pull to refresh, and "Try again": a look-up again (never before the note is acknowledged). */
    fun refresh() {
        val s = mutable.value
        if (s.noteNeeded || s.lookingUp || s.closed) return
        viewModelScope.launch { lookUp() }
    }

    private suspend fun lookUp() {
        if (mutable.value.lookingUp) return
        mutable.update { it.copy(lookingUp = true) }
        serverAtLookup = serverNow()
        val result = wallets.lookup(walletId)
        mutable.update {
            it.copy(
                lookingUp = false,
                noteNeeded = it.noteNeeded || result == LookupResult.NoteNeeded,
                lookup = result.takeUnless { r -> r == LookupResult.NoteNeeded },
                last = (result as? LookupResult.Done)?.lookup ?: it.last,
                closed = it.closed || result is LookupResult.Cleared,
            )
        }
    }

    private suspend fun serverNow(): ChainServer? = mutable.value.network?.let { settings.server(it) }

    private suspend fun hostNow(): String? = when (val s = serverNow()) {
        is ChainServer.Default -> s.url.host
        is ChainServer.Own -> s.url.host
        else -> null
    }

    /**
     * The screen is in front again (after Chain server, or the app). A chain server that is not the one of the last look-up (changed
     * on Chain server) means the balance is out of date, even after a good look-up: it is looked up again. A look-up that ended in a
     * server problem is made again too (the server may be back). A mismatch, a network not offered or a vault failure are not
     * changed by coming back; the network question waits for its answer (or looks up once it was answered on Receive).
     */
    fun resumed() {
        viewModelScope.launch {
            val s = mutable.value
            if (s.closed || s.lookingUp) return@launch
            if (s.noteNeeded) {
                mutable.update { it.copy(serverHost = hostNow()) }
                return@launch
            }
            val changed = s.lookup != null && serverNow() != serverAtLookup
            when (s.lookup) {
                null -> Unit
                LookupResult.NeedsNetwork -> if (networkKnown()) lookUp()
                is LookupResult.NoServer, is LookupResult.ServerWrongNetwork, is LookupResult.ServerFailed -> lookUp()
                else -> if (changed) lookUp()
            }
        }
    }

    private suspend fun networkKnown(): Boolean {
        val w = wallets.wallets.value.firstOrNull { it.id == walletId } ?: return false
        return wallets.networkOf(w) != null
    }

    /** "Change" on the testnet3 answer: back to the question. */
    fun changeNetwork() {
        if (mutable.value.lookup is LookupResult.NetworkNotOffered) mutable.update { it.copy(askNetwork = true) }
    }

    /** "Which test network is this wallet on?": the answer is kept on this phone, then the look-up again. */
    fun chooseTestNetwork(network: Network) {
        val s = mutable.value
        if (s.lookup !is LookupResult.NeedsNetwork && !s.askNetwork) return
        viewModelScope.launch {
            val kept = wallets.setTestNetwork(walletId, network)
            mutable.update { it.copy(networkSaveFailed = !kept, askNetwork = !kept && it.askNetwork) }
            if (kept) lookUp()
        }
    }

    /** "Try again" on the note that Receive is off: the pending `wallet.caps.set`, and nothing else. */
    fun retryCaps() {
        if (!mutable.value.capsPending || !mutable.value.idle) return
        viewModelScope.launch {
            try {
                wallets.settleCaps(walletId)
            } catch (_: VaultFailure) {
                // It stays pending; the note stays.
            }
        }
    }

    /** "Reset wallet settings on this phone": the confirm, only while the flags file cannot be read. */
    fun askReset() {
        if (mutable.value.settingsUnreadable && mutable.value.idle) mutable.update { it.copy(resetAsk = true, resetFailed = false) }
    }

    fun cancelReset() = mutable.update { it.copy(resetAsk = false) }

    /** The reset confirmed: the local flags only, a fresh file; nothing is sent to the vault. */
    fun resetSettings() {
        val s = mutable.value
        if (!s.resetAsk || !s.settingsUnreadable || !s.idle) return
        mutable.update { it.copy(resetAsk = false) }
        viewModelScope.launch {
            val done = wallets.resetSettings()
            mutable.update { it.copy(resetFailed = !done) }
        }
    }

    /** "Receiving address type" (`wallet.update`). A refused change says why and leaves the type as it was. */
    fun setAddressType(type: String) {
        val s = mutable.value
        if (!s.idle || type == s.addressType) return
        mutable.update { it.copy(typeBusy = true, typeError = null) }
        viewModelScope.launch {
            val failure = try {
                wallets.setAddressType(walletId, type)
                null
            } catch (e: VaultFailure) {
                e.kind
            }
            mutable.update { it.copy(typeBusy = false, typeError = failure) }
        }
    }

    fun dismissTypeError() = mutable.update { it.copy(typeError = null) }

    /** "Delete wallet": the warning first. */
    fun askDelete() {
        if (mutable.value.idle) mutable.update { it.copy(delete = DeleteStep.CONFIRM, deleteError = null, password = "") }
    }

    fun cancelDelete() {
        if (!mutable.value.deleting) mutable.update { it.copy(delete = DeleteStep.NONE, deleteError = null, password = "") }
    }

    /** The warning accepted: the password is asked next. */
    fun continueDelete() {
        if (mutable.value.delete == DeleteStep.CONFIRM) mutable.update { it.copy(delete = DeleteStep.PASSWORD) }
    }

    fun setPassword(v: String) = mutable.update { it.copy(password = v, deleteError = null) }

    /** `item.delete` with the password, blanked at once; the screen leaves when the wallet is gone. */
    fun delete() {
        val s = mutable.value
        if (s.delete != DeleteStep.PASSWORD || !s.idle || s.password.isEmpty()) return
        val pw = s.password
        mutable.update { it.copy(deleting = true, password = "", deleteError = null) }
        viewModelScope.launch {
            try {
                wallets.delete(walletId, pw)
                mutable.update { it.copy(deleting = false, closed = true) }
            } catch (e: VaultFailure) {
                mutable.update { it.copy(deleting = false, deleteError = e.kind) }
            }
        }
    }
}
