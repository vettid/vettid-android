package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.wallet.ChainServerSettings
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

/** One account in the details: its type (`p2tr`, `p2wpkh`), derivation path and descriptors. */
data class AccountDetails(val type: String, val path: String, val receive: String, val change: String) {
    override fun toString(): String = "AccountDetails"
}

/** A wallet's details. Prints its type name only (fingerprint and descriptors are in it). */
data class WalletDetailsUiState(
    val loaded: Boolean = false,
    val fingerprint: String = "",
    val network: Network? = null,
    val accounts: List<AccountDetails> = emptyList(),
    /** The chain server's host ("Using mempool.space"); null when no server is set. */
    val host: String? = null,
    /** The network's vault name, for the Chain server route; null while the network is not known. */
    val networkName: String? = null,
    val closed: Boolean = false,
) {
    override fun toString(): String = "WalletDetailsUiState"
}

/** Details (ANDROID-PLAN §4 screens table): fingerprint, network, each account's path and descriptors; "Using {host}". */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WalletDetailsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val wallets: WalletsManager,
    private val settings: ChainServerSettings,
) : ViewModel() {
    private val walletId: String = saved.toRoute<WalletDetailsRoute>().walletId
    private val mutable = MutableStateFlow(WalletDetailsUiState())
    val state: StateFlow<WalletDetailsUiState> = mutable.asStateFlow()

    private var seen = false

    init {
        viewModelScope.launch {
            combine(wallets.wallets, wallets.availability, wallets.flagsChanged) { list, availability, _ ->
                list.firstOrNull { it.id == walletId } to availability
            }.mapLatest { (w, availability) ->
                val network = w?.let { wallets.networkOf(it) }
                Triple(w, availability, network to network?.let { host(it) })
            }.collect { (w, availability, networkAndHost) ->
                if (w == null) {
                    if (seen || availability != WalletAvailability.UNKNOWN) mutable.value = mutable.value.copy(closed = true)
                    return@collect
                }
                seen = true
                mutable.value = WalletDetailsUiState(
                    loaded = true,
                    fingerprint = w.fingerprint,
                    network = networkAndHost.first,
                    accounts = w.accounts.map { AccountDetails(it.type, it.path, it.receiveDescriptor, it.changeDescriptor) },
                    host = networkAndHost.second,
                    networkName = networkAndHost.first?.vaultName,
                )
            }
        }
    }

    /** The screen is in front again (after Chain server): the host is read again. */
    fun resumed() {
        viewModelScope.launch {
            val network = mutable.value.network ?: return@launch
            mutable.update { it.copy(host = host(network)) }
        }
    }

    private suspend fun host(network: Network): String? = when (val s = settings.server(network)) {
        is ChainServer.Default -> s.url.host
        is ChainServer.Own -> s.url.host
        else -> null
    }
}
