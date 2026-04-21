package com.vettid.app.features.feed

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Backs GuidesListScreen with read-state so each row can show a
 * read / unread marker. The GuidesList route lives outside the
 * FeedViewModel's nav graph, so we spin up a tiny VM to expose the
 * singleton GuideReadTracker's state.
 */
@HiltViewModel
class GuidesListViewModel @Inject constructor(
    private val readTracker: GuideReadTracker,
) : ViewModel() {
    val readIds: StateFlow<Set<String>> = readTracker.readIds
}
