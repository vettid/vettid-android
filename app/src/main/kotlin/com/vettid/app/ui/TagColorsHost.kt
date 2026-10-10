package com.vettid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.items.SharingRepository
import com.vettid.core.data.items.TagView
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.ui.theme.LocalTagColors
import com.vettid.core.ui.theme.TagColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Tag colours while the vault is open (owner decision 2026-10-09): the registry's stored colours for every screen
 * ([LocalTagColors]), and a colour stored for every tag that has none. Lives with the open app ([VettIdApp]'s main
 * destination), so nothing runs while the vault is locked. The registry is read when the vault opens and again after
 * it was dropped (a `tag.changed`, a tag first used on an item); each time it is listed, tags without a colour get the
 * least-used palette colour ([TagColors.next]) — never while the daily owner check is due or the vault held (§3.6),
 * when they keep their hash colours.
 */
@HiltViewModel
class TagColorsViewModel @Inject constructor(
    private val sharing: SharingRepository,
    private val ownerCheck: OwnerCheckRepository,
) : ViewModel() {
    val colors: StateFlow<Map<String, String>> = sharing.colors

    private var assigning: Job? = null
    private var again = false

    init {
        viewModelScope.launch {
            sharing.tags.collect { r ->
                if (r == null) {
                    // Dropped: read again shortly (a burst of `tag.changed` is one read).
                    delay(REREAD_DELAY_MS)
                    quietly { sharing.refreshTags() }
                } else {
                    assign()
                }
            }
        }
    }

    private fun gated(): Boolean =
        ownerCheck.ownerCheck.value?.gated(Instant.now()) == true || ownerCheck.lockedByOwnerCheck.value

    /** One assignment at a time; a registry listed meanwhile is looked at once it ends. */
    private fun assign() {
        if (gated()) return
        if (assigning?.isActive == true) {
            again = true
            return
        }
        assigning = viewModelScope.launch {
            do {
                again = false
                quietly { sharing.assignColors(::next) }
            } while (again && !gated())
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: VaultFailure) {
            // Offline, held or refused: the tags keep their hash colours until the next listing.
        }
    }

    companion object {
        const val REREAD_DELAY_MS = 500L

        /** [TagColors.next] over the registry's entries. */
        fun next(tags: List<TagView>): Pair<String, String>? = TagColors.next(tags.map { it.tag to it.color })
    }
}

/** Provides the registry's tag colours ([LocalTagColors]) to [content] and keeps them assigned. */
@Composable
fun TagColorsHost(content: @Composable () -> Unit) {
    val vm: TagColorsViewModel = hiltViewModel()
    val colors by vm.colors.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalTagColors provides colors, content = content)
}
