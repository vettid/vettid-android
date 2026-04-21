package com.vettid.app.features.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Lives only to mark the current guide as read in the shared
 * GuideReadTracker when the detail screen mounts. The Guide route
 * is reached from two places:
 *
 *   - feed pending-row tap (routes via FeedViewModel.markGuideRead)
 *   - guide list tap (the list route has no FeedViewModel in scope)
 *
 * The previous implementation only marked events read (via
 * feedViewModel.markAsRead) when an eventId was supplied — the list
 * route passes an empty eventId, so reading a guide from there never
 * cleared its unread dot. Marking read on mount through the
 * singleton tracker makes both paths work.
 */
@HiltViewModel
class GuideDetailViewModel @Inject constructor(
    private val readTracker: GuideReadTracker,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val guideId: String = savedStateHandle["guideId"] ?: ""

    init {
        if (guideId.isNotEmpty()) {
            readTracker.markRead(guideId)
        }
    }
}
