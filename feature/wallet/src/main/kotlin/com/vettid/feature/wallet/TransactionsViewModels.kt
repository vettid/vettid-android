package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.wallet.TransactionRoute
import com.vettid.core.data.wallet.TransactionsRoute
import com.vettid.core.wallet.TransactionsResult
import com.vettid.core.wallet.WalletAvailability
import com.vettid.core.wallet.WalletTransaction
import com.vettid.core.wallet.WalletTransactions
import com.vettid.core.wallet.WalletsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// The Transactions screens (W2-3; ANDROID-PLAN §4 "Wallet", "Transactions"). Memory only: the manager holds the list and forgets it at
// lock, hold and wipe; nothing of it is in saved state, and every state prints its type name only.

/** The Transactions list. [problem] is the look-up's outcome when it did not end with a list. */
data class TransactionsUiState(
    val loading: Boolean = true,
    val name: String = "",
    val network: Network? = null,
    val list: WalletTransactions? = null,
    val problem: TransactionsResult? = null,
    /** The wallet is gone from this screen (deleted, locked, held, wiped): the screen leaves. */
    val closed: Boolean = false,
) {
    override fun toString(): String = "TransactionsUiState"
}

/** The list of a wallet's transactions: read when the screen opens, and again on Try again and pull to refresh. */
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val wallets: WalletsManager,
) : ViewModel() {
    val walletId: String = saved.toRoute<TransactionsRoute>().walletId
    private val mutable = MutableStateFlow(TransactionsUiState())
    val state: StateFlow<TransactionsUiState> = mutable.asStateFlow()
    private var seen = false
    private var busy = false

    init {
        viewModelScope.launch {
            wallets.wallets.collect { list ->
                val w = list.firstOrNull { it.id == walletId }
                if (w == null) {
                    if (seen || wallets.availability.value != WalletAvailability.UNKNOWN) mutable.update { it.copy(closed = true) }
                } else {
                    seen = true
                    mutable.update { it.copy(name = w.name, network = wallets.networkOf(w)) }
                }
            }
        }
        load()
    }

    /** Reads the transactions (the first time, Try again, pull to refresh); one read at a time. */
    fun load() {
        if (busy || mutable.value.closed) return
        busy = true
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = safely { wallets.transactions(walletId) }
            busy = false
            mutable.update {
                when (result) {
                    is TransactionsResult.Done -> it.copy(loading = false, list = result.transactions, problem = null)
                    TransactionsResult.Cleared -> it.copy(loading = false, closed = true)
                    else -> it.copy(loading = false, list = null, problem = result)
                }
            }
        }
    }

    override fun toString(): String = "TransactionsViewModel"
}

/** One transaction. */
data class TransactionUiState(
    val loading: Boolean = true,
    val name: String = "",
    val network: Network? = null,
    val tx: WalletTransaction? = null,
    /** The chain server's host, for the amounts' source note; null before the first list. */
    val host: String? = null,
    /** The look-up ended without the list (the server states, the vault's failure, a note not yet shown). */
    val problem: TransactionsResult? = null,
    /** The wallet and the server answered and the transaction is not in either. */
    val notFound: Boolean = false,
    val closed: Boolean = false,
) {
    override fun toString(): String = "TransactionUiState"
}

/**
 * One transaction. Opened from the list (the wallet and `txid` are known), or from a `wallet.signed` notification or History
 * (the `txid` only: the wallet is found from `wallet.history`). The list the manager holds is shown at once, then read again for
 * its confirmations.
 */
@HiltViewModel
class TransactionViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val wallets: WalletsManager,
) : ViewModel() {
    private val route = saved.toRoute<TransactionRoute>()
    val txid: String = route.txid.lowercase()
    private val mutable = MutableStateFlow(TransactionUiState())
    val state: StateFlow<TransactionUiState> = mutable.asStateFlow()
    private var walletId: String? = route.walletId
    private var busy = false
    private var seen = false

    init {
        viewModelScope.launch {
            wallets.wallets.collect { list ->
                val id = walletId ?: return@collect
                val w = list.firstOrNull { it.id == id }
                if (w == null) {
                    if (seen || wallets.availability.value != WalletAvailability.UNKNOWN) mutable.update { it.copy(closed = true) }
                } else {
                    seen = true
                    mutable.update { it.copy(name = w.name, network = wallets.networkOf(w)) }
                }
            }
        }
        load()
    }

    /** Finds the wallet if it is not known, shows what the manager holds, and reads the list again. */
    fun load() {
        if (busy || mutable.value.closed) return
        busy = true
        mutable.update { it.copy(loading = true, notFound = false) }
        viewModelScope.launch {
            try {
                val id = walletId ?: find()
                if (id == null) return@launch
                wallets.transactionsKnown.value[id]?.let { show(it) }
                when (val result = safely { wallets.transactions(id) }) {
                    is TransactionsResult.Done -> show(result.transactions)
                    TransactionsResult.Cleared -> mutable.update { it.copy(closed = true) }
                    else -> mutable.update { it.copy(problem = result) }
                }
            } finally {
                busy = false
                mutable.update { it.copy(loading = false) }
            }
        }
    }

    /** The wallet whose `wallet.history` has the txid; none: [TransactionUiState.notFound]. */
    @Suppress("ReturnCount")
    private suspend fun find(): String? {
        // Not a txid: nothing to ask the vault about.
        if (!WalletsManager.isTxid(txid)) {
            mutable.update { it.copy(notFound = true) }
            return null
        }
        val id = try {
            wallets.walletOfTransaction(txid)
        } catch (e: VaultFailure) {
            mutable.update { it.copy(problem = TransactionsResult.VaultFailed(e)) }
            return null
        }
        if (id == null) {
            mutable.update { it.copy(notFound = true) }
            return null
        }
        walletId = id
        // The wallet's name and network are watched from here on.
        wallets.wallets.value.firstOrNull { it.id == id }?.let { w ->
            seen = true
            mutable.update { it.copy(name = w.name, network = wallets.networkOf(w)) }
        }
        return id
    }

    private fun show(list: WalletTransactions) {
        val tx = list.items.firstOrNull { it.txid == txid }
        mutable.update {
            it.copy(
                tx = tx,
                host = list.host,
                problem = null,
                notFound = tx == null,
            )
        }
    }

    override fun toString(): String = "TransactionViewModel"
}

/**
 * The look-up, with nothing that goes wrong in it able to escape the view model's scope (a crash on a server's or a vault's odd
 * answer): any failure but a cancellation is a server that gave nothing usable.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun safely(block: suspend () -> TransactionsResult): TransactionsResult = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    TransactionsResult.ServerFailed(com.vettid.core.chain.ChainError.Malformed())
}
