package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.wallet.BackupRoute
import com.vettid.core.wallet.RevealedPhrase
import com.vettid.core.wallet.WalletAvailability
import com.vettid.core.wallet.WalletsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.CharBuffer
import javax.inject.Inject
import kotlin.random.Random

/** One row of the list: what the vault says of the wallet, and what this phone knows (backed up, the legacy test chain). */
data class WalletRow(val id: String, val name: String, val network: Network?, val networkName: String, val backedUp: Boolean) {
    override fun toString(): String = "WalletRow"
}

data class WalletsUiState(
    val loading: Boolean = true,
    val rows: List<WalletRow> = emptyList(),
    /** The last read failed (shown with Try again: a read that failed at the unlock is not tried again by itself). */
    val failed: FailureKind? = null,
    /** The build makes wallets on test networks only (no mainnet): the banner shows before the first wallet too. */
    val testBuild: Boolean = false,
) {
    /** "Test network: these coins have no value." Every wallet the staging app holds is on one. */
    val testNetwork: Boolean get() = rows.any { it.network != Network.MAINNET }
}

/** The Wallet list (ANDROID-PLAN §4 "Wallet", screens table): `wallet.list`, with this phone's backed-up flags. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WalletsViewModel @Inject constructor(private val wallets: WalletsManager) : ViewModel() {
    private val failure = MutableStateFlow<FailureKind?>(null)

    // The flags are this phone's (backed up, the legacy test chain): a change of them (a check just passed) reads the rows again.
    val state: StateFlow<WalletsUiState> = combine(
        wallets.wallets,
        wallets.availability,
        failure,
        wallets.flagsChanged,
    ) { list, availability, failed, _ -> Triple(list, availability, failed) }.mapLatest { (list, availability, failed) ->
        WalletsUiState(
            loading = availability == WalletAvailability.UNKNOWN && failed == null,
            rows = list.map { w -> WalletRow(w.id, w.name, wallets.networkOf(w), w.networkName, wallets.backedUp(w.id)) },
            failed = failed,
            testBuild = !wallets.offers(Network.MAINNET),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WalletsUiState())

    /** Reads the list again: on opening the screen, and from Try again. */
    fun refresh() {
        viewModelScope.launch {
            failure.value = null
            try {
                wallets.refresh()
            } catch (e: VaultFailure) {
                failure.value = e.kind
            }
        }
    }
}

/** The steps of making a wallet and backing it up (ANDROID-PLAN §4 "Creating and backing up"). */
enum class BackupStep {
    /** New wallet: name and network. */
    FORM,

    /** "Set a spending limit?" (ANDROID-PLAN §4 "Spending limits"): after the name and network. */
    LIMITS,

    /** New wallet: the password, and the wallet is created. */
    CREATE,

    /** A wallet that is not backed up yet: the password, to show the phrase again. */
    PASSWORD,

    /** The words, `FLAG_SECURE`. */
    PHRASE,

    /** Three words asked back, `FLAG_SECURE`. */
    CHECK,

    DONE,
}

/**
 * The phrase's words while they are shown or checked (ANDROID-PLAN §4 "Creating and backing up", 3): `CharArray`s, wiped when the
 * flow ends. A word becomes a `String` only to be drawn ([word]); the check compares the arrays ([matches], [same]). Prints its type
 * name only; equal only to itself.
 */
class PhraseWords(private val words: List<CharArray>, private val pass: CharArray? = null) {
    val size: Int get() = words.size

    /** The wallet has a BIP39 passphrase (an imported one): it is shown under the words, and is part of the backup. */
    val hasPassphrase: Boolean get() = pass != null

    /** The passphrase, for drawing it; "" without one. */
    fun passphrase(): String = pass?.let(::String).orEmpty()

    /** The word at [i], for drawing it. */
    fun word(i: Int): String = String(words[i])

    /** Whether [typed] is the word at [i], in any case, spaces around it ignored. */
    fun matches(i: Int, typed: CharSequence): Boolean = equalIgnoringCase(words[i], typed.trim())

    /** Whether the words at [i] and [j] are the same word, in any case. */
    fun same(i: Int, j: Int): Boolean = equalIgnoringCase(words[i], CharBuffer.wrap(words[j]))

