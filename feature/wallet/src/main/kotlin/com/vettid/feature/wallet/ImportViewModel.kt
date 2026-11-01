package com.vettid.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.chain.Bip39
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.wallet.WalletAvailability
import com.vettid.core.wallet.WalletsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What the words typed so far are, checked against the BIP39 English list as they are typed (ANDROID-PLAN §4, "Import a wallet"). */
data class PhraseStatus(
    /** How many words are typed (a word being typed counts). */
    val count: Int = 0,
    /** The 1-based position of the first word that is not, and cannot become, a word of the list; null when there is none. */
    val unknown: Int? = null,
    /** The last word is a whole word of the list, or its end is not being typed (a space follows it). */
    val lastComplete: Boolean = false,
) {
    /** 12, 15, 18, 21 or 24 words, every one of the list: the checksum is the one thing left to check. */
    val ready: Boolean get() = unknown == null && count in Bip39.WORD_COUNTS && lastComplete
}

/** The checks of the import field, pure. */
object PhraseInput {
    /** The words of [text]: separated by any spaces or line breaks, any case. */
    fun tokens(text: CharSequence): List<String> = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    /** Each word against the list as typed: a word still being typed only has to begin one. */
    fun analyse(text: CharSequence): PhraseStatus {
        val words = tokens(text)
        if (words.isEmpty()) return PhraseStatus()
        val typing = !text.last().isWhitespace()
        val unknown = words.indices.firstOrNull { i ->
            if (i == words.lastIndex && typing) !Bip39.isPrefix(words[i]) else !Bip39.isWord(words[i])
        }
        return PhraseStatus(words.size, unknown?.plus(1), !typing || Bip39.isWord(words.last()))
    }

    /** Whether the checksum fits (every word known and 12, 15, 18, 21 or 24 of them): the last check before anything is sent. */
    fun checksumOk(text: CharSequence): Boolean = Bip39.check(tokens(text)) == Bip39.Result.Valid

    /** What is sent: the words in lower case with single spaces, in a [CharArray] the caller wipes. */
    fun normalised(text: CharSequence): CharArray = tokens(text).joinToString(" ").lowercase(Locale.ROOT).toCharArray()

    private val WHITESPACE = Regex("\\s+")
}

/** The passphrase rule (the vault's): printable ASCII, 0x20 to 0x7E, at most 256 bytes. */
object PassphraseRule {
    const val MAX_BYTES = 256

    fun valid(text: CharSequence): Boolean = text.length <= MAX_BYTES && text.all { it.code in FIRST..LAST }

    private const val FIRST = 0x20
    private const val LAST = 0x7E
}

enum class ImportStep {
    /** Name, network, the words, and "Advanced" (the passphrase). */
    FORM,

    /** "Set a spending limit?" */
    LIMITS,

    /** The VettID password; the wallet is imported. */
    PASSWORD,

    /** Imported, but the chosen limit could not be set yet, or this phone could not record the wallet as backed up. */
    DONE,
}

/** A vault refusal of the import that has its own text. */
enum class ImportProblem {
    /** `limit`: "Your vault holds 16 wallets, the most it can." */
    LIMIT,

    /** `exists`: a wallet with these keys is already in the vault. */
    EXISTS,
}

/**
 * The state of the Import flow. The words and the passphrase as typed are in it for the fields to draw (Compose draws a `String`);
 * both are blank once the flow ends. Prints its step only.
 */
