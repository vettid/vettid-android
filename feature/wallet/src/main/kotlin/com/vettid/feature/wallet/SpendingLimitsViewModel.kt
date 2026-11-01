package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.wallet.SpendingLimitsRoute
import com.vettid.core.wallet.WalletAvailability
import com.vettid.core.wallet.WalletsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the Spending limits screen stands. */
enum class LimitsStep {
    OVERVIEW,

    /** "Lower a limit": the form. */
    LOWER,

    /** "Raise or remove a limit": the form. */
    RAISE,

    /** "Lower the limit to {new}?" (no password). */
    CONFIRM_SET,

    /** "Raise the limit from {old} to {new}?" or "Remove the limit?", with the PIN and password. */
    CONFIRM_RAISE,
}

/**
 * The Spending limits screen. Prints its type name only (the name, the limits, the PIN and the password are in it).
 * The PIN and password are blanked in the state as soon as they are sent.
 */
data class SpendingLimitsUiState(
    val loaded: Boolean = false,
    val name: String = "",
    /** The wallet's vault enforces spending limits (`caps` is in `wallet.list`). */
    val supported: Boolean = true,
    val perTx: Long? = null,
    val perPeriod: Long? = null,
    /** `caps.spent_sats`: what the period holds; null when the vault did not say. */
    val spent: Long? = null,
    /** A limit chosen at creation has not reached the vault yet. */
    val capsPending: Boolean = false,
    val step: LimitsStep = LimitsStep.OVERVIEW,
    /** The form the member is in ([LimitsStep.LOWER] or [LimitsStep.RAISE]), also behind its confirm. */
    val raiseForm: Boolean = false,
    val form: CapsEditForm = CapsEditForm(),
    val pin: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: FailureKind? = null,
    /** The vault's code of [error] (or this app's: [WalletsManager.CAPS_PENDING]). */
    val errorCode: String? = null,
    val closed: Boolean = false,
) {
    /** What the form comes to against the limits as they stand. */
    val plan: CapsPlan get() = CapsEdit.plan(perTx, perPeriod, form, raiseForm)

    /** The PIN and password are typed in full enough to send. */
    val secretsReady: Boolean get() = pin.length in PIN_LENGTH && password.isNotEmpty()

    /** Nothing can be started while something is under way. */
    val idle: Boolean get() = !busy && !closed

    override fun toString(): String = "SpendingLimitsUiState"
}

/**
 * Spending limits (ANDROID-PLAN §4 "Spending limits", "The screen"; VAULT-MESSAGING §10.18): the limits as they stand, what the
 * last 24 hours hold, and the two ways to change them. A form that only lowers is sent as `wallet.caps.set` (no password); one that
 * raises or removes, as `wallet.caps.raise` (PIN and password, one credential operation), with a lowered member in it too.
 * **Fail secure**: every error leaves the limits as they were (nothing is shown as changed before the vault answers `ok`), and the
 * wallet is read again.
 */
