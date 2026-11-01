package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.chain.SaveResult
import com.vettid.core.chain.UrlProblem
import com.vettid.core.wallet.ChainServerSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why "Use my own server" did not save: each has its own text. */
sealed interface ServerProblem {
    data class BadUrl(val problem: UrlProblem) : ServerProblem

    /** The server gave no usable answer to the check. */
    data object Failed : ServerProblem

    /** The server is on another network. */
    data object WrongNetwork : ServerProblem

    /** The check passed, but this phone could not write the setting. */
    data object NotWritten : ServerProblem

    data object NotOffered : ServerProblem

    /** "Reset to default" could not be written. */
    data object ResetFailed : ServerProblem
}

/** The chain server setting of one network. Prints its type name only. */
data class ChainServerUiState(
    /** The network of the route; null if it names none the app knows. */
    val network: Network?,
    /** The setting as it stands; null while it is read. */
    val server: ChainServer? = null,
    val text: String = "",
    /** The server is being tested ("Checking…"). */
    val checking: Boolean = false,
    val problem: ServerProblem? = null,
    /** The server was just saved. */
    val saved: Boolean = false,
) {
    override fun toString(): String = "ChainServerUiState"
}

/**
 * Wallet → ⋯ → Chain server (ANDROID-PLAN §4 "The chain source"): one setting per network. "Use my own server" tests the server
 * before it is saved ([ChainServerSettings.save]); "Reset to default" gives the network's default back.
 */
@HiltViewModel
class ChainServerViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val settings: ChainServerSettings,
) : ViewModel() {
    private val network: Network? = Network.fromVaultName(saved.toRoute<ChainServerRoute>().network)
    private val mutable = MutableStateFlow(ChainServerUiState(network))
    val state: StateFlow<ChainServerUiState> = mutable.asStateFlow()

    init {
        read()
    }

    private fun read() {
        val n = network ?: return mutable.update { it.copy(server = ChainServer.NotOffered) }
        viewModelScope.launch { mutable.update { it.copy(server = settings.server(n)) } }
    }

    fun setText(v: String) = mutable.update { it.copy(text = v, problem = null, saved = false) }

    /** "Save": the server is tested first; nothing is saved unless it answers and is on this network. */
    fun save() {
        val n = network ?: return
        val s = mutable.value
        if (s.checking || s.text.isBlank()) return
        mutable.update { it.copy(checking = true, problem = null, saved = false) }
        viewModelScope.launch {
            val result = settings.save(n, s.text)
            val server = if (result is SaveResult.Saved) settings.server(n) else null
            mutable.update {
                it.copy(
                    checking = false,
                    problem = problemOf(result),
                    saved = result is SaveResult.Saved,
                    text = if (result is SaveResult.Saved) "" else it.text,
                    server = server ?: it.server,
                )
            }
        }
    }

    /** "Reset to default". */
    fun reset() {
        val n = network ?: return
        if (mutable.value.checking) return
        viewModelScope.launch {
            val ok = settings.reset(n)
            val server = settings.server(n)
            mutable.update {
                it.copy(
                    problem = if (ok) null else ServerProblem.ResetFailed,
                    saved = false,
                    text = if (ok) "" else it.text,
                    server = server,
                )
            }
        }
    }

    private fun problemOf(r: SaveResult): ServerProblem? = when (r) {
        is SaveResult.Saved -> null
        is SaveResult.BadUrl -> ServerProblem.BadUrl(r.problem)
        is SaveResult.Failed -> ServerProblem.Failed
        is SaveResult.WrongNetwork -> ServerProblem.WrongNetwork
        SaveResult.NotWritten -> ServerProblem.NotWritten
        SaveResult.NotOffered -> ServerProblem.NotOffered
    }
}
