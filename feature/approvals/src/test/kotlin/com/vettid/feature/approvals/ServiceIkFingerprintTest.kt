package com.vettid.feature.approvals

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.IkFingerprint
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalParser
import com.vettid.core.testing.FakeItems
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
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

/** VAULT-MESSAGING 0.83.7 §10.4: a service's `ik` reaches the outgoing decision as "Vault key fingerprint", event or list. */
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
    private val social = FakeSocial()

    private fun body(kind: String?, ikValue: String?) = VaultJson.json.parseToJsonElement(
        """{"connection_id":"c1","sas":"315904","state":"pending","name":"Acme"""" +
            (kind?.let { ""","peer_kind":"$it"""" } ?: "") +
            (ikValue?.let { ""","ik":"$it"""" } ?: "") + "}",
    ).jsonObject

    private fun fromEvent(kind: String?, ikValue: String?) =
        ApprovalParser.parse("connection.request.outgoing", body(kind, ikValue), now) as Approval.OutgoingRequest

    private fun fromList(kind: String?, ikValue: String?) = ApprovalParser.outgoing(body(kind, ikValue), now)!!

    private fun TestScope.shown(a: Approval.OutgoingRequest): String? {
        social.approvals.value = listOf(a)
        val vm = ApprovalDetailViewModel(
            SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to a.key)), social, FakeItems(),
            com.vettid.core.testing.FakeDevices(), FakeVault(), social, com.vettid.core.testing.FakePairing(),
        )
        advanceUntilIdle()
        val state = vm.uiState.value
        rule.setContent { ApprovalDetailScreen(state, DecisionActions()) }
        rule.waitForIdle()
        return state.peerFingerprint
    }

    private val b64 get() = Base64s.encodeStd(ik)

    private fun TestScope.assertShowsFingerprint(a: Approval.OutgoingRequest) {
        assertEquals(fp, shown(a))
        rule.onNodeWithText("Vault key fingerprint").assertExists()
        rule.onNodeWithText(fp).assertExists()
    }

    @Test
    fun aServiceRequestWithAnIkShowsTheFingerprintFromTheEvent() = runTest { assertShowsFingerprint(fromEvent("service", b64)) }

    @Test
    fun aServiceRequestWithAnIkShowsTheFingerprintFromTheList() = runTest { assertShowsFingerprint(fromList("service", b64)) }

    @Test
    fun aServiceRequestWithoutAnIkShowsNoFingerprintLine() = runTest {
        assertNull(shown(fromEvent("service", null)))
        rule.onNodeWithTag("service_request").assertExists()
        rule.onNodeWithTag("service_request_fingerprint").assertDoesNotExist()
        rule.onNodeWithText("Vault key fingerprint").assertDoesNotExist()
    }

    @Test
    fun anInvalidIkShowsNoLineAndNoError() = runTest {
        assertNull(shown(fromList("service", "not base64!")))
        rule.onNodeWithTag("service_request_fingerprint").assertDoesNotExist()
        rule.onNodeWithText("Vault key fingerprint").assertDoesNotExist()
    }

    @Test
    fun aWaitingServiceRequestWithAnIkShowsNoLine() = runTest {
        val waiting = ApprovalParser.outgoing(
            VaultJson.json.parseToJsonElement("""{"connection_id":"c1","state":"waiting","peer_kind":"service","ik":"$b64"}""").jsonObject,
            now,
        )!!
        assertNull(shown(waiting))
        rule.onNodeWithText("Vault key fingerprint").assertDoesNotExist()
    }

    @Test
    fun aPersonsRequestWithAnIkShowsNothingNew() = runTest {
        // The `ik` is parsed whatever the kind, and not shown for a person.
        assertEquals(fp, fromEvent(null, b64).peerFingerprint)
        assertNull(shown(fromEvent(null, b64)))
        rule.onNodeWithTag("service_request").assertDoesNotExist()
        rule.onNodeWithText("Vault key fingerprint").assertDoesNotExist()
    }
}
