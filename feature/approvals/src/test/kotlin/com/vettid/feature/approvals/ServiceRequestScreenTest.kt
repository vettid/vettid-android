package com.vettid.feature.approvals

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.AuthPurpose
import com.vettid.core.data.social.RequestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** ANDROID-PLAN §4 "Service connections" (0.1.90) in Approvals: the request's line and caveat, and the "Service" label on a row. */
@RunWith(RobolectricTestRunner::class)
class ServiceRequestScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-11-03T12:00:00Z")

    private fun outgoing(service: Boolean) = Approval.OutgoingRequest(
        "c1", "123456", remote = false, name = "Acme", state = RequestState.PENDING, peerApproved = false, introducedBy = null,
        receivedAt = now, exp = null, service = service,
    )

    private fun show(a: Approval.OutgoingRequest, fingerprint: String? = null) = rule.setContent {
        ApprovalDetailScreen(ApprovalDetailUiState(key = a.key, approval = a, peerFingerprint = fingerprint), DecisionActions())
    }

    @Test
    fun aServiceRequestShowsTheLineTheCaveatTheNameLabelAndTheFingerprint() {
        show(outgoing(true), "9a1f bb7d 873e eafb")
        rule.onNodeWithText("This is a service, not a person.").assertIsDisplayed()
        rule.onNodeWithText("The invitation says so. VettID has not checked who runs it.").assertIsDisplayed()
        rule.onNodeWithText("Name given by the service").assertIsDisplayed()
        rule.onNodeWithTag("service_request_fingerprint").assertExists()
        rule.onNodeWithTag("sas").assertExists()
    }

    @Test
    fun aRequestWithoutPeerKindShowsNothingNew() {
        show(outgoing(false))
        assertEquals(0, rule.onAllNodesWithText("This is a service, not a person.").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Name given by the service").fetchSemanticsNodes().size)
        rule.onNodeWithText("Invitation from").assertIsDisplayed()
    }

    @Test
    fun aSignInFromAServiceNamesItWithTheServiceLabel() {
        val a = Approval.Authentication("a1", "c1", "Sign in", now, null, "Acme", connectionService = true)
        rule.setContent { ApprovalDetailScreen(ApprovalDetailUiState(key = a.key, approval = a), DecisionActions()) }
        assertTrue(rule.onAllNodesWithText("Acme", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Service", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    private fun signIn(service: Boolean) = Approval.Authentication(
        "a1", "c1", "Sign in", now, null, "Acme", AuthPurpose.SIGNIN, "42", connectionService = service,
    )

    @Test
    fun aServiceSignInWithAMatchCodeStillCarriesTheServiceLabel() {
        val a = signIn(true)
        rule.setContent { ApprovalDetailScreen(ApprovalDetailUiState(key = a.key, approval = a), DecisionActions()) }
        rule.onNodeWithTag("service_label").assertExists()
        rule.onNodeWithTag("match_code").assertExists()
    }

    @Test
    fun aPersonSignInHasNoServiceLabel() {
        val a = signIn(false)
        rule.setContent { ApprovalDetailScreen(ApprovalDetailUiState(key = a.key, approval = a), DecisionActions()) }
        assertEquals(0, rule.onAllNodesWithText("Service", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("service_label").assertDoesNotExist()
    }

    @Test
    fun theFingerprintHasTheVaultKeyLabel() {
        show(outgoing(true), "9a1f bb7d 873e eafb")
        rule.onNodeWithText("Vault key fingerprint").assertExists()
    }
}
