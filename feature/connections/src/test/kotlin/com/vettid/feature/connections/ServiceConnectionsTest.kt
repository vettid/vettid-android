// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import com.vettid.core.ui.components.ShellChrome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ANDROID-PLAN §4 "Service connections" (0.1.90): the request, the Services section, the detail and `forbidden`. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServiceConnectionsTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val social = FakeSocial()
    private val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
    private val fp = "9a1f bb7d 873e eafb 494b ef94 f072 7b25"

    private fun service(id: String = "s1", name: String = "Acme", favorite: Boolean = false) = ConnectionInfo(
        id, name, ConnectionState.ACTIVE, favorite = favorite, firstName = name, lastName = "Co-op", service = true, keyFingerprint = fp,
    )

    private fun person(id: String = "c1") = ConnectionInfo(id, "Ada", ConnectionState.ACTIVE, firstName = "Ada", lastName = "Lovelace")

    // --- the connection request ("Compare the code") ---

    private fun compare(service: Boolean) = rule.setContent {
        AcceptScreen(
            AcceptUiState(
                step = AcceptStep.COMPARE, connectionId = "c9", name = "Acme", sas = "123456", service = service,
                keyFingerprint = fp.takeIf { service },
            ),
            AcceptActions(),
        )
    }

    @Test
    fun aRequestFromAServiceSaysSoWithTheCaveatAndTheServicesOwnNameLabel() {
        compare(service = true)
        rule.onNodeWithText("This is a service, not a person.").assertIsDisplayed()
        rule.onNodeWithText("The invitation says so. VettID has not checked who runs it.").assertIsDisplayed()
        rule.onNodeWithText("Name given by the service").assertIsDisplayed()
        rule.onNodeWithTag("service_request_fingerprint").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Name on their VettID account").fetchSemanticsNodes().size)
        // Under the name, above the code.
        val line = rule.onNodeWithTag("service_request_line").getUnclippedBoundsInRoot().top
        val name = rule.onNodeWithTag("request_name").getUnclippedBoundsInRoot().top
        val code = rule.onNodeWithTag("sas").getUnclippedBoundsInRoot().top
        assertTrue(name < line && line < code)
    }

    @Test
    fun aRequestWithoutPeerKindShowsNothingNew() {
        compare(service = false)
        assertEquals(0, rule.onAllNodesWithText("This is a service, not a person.").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("The invitation says so", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Name given by the service").fetchSemanticsNodes().size)
        rule.onNodeWithTag("sas").assertExists()
    }

    @Test
    fun theAcceptKnowsAServiceFromTheAnswerAndFromTheOutgoingRequest() = runTest {
        social.acceptResult = com.vettid.core.data.social.AcceptedConnection("c5", "Acme", service = true)
        social.seed(listOf(service("c5").copy(state = ConnectionState.PENDING)))
        val vm = AcceptViewModel(SavedStateHandle(), social, social)
        vm.setInput("test-link")
        vm.accept()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.service)

        // The answer lacked it but the outgoing request has it.
        val b = FakeSocial()
        b.acceptResult = com.vettid.core.data.social.AcceptedConnection("c6", "Acme")
        val vm2 = AcceptViewModel(SavedStateHandle(), b, b)
        vm2.setInput("test-link")
        vm2.accept()
        advanceUntilIdle()
        assertFalse(vm2.uiState.value.service)
        b.approvals.value = listOf(
            com.vettid.core.data.social.Approval.OutgoingRequest(
                "c6", "654321", remote = false, name = "Acme", state = com.vettid.core.data.social.RequestState.PENDING,
                peerApproved = false, introducedBy = null, receivedAt = java.time.Instant.now(), exp = null, service = true,
            ),
        )
        advanceUntilIdle()
        assertTrue(vm2.uiState.value.service)
        assertEquals(AcceptStep.COMPARE, vm2.uiState.value.step)
    }

    // --- lists ---

    @Test
    fun theServicesSectionAppearsOnlyWithAServiceAndStarredServicesComeFirst() = runTest {
        social.seed(listOf(person(), service("s1", "Zeta"), service("s2", "Alpha"), service("s3", "Mid", favorite = true)))
        val vm = ConnectionsViewModel(social, social, FakeVault())
        advanceUntilIdle()
        assertEquals(listOf("c1"), vm.uiState.value.connections.map { it.id })
        assertEquals(listOf("s3", "s2", "s1"), vm.uiState.value.services.map { it.id })
    }

    @Test
    fun theSectionIsShownOnlyWhenThereIsAService() {
        rule.setContent { ConnectionsScreen(ConnectionsUiState(loading = false, connections = listOf(person())), chrome, ConnectionsActions()) }
        rule.onNodeWithTag("connection_c1").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Services").fetchSemanticsNodes().size)
    }

    @Test
    fun aServiceRowHasTheLabelAndTheSectionHeaderBelowThePeople() {
        rule.setContent {
            ConnectionsScreen(
                ConnectionsUiState(loading = false, connections = listOf(person()), services = listOf(service())), chrome, ConnectionsActions(),
            )
        }
        val header = rule.onNodeWithText("Services").getUnclippedBoundsInRoot().top
        val people = rule.onNodeWithTag("connection_c1").getUnclippedBoundsInRoot().top
        assertTrue(people < header)
        // The service row carries the label (the header is "Services", the label "Service · Active").
        rule.onNodeWithTag("connection_s1").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Service · ", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // --- the detail ---

    private fun detail(c: ConnectionInfo, canCall: Boolean = true, votes: Boolean = false) = rule.setContent {
        ConnectionDetailScreen(
            ConnectionDetailUiState(c.id, c, null, loading = false, canCall = canCall, votes = ConnectionVotes(available = votes)),
            DetailActions(),
        )
    }

    @Test
    fun aServiceDetailHasNoMessageCallShareAskOrAuthenticateRows() {
        detail(service())
        rule.onNodeWithTag("detail_name").assertIsDisplayed()
        rule.onNodeWithTag("service_label").assertExists()
        rule.onNodeWithText("What this service can ask").assertExists()
        rule.onNodeWithText("It can ask you to confirm a sign-in or approve an action. It can't message or call you, and it can't ask for your data.").assertExists()
        assertTrue(rule.onAllNodesWithText("Name given by the service").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("detail_fingerprint").assertExists()
        rule.onNodeWithTag("auth_card").assertDoesNotExist()
        for (gone in listOf("Message", "Call", "shares with you", "Ask for something", "You share with", "Add a rule", "Authenticate")) {
            assertEquals(gone, 0, rule.onAllNodesWithText(gone, substring = true).fetchSemanticsNodes().size)
            assertEquals(gone, 0, rule.onAllNodesWithContentDescription(gone, substring = true).fetchSemanticsNodes().size)
        }
        assertTrue(rule.onAllNodesWithContentDescription("History", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithContentDescription("Remove", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theCanAskLineMentionsVotesWhereTheVaultHasThem() {
        detail(service(), votes = true)
        rule.onNodeWithText("It can ask you to confirm a sign-in, approve an action or vote. It can't message or call you, and it can't ask for your data.").assertExists()
    }

    @Test
    fun aPersonKeepsItsRows() {
        detail(person())
        rule.onNodeWithTag("auth_card").assertExists()
        assertEquals(0, rule.onAllNodesWithText("What this service can ask").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Service").fetchSemanticsNodes().size)
        assertTrue(rule.onAllNodesWithContentDescription("Message", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // --- forbidden ---

    @Test
    fun forbiddenShowsServicesCantDoThatOnceAndRemovesTheAction() = runTest {
        social.seed(listOf(person("c1")))
        social.onRefresh = { social.seed(listOf(person("c1").copy(service = true))) }
        social.fail["requestAuthentication"] = VaultFailure(FailureKind.OTHER, "forbidden")
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing())
        advanceUntilIdle()
        vm.requestAuthentication()
        advanceUntilIdle()
        assertEquals(FailureKind.SERVICE_FORBIDDEN, vm.uiState.value.error)
        assertTrue(vm.uiState.value.authRefused)
        val shown = vm.uiState.value
        rule.setContent { ConnectionDetailScreen(shown, DetailActions()) }
        rule.onNodeWithText("Services can't do that.").assertIsDisplayed()
        // Alone: no generic title above it.
        assertEquals(0, rule.onAllNodesWithText("Could not load", substring = true).fetchSemanticsNodes().size)
        assertTrue("refresh" in social.calls.drop(1))
        rule.onNodeWithTag("auth_card").assertDoesNotExist()
        // Dismissed, it does not come back, and the action stays removed.
        vm.dismissNotice()
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.authRefused)
        assertEquals(1, social.calls.count { it == "requestAuthentication" })
    }

    @Test
    fun aServiceWithoutFirstAndLastNameIsTitledByItsNameAPersonIsNot() {
        val noNames = ConnectionInfo("s9", "Acme", ConnectionState.ACTIVE, service = true)
        assertEquals("Acme", noNames.displayName)
        assertEquals("", noNames.copy(service = false).displayName)
        detail(noNames)
        rule.onNodeWithTag("detail_name").assertTextEquals("Acme")
    }

    /** `forbidden` also comes from a recovering app (VAULT-MESSAGING 0.51.2): a person is not told "Services can't do that.". */
    @Test
    fun forbiddenToAPersonIsTheGenericFailureAndTheCardStays() = runTest {
        social.seed(listOf(person("c1")))
        social.fail["requestAuthentication"] = VaultFailure(FailureKind.OTHER, "forbidden")
        val vm = ConversationDetailVm()
        advanceUntilIdle()
        vm.requestAuthentication()
        advanceUntilIdle()
        assertEquals(FailureKind.OTHER, vm.uiState.value.error)
        assertFalse(vm.uiState.value.authRefused)
        val shown = vm.uiState.value
        rule.setContent { ConnectionDetailScreen(shown, DetailActions()) }
        assertEquals(0, rule.onAllNodesWithText("Services can't do that.").fetchSemanticsNodes().size)
        rule.onNodeWithTag("auth_card").assertExists()
    }

    private fun ConversationDetailVm() =
        ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing())

    @Test
    fun theNameLabelStandsOnceAndNoPeopleHeaderSitsAboveServicesAlone() {
        detail(service().copy(name = "Acme Trading"))
        assertEquals(1, rule.onAllNodesWithText("Name given by the service").fetchSemanticsNodes().size)
    }

    @Test
    fun withOnlyServicesAndAnInviteThereIsNoConnectionsHeader() {
        rule.setContent {
            ConnectionsScreen(
                ConnectionsUiState(
                    loading = false, services = listOf(service()),
                    invites = listOf(com.vettid.core.data.social.OutstandingInvite("i1", null, remote = true)),
                ),
                chrome, ConnectionsActions(),
            )
        }
        rule.onNodeWithText("Services").assertIsDisplayed()
        // Only the top bar's title says "Connections".
        assertEquals(1, rule.onAllNodesWithText("Connections").fetchSemanticsNodes().size)
    }
}