    fun wipe() {
        words.forEach { it.fill(Char(0)) }
        pass?.fill(Char(0))
    }

    override fun toString(): String = "PhraseWords"

    private companion object {
        fun equalIgnoringCase(w: CharArray, t: CharSequence): Boolean =
            w.size == t.length && w.indices.all { k -> w[k].lowercaseChar() == t[k].lowercaseChar() }
    }
}

/**
 * The state of the create / back-up flow. It holds the phrase ([words]) only while it is shown or checked, and never prints it or
 * the password ([toString]); nothing of it is put in saved state.
 */
data class BackupUiState(
    val step: BackupStep,
    val name: String = "",
    val networks: List<Network> = emptyList(),
    val network: Network? = null,
    val password: String = "",
    /** The vault PIN, asked with the password for the phrase of a wallet that has a spending limit; nothing is saved of it. */
    val pin: String = "",
    /** The limits step's input ("No limit" until the member chooses). */
    val limits: LimitsForm = LimitsForm(),
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val errorCode: String? = null,
    /** The words, in order, while the phrase or the check is shown. */
    val words: PhraseWords? = null,
    /** The three positions asked (0-based), in order, and which of them is asked now. */
    val asked: List<Int> = emptyList(),
    val askIndex: Int = 0,
    /** The positions of the four words offered for the position asked now (the right one and three others), shuffled. */
    val candidates: List<Int> = emptyList(),
    val typed: String = "",
    /**
     * The position answered wrong (0-based): the check ends there ("That isn't word n"), and only "Show the words again" is left;
     * the next check asks new positions, so trying every candidate never passes it.
     */
    val wrong: Int? = null,
    /** Why the vault refused the last request, when it has its own text ([BackupProblem]); else [error]. */
    val problem: BackupProblem? = null,
    /** The wallet has a spending limit: showing its phrase needs the PIN as well as the password ([pin]). */
    val capped: Boolean = false,
    /** The wallet was made, but its phrase could not be shown: the member backs it up from its row. */
    val revealFailed: Boolean = false,
    /** Done with the phrase checked. */
    val backedUp: Boolean = false,
    /** The phrase was checked, but this phone could not record it: Try again ([BackupViewModel.saveAgain]). */
    val saveFailed: Boolean = false,
    /** The vault cannot enforce spending limits: the limits step was not asked, or the limits chosen were not kept (the notice says so). */
    val capsUnsupported: Boolean = false,
    /** The limits step was asked (the vault enforces limits as far as is known): Back from the password returns to it. */
    val limitsAsked: Boolean = false,
    /** The limits chosen could not be saved on this phone: they are kept for this session only, so the backup should be finished now. */
    val capsNotSaved: Boolean = false,
    /** Backed up, but the chosen spending limit is not set yet (no Receive until it is): Try again ([BackupViewModel.retryCaps]). */
    val capsFailed: Boolean = false,
    /** The vault closed (locked, held, due) during the flow: the words are gone and the flow leaves. */
    val closed: Boolean = false,
) {
    val nameValid: Boolean get() = WalletNames.valid(name)

    /** What is wrong with a typed name, for its text; null when it is fine or nothing is typed yet. */
    val nameProblem: WalletNames.Problem? get() = if (name.isEmpty()) null else WalletNames.problem(name)

    /** What the limits step says: No limit, or the limits to set, and what is wrong with them. */
    val limitsParse: LimitsParse get() = LimitsInput.parse(limits)

    /** The PIN is typed in full enough to send (6 to 32 digits, VAULT-MESSAGING §11.3); a wallet without a limit does not ask. */
    val pinReady: Boolean get() = !capped || pin.length in PIN_LENGTH

    /** Everything the last step needs: a name, a network, limits the vault would take, a password, and nothing in progress. */
    val createReady: Boolean get() = idle && formReady && password.isNotEmpty() && limitsParse.ok

    /** A name and a network: the first step may go on. */
    val formReady: Boolean get() = nameValid && network != null

    /** Nothing in progress, and the vault still open: an action may start. */
    val idle: Boolean get() = !busy && !closed

    /** An answer may be given: the check is shown, idle, and not ended by a wrong answer. */
    val answering: Boolean get() = step == BackupStep.CHECK && idle && wrong == null

    override fun toString(): String = "BackupUiState(step=$step)"
}

