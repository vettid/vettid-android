package com.vettid.feature.connections

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.IkFingerprint
import com.vettid.core.data.social.AcceptedConnection
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalParser
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.VaultJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** VAULT-MESSAGING 0.83.7 §10.4: a service's `ik` reaches "Compare the code" as "Vault key fingerprint", from the event or the list. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServiceIkFingerprintTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val ik = ByteArray(32) { (it + 1).toByte() }
    private val fp = IkFingerprint.format(ik)
    private val b64 get() = Base64s.encodeStd(ik)

    private fun body(kind: String?, ikValue: String?) = VaultJson.json.parseToJsonElement(
        """{"connection_id":"c6","sas":"315904","state":"pending","name":"Acme"""" +
            (kind?.let { ""","peer_kind":"$it"""" } ?: "") +
            (ikValue?.let { ""","ik":"$it"""" } ?: "") + "}",
    ).jsonObject

    private fun fromEvent(kind: String?, ikValue: String?) =
        ApprovalParser.parse("connection.request.outgoing", body(kind, ikValue), now) as Approval.OutgoingRequest

    private fun fromList(kind: String?, ikValue: String?) = ApprovalParser.outgoing(body(kind, ikValue), now)!!

    /** The accept flow with [a] arriving as the outgoing request; the answer to the accept itself has no `peer_kind` and no `ik`. */
    private fun TestScope.compare(a: Approval.OutgoingRequest) {
        val social = FakeSocial()
        social.acceptResult = AcceptedConnection("c6", "Acme")
        val vm = AcceptViewModel(SavedStateHandle(), social, social)
        vm.setInput("test-link")
        vm.accept()
        advanceUntilIdle()
        social.approvals.value = listOf(a)
        advanceUntilIdle()
        assertEquals(AcceptStep.COMPARE, vm.uiState.value.step)
        val state = vm.uiState.value
        rule.setContent { AcceptScreen(state, AcceptActions()) }
        rule.waitForIdle()
        shownFingerprint = state.keyFingerprint
    }

    private var shownFingerprint: String? = null

    private fun assertShown() {
        assertEquals(fp, shownFingerprint)
        rule.onNodeWithText("Vault key fingerprint").assertExists()
        rule.onNodeWithText(fp).assertExists()
    }

    private fun assertNoLine() {
        assertNull(shownFingerprint)
        rule.onNodeWithTag("service_request_fingerprint").assertDoesNotExist()
        rule.onNodeWithText("Vault key fingerprint").assertDoesNotExist()
    }

    @Test
    fun aServiceRequestWithAnIkShowsTheFingerprintFromTheEvent() = runTest {
        compare(fromEvent("service", b64))
        assertShown()
    }

    @Test
    fun aServiceRequestWithAnIkShowsTheFingerprintFromTheList() = runTest {
        compare(fromList("service", b64))
        assertShown()
    }

    @Test
    fun aServiceRequestWithoutAnIkShowsNoLine() = runTest {
        compare(fromEvent("service", null))
        rule.onNodeWithTag("service_request").assertExists()
        assertNoLine()
    }

    @Test
    fun anInvalidIkShowsNoLine() = runTest {
        compare(fromList("service", "not base64!"))
        rule.onNodeWithTag("service_request").assertExists()
        assertNoLine()
    }

    @Test
    fun aPersonsRequestWithAnIkShowsNothingNew() = runTest {
        compare(fromEvent(null, b64))
        rule.onNodeWithTag("service_request").assertDoesNotExist()
        assertNoLine()
    }
}
