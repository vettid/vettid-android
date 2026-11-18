package com.vettid.feature.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * (Rule 3) Every vault the app talks to has photos: the composer offers the photo button with no availability probe and
 * no flag. Where there is no composer (a connection that is not active, a service) there is no button.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoButtonScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val sam = ConnectionInfo("c1", "Sam", ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee")

    @Test
    fun anActiveConversationOffersThePhotoButton() {
        rule.setContent { ConversationScreen(ConversationUiState("c1", sam, emptyList(), loading = false), ConversationActions()) }
        rule.onNodeWithTag("photo").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun aConversationThatIsNotActiveHasNoPhotoButton() {
        val stale = sam.copy(state = ConnectionState.STALE)
        rule.setContent { ConversationScreen(ConversationUiState("c1", stale, emptyList(), loading = false), ConversationActions()) }
        rule.onNodeWithTag("photo").assertDoesNotExist()
    }

    @Test
    fun aServiceHasNoPhotoButton() {
        val service = sam.copy(service = true)
        rule.setContent { ConversationScreen(ConversationUiState("c1", service, emptyList(), loading = false), ConversationActions()) }
        rule.onNodeWithTag("photo").assertDoesNotExist()
    }
}