/** A vault refusal of the flow that has its own text. */
enum class BackupProblem {
    /** `limit` on `wallet.create`: "Your vault holds 16 wallets, the most it can." */
    LIMIT,

    /** `pin_required` on the reveal: a wallet with a spending limit (set since the list was read): the PIN field is shown. */
    PIN_REQUIRED,
}

/** A vault PIN is 6 to 32 ASCII digits (VAULT-MESSAGING §11.3). */
internal val PIN_LENGTH = 6..32

/**
 * The rule for a wallet's name (ANDROID-PLAN §4 screens table): 1–128 bytes of UTF-8, as the vault counts them, not blank, no
 * control characters.
 */
object WalletNames {
    const val MAX_BYTES = 128

    enum class Problem { BLANK, TOO_LONG, CONTROL }

    fun problem(name: String): Problem? = when {
        name.isBlank() -> Problem.BLANK
        name.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> Problem.TOO_LONG
        name.codePoints().anyMatch { Character.getType(it) == Character.CONTROL.toInt() } -> Problem.CONTROL
        else -> null
    }

    fun valid(name: String): Boolean = problem(name) == null
}

/**
 * Make a wallet and back it up ([NewWalletRoute]), or back up one that is not yet ([BackupRoute]): the password is typed once (for
 * `wallet.create` and the `item.reveal` right after, or for the reveal alone), the words shown, three asked back, and the wallet
 * marked backed up on this phone. The phrase is wiped when the flow ends ([onCleared]) and when the vault closes during it.
 */