data class ImportUiState(
    val step: ImportStep = ImportStep.FORM,
    val name: String = "",
    val networks: List<Network> = emptyList(),
    val network: Network? = null,
    val typed: String = "",
    val status: PhraseStatus = PhraseStatus(),
    /** "Continue" was pressed with words whose checksum does not fit: "These words aren't a valid recovery phrase." */
    val invalidPhrase: Boolean = false,
    val advanced: Boolean = false,
    val passphrase: String = "",
    val limits: LimitsForm = LimitsForm(),
    val password: String = "",
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val problem: ImportProblem? = null,
    /** The wallet was imported ([ImportStep.DONE] when its limit is still to be set, else the flow leaves for it). */
    val importedId: String? = null,
    val capsFailed: Boolean = false,
    /** This phone could not record the imported wallet as backed up: it shows "Not backed up". */
    val notRecorded: Boolean = false,
    /**
     * The member chose a limit but the vault cannot enforce one (the imported wallet came back without `caps`): it was not kept or
     * sent, and the notice says so. A limits step that was skipped (a listed wallet without `caps`) shows no notice.
     */
    val capsUnsupported: Boolean = false,
    /** The limits step was asked: Back from the password returns to it. */
    val limitsAsked: Boolean = false,
    /** The limits chosen could not be saved on this phone (kept for this session only), and the send failed. */
    val capsNotSaved: Boolean = false,
    /** The vault closed (locked, held, due) during the flow: the words are gone and the flow leaves. */
    val closed: Boolean = false,
) {
    val nameValid: Boolean get() = WalletNames.valid(name)
    val nameProblem: WalletNames.Problem? get() = if (name.isEmpty()) null else WalletNames.problem(name)
    val passphraseValid: Boolean get() = PassphraseRule.valid(passphrase)
    val limitsParse: LimitsParse get() = LimitsInput.parse(limits)
    val idle: Boolean get() = !busy && !closed

    /** Imported with nothing left to say or do here (no limit to retry, no notice): the flow goes on to the wallet. */
    val finished: Boolean get() = importedId != null && !capsFailed && !notRecorded && !capsUnsupported

    /** Nothing in progress, a password is typed and the limits are ones the vault would take. */
    val passwordReady: Boolean get() = idle && password.isNotEmpty() && limitsParse.ok

    /** "Continue" on the first step: a name, a network, words that are all of the list and fit a length, a passphrase that is allowed. */
    val formReady: Boolean get() = nameValid && network != null && status.ready && passphraseValid

    override fun toString(): String = "ImportUiState(step=$step)"
}