// One function per action of the screen (they share its state).
@Suppress("TooManyFunctions")
@HiltViewModel
class SpendingLimitsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val wallets: WalletsManager,
) : ViewModel() {
    val walletId: String = saved.toRoute<SpendingLimitsRoute>().walletId
    private val mutable = MutableStateFlow(SpendingLimitsUiState())
    val state: StateFlow<SpendingLimitsUiState> = mutable.asStateFlow()

    private var seen = false

    init {
        viewModelScope.launch {
            combine(wallets.wallets, wallets.availability, wallets.capsPending) { list, availability, pending ->
                Triple(list.firstOrNull { it.id == walletId }, availability, WalletsManager.isPending(pending, walletId))
            }.collect { (w, availability, pending) ->
                if (w == null) {
                    val gone = seen || availability != WalletAvailability.UNKNOWN
                    if (gone) mutable.update { it.copy(closed = true, pin = "", password = "") }
                    return@collect
                }
                seen = true
                mutable.update {
                    it.copy(
                        loaded = true, name = w.name, supported = w.capsSupported, perTx = w.perTxSats, perPeriod = w.perPeriodSats,
                        spent = w.spentSats, capsPending = pending,
                    )
                }
            }
        }
        // The screen reads the wallet when it opens: the limits and the period's total are the vault's, not a memory of it.
        viewModelScope.launch { readAgain() }
    }

    private suspend fun readAgain() {
        try {
            wallets.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: VaultFailure) {
            // What is shown stays; nothing here depends on it being fresh.
        }
    }

    fun openLower() = open(raise = false)

    fun openRaise() = open(raise = true)

    private fun open(raise: Boolean) {
        val s = mutable.value
        if (!s.idle || s.step != LimitsStep.OVERVIEW || !s.supported) return
        mutable.update {
            it.copy(
                step = if (raise) LimitsStep.RAISE else LimitsStep.LOWER, raiseForm = raise, form = CapsEditForm(),
                error = null, errorCode = null,
            )
        }
    }

    /** Back: a confirm to its form, a form to the overview. Not while the vault is answering. */
    fun back() {
        if (!mutable.value.idle) return
        mutable.update {
            when (it.step) {
                LimitsStep.CONFIRM_SET, LimitsStep.CONFIRM_RAISE -> it.copy(
                    step = if (it.raiseForm) LimitsStep.RAISE else LimitsStep.LOWER,
                    pin = "", password = "", error = null, errorCode = null,
                )
                LimitsStep.LOWER, LimitsStep.RAISE -> it.copy(step = LimitsStep.OVERVIEW, form = CapsEditForm())
                LimitsStep.OVERVIEW -> it
            }
        }
    }

    fun setPerTx(v: String) = mutable.update { it.copy(form = it.form.copy(perTx = LimitsInput.filter(v, it.form.btc)), error = null) }

    fun setPerPeriod(v: String) =
        mutable.update { it.copy(form = it.form.copy(perPeriod = LimitsInput.filter(v, it.form.btc)), error = null) }

    fun setRemoveTx(v: Boolean) = mutable.update { it.copy(form = it.form.copy(removeTx = v, perTx = ""), error = null) }

    fun setRemovePeriod(v: Boolean) = mutable.update { it.copy(form = it.form.copy(removePeriod = v, perPeriod = ""), error = null) }

    fun toggleUnit() = mutable.update { it.copy(form = it.form.toggled()) }

    fun setPin(v: String) =
        mutable.update { it.copy(pin = v.filter { c -> c in '0'..'9' }.take(PIN_LENGTH.last), error = null, errorCode = null) }

    fun setPassword(v: String) = mutable.update { it.copy(password = v, error = null, errorCode = null) }

    /** The form is right: on to the question. What only lowers goes to the question with no password; the rest asks for both. */
    fun next() {
        val s = mutable.value
        if (!s.idle || (s.step != LimitsStep.LOWER && s.step != LimitsStep.RAISE)) return
        val plan = s.plan
        if (!plan.ok) return
        mutable.update {
            it.copy(
                step = if (plan.action == CapsAction.SET) LimitsStep.CONFIRM_SET else LimitsStep.CONFIRM_RAISE,
                pin = "", password = "", error = null, errorCode = null,
            )
        }
    }

    /** "Lower the limit to {new}?" confirmed: `wallet.caps.set`, no password. */
    fun confirmSet() {
        val s = mutable.value
        val plan = s.plan
        if (!s.idle || s.step != LimitsStep.CONFIRM_SET || plan.action != CapsAction.SET) return
        mutable.update { it.copy(busy = true, error = null, errorCode = null) }
        viewModelScope.launch {
            val failure = try {
                wallets.lowerCaps(walletId, plan.setPerTx, plan.setPerPeriod)
                null
            } catch (e: VaultFailure) {
                e
            }
            finish(failure, LimitsStep.CONFIRM_SET)
        }
    }

    /** "Raise the limit …?" / "Remove the limit?" confirmed with the PIN and password: `wallet.caps.raise`. Both are blanked at once. */
    fun confirmRaise() {
        val s = mutable.value
        val plan = s.plan
        val ready = s.step == LimitsStep.CONFIRM_RAISE && plan.action == CapsAction.RAISE && s.secretsReady
        if (!s.idle || !ready) return
        val pin = s.pin
        val password = s.password
        mutable.update { it.copy(busy = true, pin = "", password = "", error = null, errorCode = null) }
        viewModelScope.launch {
            val failure = try {
                wallets.raiseCaps(walletId, plan.newPerTx, plan.newPerPeriod, pin, password)
                null
            } catch (e: VaultFailure) {
                e
            }
            finish(failure, LimitsStep.CONFIRM_RAISE)
        }
    }

    /**
     * The vault answered. `ok`: back to the overview, which shows what the vault now holds. Any error: the limits are as they were,
     * the wallet is read again, and the screen says why; a `conflict` (the wallet changed elsewhere), or a pending limit just
     * sent, goes back to the overview, the
     * others stay where the member can try again.
     */
    private suspend fun finish(failure: VaultFailure?, from: LimitsStep) {
        if (failure == null) {
            mutable.update { it.copy(busy = false, step = LimitsStep.OVERVIEW, form = CapsEditForm(), pin = "", password = "") }
            return
        }
        readAgain()
        // The limits the form was chosen against are not the vault's any more (a conflict, or a pending limit just sent).
        val stale = failure.kind == FailureKind.CONFLICT || failure.code == WalletsManager.CAPS_SETTLED
        mutable.update {
            it.copy(
                busy = false, error = failure.kind, errorCode = failure.code, pin = "", password = "",
                step = if (stale) LimitsStep.OVERVIEW else from,
                form = if (stale) CapsEditForm() else it.form,
            )
        }
    }

    fun dismissError() = mutable.update { it.copy(error = null, errorCode = null) }

    override fun onCleared() {
        mutable.update { it.copy(pin = "", password = "") }
    }
}
