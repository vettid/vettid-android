package com.vettid.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.CanaryManifestInbox
import com.vettid.core.data.vault.CanaryManifestRepository
import com.vettid.core.data.vault.CanaryManifestView
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The canary manifest dialog at the root of the UI. */
sealed interface CanaryManifestPrompt {
    /** A verified document: install it? (the bytes stay in the ViewModel). */
    data class Confirm(val view: CanaryManifestView) : CanaryManifestPrompt

    /** The document was refused ([code]: a `CanaryManifestRepository.CODE_*`, or null). Nothing changed. */
    data class Refused(val code: String?) : CanaryManifestPrompt

    /** Installed. */
    data class Installed(val view: CanaryManifestView) : CanaryManifestPrompt
}

/**
 * A served manifest document shared to the app ([CanaryManifestInbox], VAULT-RELEASES §10.1 step 9): verified
 * first (pinned keys, format, serial rules), shown to the member, installed only on their confirmation.
 */
@HiltViewModel
class CanaryManifestViewModel @Inject constructor(
    private val inbox: CanaryManifestInbox,
    private val repo: CanaryManifestRepository,
) : ViewModel() {
    private val state = MutableStateFlow<CanaryManifestPrompt?>(null)
    val prompt: StateFlow<CanaryManifestPrompt?> = state.asStateFlow()
    private var pending: ByteArray? = null
    private var checking: Job? = null

    /**
     * Takes the documents shared to the app while it is called: by the root of the UI, only while its activity
     * is started. Another instance of the activity left in the background (a share sheet may open a second
     * one in a task of its own) must not take a document and ask about it where the member cannot see it.
     * The check runs in the ViewModel, so that stopping the UI does not lose a document already taken.
     */
    suspend fun receive() {
        inbox.document.filterNotNull().collect { doc ->
            inbox.consume()
            checking?.cancel()
            checking = viewModelScope.launch { check(doc) }
        }
    }

    private suspend fun check(doc: ByteArray) {
        state.value = try {
            val v = repo.checkCanaryManifest(doc)
            pending = doc
            CanaryManifestPrompt.Confirm(v)
        } catch (e: VaultFailure) {
            pending = null
            CanaryManifestPrompt.Refused(e.code)
        }
    }

    fun confirm() {
        val doc = pending ?: return
        pending = null
        viewModelScope.launch {
            state.value = try {
                CanaryManifestPrompt.Installed(repo.installCanaryManifest(doc))
            } catch (e: VaultFailure) {
                CanaryManifestPrompt.Refused(e.code)
            }
        }
    }

    fun dismiss() {
        pending = null
        state.value = null
    }
}