/**
 * Import a wallet ([ImportWalletRoute][com.vettid.core.data.wallet.ImportWalletRoute]; ANDROID-PLAN §4 "Wallet", screens table):
 * name, network, the words (each checked against the BIP39 English list as typed, then the checksum before anything is sent) and an
 * optional passphrase; then the limits step and the password; `wallet.create` with the words as `item`, and the chosen limits set
 * at once (an imported wallet starts backed up); the checksum is checked again inside [import], before anything is sent. The words
 * live as the text the field draws (Compose needs a `String`, blank once the flow ends); the array sent is made from it at the send
 * and wiped as soon as it is answered. The passphrase is also kept in a [CharArray], wiped when the flow ends, when "Advanced" is
 * folded away, when the vault closes and in [onCleared]. None of it is in saved state (this view model takes no
 * `SavedStateHandle`), a log, or the clipboard.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class ImportViewModel @Inject constructor(private val wallets: WalletsManager) : ViewModel() {
    private var pass = CharArray(0)

    private val mutable = MutableStateFlow(ImportUiState(networks = wallets.creatable, network = wallets.creatable.firstOrNull()))
    val state: StateFlow<ImportUiState> = mutable.asStateFlow()

    init {
        // §3.6.3, "Local state": nothing of a wallet is kept or shown once the vault closes.
        viewModelScope.launch {
            var seen = false
            wallets.availability.collect { a ->
                if (a == WalletAvailability.AVAILABLE) {
                    seen = true
                } else if (seen && a == WalletAvailability.UNKNOWN) {
                    wipe()
                    mutable.update { it.copy(closed = true, busy = false, password = "", typed = "", passphrase = "") }
                }
            }
        }
    }

    fun setName(v: String) = mutable.update { it.copy(name = v, error = null) }

    fun setNetwork(n: Network) = mutable.update { it.copy(network = n) }

    /** The words as typed: the state keeps the text for the field; the array that is sent is made from it at the send ([import]). */
    fun setWords(text: String) {
        mutable.update { it.copy(typed = text, status = PhraseInput.analyse(text), invalidPhrase = false, error = null, problem = null) }
    }

    /** "Advanced": folding it away also forgets the passphrase typed there (it is not sent behind a closed section). */
    fun toggleAdvanced() {
        if (mutable.value.advanced) {
            pass.fill(Char(0))
            pass = CharArray(0)
            mutable.update { it.copy(advanced = false, passphrase = "") }
        } else {
            mutable.update { it.copy(advanced = true) }
        }
    }

    fun setPassphrase(text: String) {
        pass.fill(Char(0))
        pass = text.toCharArray()
        mutable.update { it.copy(passphrase = text) }
    }

    fun setPassword(v: String) = mutable.update { it.copy(password = v, error = null, problem = null) }

    fun setLimitChoice(limit: Boolean) = mutable.update { it.copy(limits = it.limits.copy(limit = limit)) }

    fun setPerTx(v: String) = mutable.update { it.copy(limits = it.limits.copy(perTx = LimitsInput.filter(v, it.limits.btc))) }

    fun setPerPeriod(v: String) = mutable.update { it.copy(limits = it.limits.copy(perPeriod = LimitsInput.filter(v, it.limits.btc))) }

    fun toggleUnit() = mutable.update { it.copy(limits = it.limits.toggled()) }

    /** "Continue" from the first step: the checksum is checked here, before anything is sent; a wrong one stays on the step. */
    fun toLimits() {
        val s = mutable.value
        if (s.step != ImportStep.FORM || !s.idle || !s.formReady) return
        if (PhraseInput.checksumOk(s.typed)) {
            // A vault whose wallets give no `caps` cannot enforce a limit: no question, nothing chosen, nothing pending.
            if (wallets.capsOffered) {
                mutable.update { it.copy(step = ImportStep.LIMITS, limitsAsked = true, invalidPhrase = false) }
            } else {
                mutable.update { it.copy(step = ImportStep.PASSWORD, limitsAsked = false, limits = LimitsForm(), invalidPhrase = false) }
            }
        } else {
            mutable.update { it.copy(invalidPhrase = true) }
        }
    }

    fun toPassword() {
        val s = mutable.value
        if (s.step == ImportStep.LIMITS && s.idle && s.limitsParse.ok) mutable.update { it.copy(step = ImportStep.PASSWORD) }
    }

    /** Back one step; the password is left behind. */
    fun previous() {
        mutable.update {
            when (it.step) {
                ImportStep.LIMITS -> it.copy(step = ImportStep.FORM)
                ImportStep.PASSWORD -> it.copy(
                    step = if (it.limitsAsked) ImportStep.LIMITS else ImportStep.FORM, password = "", error = null, problem = null,
                )
                else -> it
            }
        }
    }

    /** "Import wallet": `wallet.create` with the words, the password blanked at once, the words wiped once it is answered. */
    fun import() {
        val s = mutable.value
        val network = s.network
        if (s.step != ImportStep.PASSWORD || !s.passwordReady || network == null) return
        // The checksum again, whatever the steps before did: nothing is sent for words that do not fit it.
        if (!s.status.ready || !PhraseInput.checksumOk(s.typed)) {
            mutable.update { it.copy(step = ImportStep.FORM, invalidPhrase = true, password = "") }
            return
        }
        val pw = s.password
        mutable.update { it.copy(busy = true, password = "", error = null, problem = null) }
        viewModelScope.launch {
            val phrase = PhraseInput.normalised(s.typed)
            try {
                val r = wallets.import(s.name, network, pw, phrase, pass.takeIf { it.isNotEmpty() }, s.limitsParse.caps)
                wipe()
                val notice = r.capsFailure != null || r.notRecorded || r.capsUnsupported
                mutable.update {
                    it.copy(
                        busy = false, typed = "", passphrase = "", status = PhraseStatus(), importedId = r.wallet.id,
                        capsFailed = r.capsFailure != null, notRecorded = r.notRecorded, capsNotSaved = r.capsNotSaved,
                        capsUnsupported = r.capsUnsupported,
                        step = if (notice) ImportStep.DONE else it.step,
                    )
                }
            } catch (e: VaultFailure) {
                refused(e)
            } finally {
                phrase.fill(Char(0))
            }
        }
    }

    private fun refused(e: VaultFailure) {
        val problem = when {
            e.kind == FailureKind.LIMIT -> ImportProblem.LIMIT
            e.code == EXISTS -> ImportProblem.EXISTS
            else -> null
        }
        mutable.update { it.copy(busy = false, error = e.kind.takeIf { problem == null }, problem = problem) }
    }

    /** "Try again" after the wallet was imported but its limit could not be set: the same `wallet.caps.set`, nothing else. */
    fun retryCaps() {
        val id = mutable.value.importedId ?: return
        val s = mutable.value
        if (s.step != ImportStep.DONE || !s.capsFailed || !s.idle) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = try {
                wallets.settleCaps(id)
                true
            } catch (_: VaultFailure) {
                false
            }
            mutable.update { it.copy(busy = false, capsFailed = !ok) }
        }
    }

    private fun wipe() {
        pass.fill(Char(0))
        pass = CharArray(0)
    }

    override fun onCleared() {
        wipe()
        super.onCleared()
    }

    private companion object {
        const val EXISTS = "exists"
    }
}