// One function per action of the flow's screens (the steps share the state and the wipe).
@Suppress("TooManyFunctions")
@HiltViewModel
class BackupViewModel internal constructor(
    saved: SavedStateHandle,
    private val wallets: WalletsManager,
    private val random: Random,
) : ViewModel() {
    @Inject constructor(saved: SavedStateHandle, wallets: WalletsManager) : this(saved, wallets, Random.Default)

    /** The wallet being backed up; null while a new one is being made. */
    private var walletId: String? = runCatching { saved.toRoute<BackupRoute>().walletId }.getOrNull()
    private var words: PhraseWords? = null

    private val mutable = MutableStateFlow(
        if (walletId == null) {
            BackupUiState(BackupStep.FORM, networks = wallets.creatable, network = wallets.creatable.firstOrNull())
        } else {
            BackupUiState(BackupStep.PASSWORD, capped = wallets.wallets.value.firstOrNull { it.id == walletId }?.capped == true)
        },
    )
    val state: StateFlow<BackupUiState> = mutable.asStateFlow()

    init {
        // §3.6.3, "Local state": nothing of a wallet is kept or shown once the vault closes (a clear makes the wallets UNKNOWN again).
        viewModelScope.launch {
            var seen = false
            wallets.availability.collect { a ->
                if (a == WalletAvailability.AVAILABLE) {
                    seen = true
                } else if (seen && a == WalletAvailability.UNKNOWN) {
                    forgetWords()
                    mutable.update { it.copy(closed = true, password = "", busy = false) }
                }
            }
        }
    }

    fun setName(v: String) = mutable.update { it.copy(name = v, error = null) }

    fun setNetwork(n: Network) = mutable.update { it.copy(network = n) }

    fun setPassword(v: String) = mutable.update { it.copy(password = v, error = null, errorCode = null, problem = null) }

    fun setPin(v: String) =
        mutable.update { it.copy(pin = v.filter { c -> c in '0'..'9' }, error = null, errorCode = null, problem = null) }

    /** "Continue" from the name and network: the limits step. */
    fun toLimits() {
        val s = mutable.value
        if (s.step != BackupStep.FORM || !s.idle || !s.formReady) return
        // A vault whose wallets give no `caps` cannot enforce a limit: no question, nothing chosen, nothing pending.
        if (wallets.capsOffered) {
            mutable.update { it.copy(step = BackupStep.LIMITS, limitsAsked = true) }
        } else {
            mutable.update { it.copy(step = BackupStep.CREATE, limitsAsked = false, limits = LimitsForm()) }
        }
    }

    /** "Continue" from the limits: the password (only with limits the vault would take). */
    fun toPassword() {
        val s = mutable.value
        if (s.step == BackupStep.LIMITS && s.idle && s.limitsParse.ok) mutable.update { it.copy(step = BackupStep.CREATE) }
    }

    /** Back one step of making a wallet (the name and network, the limits); the password is left behind. */
    fun previous() {
        mutable.update {
            when (it.step) {
                BackupStep.LIMITS -> it.copy(step = BackupStep.FORM)
                BackupStep.CREATE -> it.copy(
                    step = if (it.limitsAsked) BackupStep.LIMITS else BackupStep.FORM,
                    password = "", error = null, errorCode = null, problem = null,
                )
                else -> it
            }
        }
    }

    fun setLimitChoice(limit: Boolean) = mutable.update { it.copy(limits = it.limits.copy(limit = limit)) }

    fun setPerTx(v: String) = mutable.update { it.copy(limits = it.limits.copy(perTx = LimitsInput.filter(v, it.limits.btc))) }

    fun setPerPeriod(v: String) = mutable.update { it.copy(limits = it.limits.copy(perPeriod = LimitsInput.filter(v, it.limits.btc))) }

    /** The toggle between sats and bitcoin: what is typed is carried over when it is a valid amount, else cleared. */
    fun toggleUnit() = mutable.update { it.copy(limits = it.limits.toggled()) }

    /** "Create wallet": `wallet.create`, then the reveal with the same password; the password is blanked at once. */
    fun create() {
        val s = mutable.value
        val network = s.network
        if (!s.createReady || network == null) return
        val pw = s.password
        val parse = s.limitsParse
        mutable.update { it.copy(busy = true, password = "", error = null, errorCode = null, problem = null) }
        viewModelScope.launch {
            try {
                // Created without limits; the ones chosen are kept as pending and sent once the phrase is checked.
                val c = wallets.create(s.name, network, pw, parse.caps)
                walletId = c.wallet.id
                mutable.update { it.copy(capsNotSaved = c.capsNotSaved, capsUnsupported = c.capsUnsupported) }
                val p = c.phrase
                if (p == null) {
                    mutable.update { it.copy(busy = false, step = BackupStep.DONE, revealFailed = true) }
                } else {
                    show(p)
                }
            } catch (e: VaultFailure) {
                refused(e)
            }
        }
    }

    /** "Show the recovery phrase" of a wallet that is not backed up yet. */
    fun reveal() {
        val id = walletId ?: return
        val s = mutable.value
        if (!s.idle || s.password.isEmpty() || !s.pinReady) return
        val pw = s.password
        val pin = s.pin.takeIf { s.capped }
        mutable.update { it.copy(busy = true, password = "", pin = "", error = null, errorCode = null, problem = null) }
        viewModelScope.launch {
            try {
                show(wallets.revealPhrase(id, pw, pin))
            } catch (e: VaultFailure) {
                refused(e)
            }
        }
    }

    private fun refused(e: VaultFailure) {
        val problem = when {
            e.kind == FailureKind.LIMIT -> BackupProblem.LIMIT
            e.code == "pin_required" -> BackupProblem.PIN_REQUIRED
            else -> null
        }
        mutable.update {
            it.copy(
                busy = false,
                pin = "",
                error = e.kind.takeIf { problem == null },
                errorCode = e.code,
                problem = problem,
                // A limit set since the list was read: the same note, and no button.
                capped = it.capped || problem == BackupProblem.PIN_REQUIRED,
            )
        }
    }

    /** The words are copied out of [p], which is wiped at once: only [words] holds them from here. */
    private fun show(p: RevealedPhrase) {
        val w = PhraseWords(p.words(), p.passphrase())
        p.wipe()
        if (mutable.value.closed) {
            w.wipe()
            return
        }
        words?.wipe()
        words = w
        mutable.update { it.copy(busy = false, step = BackupStep.PHRASE, words = w) }
    }

    /** "Continue" from the words: three positions, at random, asked in order. */
    fun startCheck() {
        val w = words ?: return
        if (w.size < ASKED) return
        val asked = (0 until w.size).shuffled(random).take(ASKED).sorted()
        val offered = candidates(w, asked[0], random)
        mutable.update { it.copy(step = BackupStep.CHECK, asked = asked, askIndex = 0, wrong = null, typed = "", candidates = offered) }
    }

    /** Typing never reopens a check a wrong answer ended. */
    fun setTyped(v: String) = mutable.update { if (!it.answering) it else it.copy(typed = v) }

    /** The typed word for the position asked now (any case and spacing). */
    fun answer(typed: String) = check { w, at -> w.matches(at, typed) }

    /** A candidate tapped: the word at [position] for the position asked now. */
    fun pick(position: Int) = check { w, at -> w.same(at, position) }

    private inline fun check(right: (PhraseWords, Int) -> Boolean) {
        val s = mutable.value
        val w = words
        if (!s.answering || w == null) return
        val at = s.asked[s.askIndex]
        val next = s.askIndex + 1
        when {
            !right(w, at) -> mutable.update { it.copy(wrong = at, typed = "", candidates = emptyList()) }
            next < s.asked.size -> mutable.update {
                it.copy(askIndex = next, wrong = null, typed = "", candidates = candidates(w, s.asked[next], random))
            }
            else -> {
                mutable.update { it.copy(busy = true, wrong = null, typed = "") }
                save()
            }
        }
    }

    /**
     * All three right: the words are wiped whatever happens; "Backed up" is shown only once this phone recorded it. Only then is the
     * spending limit the member chose sent (`wallet.caps.set`), so that the phrase step never needed the PIN; if that fails the
     * limit stays pending, the wallet has no Receive, and [BackupUiState.capsFailed] offers Try again.
     */
    private fun save() {
        val id = walletId ?: return
        viewModelScope.launch {
            var kept = false
            try {
                kept = wallets.confirmBackup(id)
            } finally {
                forgetWords()
            }
            val capsFailed = !setCaps(id)
            mutable.update {
                it.copy(
                    busy = false, step = BackupStep.DONE, backedUp = kept, saveFailed = !kept, capsFailed = capsFailed,
                    // Once the limit is set it no longer matters that this phone could not save it.
                    capsNotSaved = it.capsNotSaved && capsFailed,
                )
            }
        }
    }

    /** Sends the wallet's pending limit, if it has one; false when that failed (it stays pending). */
    private suspend fun setCaps(id: String): Boolean = try {
        wallets.settleCaps(id)
        true
    } catch (_: VaultFailure) {
        false
    }

    /** "Try again" after the limit could not be set: the same `wallet.caps.set`, nothing else. */
    fun retryCaps() {
        val id = walletId ?: return
        val s = mutable.value
        if (s.step != BackupStep.DONE || !s.capsFailed || !s.idle) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = setCaps(id)
            mutable.update { it.copy(busy = false, capsFailed = !ok, capsNotSaved = it.capsNotSaved && !ok) }
        }
    }

    /** "Try again" after the phrase was checked but could not be recorded (the check is not asked again). */
    fun saveAgain() {
        val s = mutable.value
        if (s.step != BackupStep.DONE || !s.saveFailed || !s.idle) return
        mutable.update { it.copy(busy = true) }
        save()
    }

    /** "Show the words again" from the check. */
    fun showAgain() {
        if (mutable.value.busy || words == null) return
        mutable.update { it.copy(step = BackupStep.PHRASE, wrong = null, typed = "") }
    }

    private fun forgetWords() {
        words?.wipe()
        words = null
        mutable.update { it.copy(words = null, candidates = emptyList(), typed = "") }
    }

    override fun onCleared() {
        forgetWords()
        super.onCleared()
    }

    private companion object {
        const val ASKED = 3
    }
}

private const val CANDIDATES = 4

/** The right word's position [at] and three others of the phrase (none the same word as another), shuffled. */
internal fun candidates(w: PhraseWords, at: Int, random: Random): List<Int> {
    val others = mutableListOf<Int>()
    for (i in (0 until w.size).shuffled(random)) {
        if (others.size == CANDIDATES - 1) break
        if (i != at && !w.same(i, at) && others.none { w.same(it, i) }) others += i
    }
    return (others + at).shuffled(random)
}
