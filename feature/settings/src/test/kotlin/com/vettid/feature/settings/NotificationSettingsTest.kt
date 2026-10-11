// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.settings

import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.NotificationPreviews
import com.vettid.core.notify.KeeperStatus
import com.vettid.core.notify.NoPushProvider
import com.vettid.core.notify.PushAvailability
import com.vettid.core.notify.PushControl
import com.vettid.core.notify.PushProvider
import com.vettid.core.notify.PushRegState
import com.vettid.core.notify.PushStatus
import com.vettid.core.notify.ServiceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Settings → Notifications (ANDROID-PLAN 0.1.23, Notification modes 1 and 11): the mode settings and the status line. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun theServiceIsTheDefaultAndPushCannotBeChosenYet() = runTest {
        val prefs = InMemoryPreferencesRepository()
        val vm = NotificationSettingsViewModel(prefs, ServiceStatus(), NoPushProvider)
        advanceUntilIdle()
        assertEquals(NotificationMode.SERVICE, vm.uiState.value.mode)
        assertNull("never chosen: the default, for existing installs too", prefs.current.value.notificationMode)
        vm.setMode(NotificationMode.PUSH)
        advanceUntilIdle()
        assertNull(prefs.current.value.notificationMode)
        vm.setMode(NotificationMode.OFF)
        vm.setPreviews(NotificationPreviews.NOTHING)
        advanceUntilIdle()
        assertEquals(AppPreferences(notificationMode = NotificationMode.OFF, notificationPreviews = NotificationPreviews.NOTHING), prefs.current.value)
        assertEquals(NotificationMode.OFF, vm.uiState.value.mode)
        assertNull("Off has no status line", vm.uiState.value.status)
    }

    private class Push(var available: PushAvailability) : PushProvider {
        override fun availability() = available

        override suspend fun token(): String? = null

        override var onWake: (suspend () -> Boolean)? = null
        override var onNewToken: (() -> Unit)? = null
    }

    private class Registration : PushControl {
        override val status = MutableStateFlow(PushStatus())
        var refreshed = 0

        override fun enable() = Unit

        override fun disable(wiped: Boolean) = Unit

        override suspend fun refresh() {
            refreshed++
        }
    }

    @Test
    fun googlePushCanBeChosenOnlyWhereItIsAvailable() = runTest {
        for (no in listOf(PushAvailability.NOT_AVAILABLE_YET, PushAvailability.MISSING_SERVICES, PushAvailability.SERVICES_OUTDATED)) {
            val prefs = InMemoryPreferencesRepository()
            val vm = NotificationSettingsViewModel(prefs, ServiceStatus(), Push(no), Registration())
            vm.setMode(NotificationMode.PUSH)
            advanceUntilIdle()
            assertNull("$no", prefs.current.value.notificationMode)
            assertEquals(no, vm.uiState.value.pushNote)
        }
        val prefs = InMemoryPreferencesRepository()
        val reg = Registration()
        val vm = NotificationSettingsViewModel(prefs, ServiceStatus(), Push(PushAvailability.AVAILABLE), reg)
        vm.setMode(NotificationMode.PUSH)
        advanceUntilIdle()
        assertEquals(NotificationMode.PUSH, prefs.current.value.notificationMode)
        assertEquals(NotificationMode.PUSH, vm.uiState.value.mode)
        // The status line follows the registration, read again from device.list when the screen is in front.
        assertEquals(NotifyStatusLine.CONNECTING, vm.uiState.value.status)
        reg.status.value = PushStatus(PushRegState.ACTIVE)
        advanceUntilIdle()
        assertEquals(NotifyStatusLine.CONNECTED, vm.uiState.value.status)
        vm.refreshPush()
        advanceUntilIdle()
        assertEquals(1, reg.refreshed)
        // Back to the on-phone service and to Off: the mode is the member's choice, nothing else decides it.
        vm.setMode(NotificationMode.SERVICE)
        advanceUntilIdle()
        assertEquals(NotificationMode.SERVICE, prefs.current.value.notificationMode)
    }

    @Test
    fun theGooglePushStatusLine() {
        fun line(state: PushRegState, lastResult: String? = null, service: KeeperStatus? = null, phone: PhoneNotifyState = PhoneNotifyState()) =
            NotificationSettingsUiState(NotificationMode.PUSH, service = service, push = PushAvailability.AVAILABLE, phone = phone, registration = PushStatus(state, lastResult)).status
        // "Connected" while `active`, whatever the last wake's result, except a gateway error.
        assertEquals(NotifyStatusLine.CONNECTED, line(PushRegState.ACTIVE))
        assertEquals(NotifyStatusLine.CONNECTED, line(PushRegState.ACTIVE, "sent"))
        assertEquals(NotifyStatusLine.CONNECTED, line(PushRegState.ACTIVE, "rate_limited"))
        assertEquals(NotifyStatusLine.PUSH_UNREACHABLE, line(PushRegState.ACTIVE, "gateway_error"))
        assertEquals(NotifyStatusLine.PUSH_UNREACHABLE, line(PushRegState.RETRYING, service = KeeperStatus.CONNECTED))
        assertEquals(NotifyStatusLine.PUSH_REGISTERING_AGAIN, line(PushRegState.REGISTERING_AGAIN))
        // `limit` (and the other answers that leave no registration): the on-phone service is the fallback.
        assertEquals(NotifyStatusLine.PUSH_FALLBACK, line(PushRegState.LIMITED, service = KeeperStatus.CONNECTED))
        assertEquals(NotifyStatusLine.PUSH_FALLBACK, line(PushRegState.UNAVAILABLE, service = KeeperStatus.CONNECTED))
        assertEquals(NotifyStatusLine.PUSH_FALLBACK, line(PushRegState.REFUSED, service = KeeperStatus.CONNECTING))
        assertEquals(NotifyStatusLine.CONNECTING, line(PushRegState.REGISTERING))
        assertEquals(NotifyStatusLine.CONNECTING, line(PushRegState.WAITING))
        // What matters more comes first, as in the other modes; battery optimisation does not delay a push.
        assertEquals(NotifyStatusLine.BLOCKED, line(PushRegState.ACTIVE, phone = PhoneNotifyState(allowed = false)))
        assertEquals(NotifyStatusLine.CONNECTED, line(PushRegState.ACTIVE, phone = PhoneNotifyState(background = false)))
        assertEquals(NotifyStatusLine.LOCKED, line(PushRegState.LIMITED, service = KeeperStatus.LOCKED))
        assertEquals(NotifyStatusLine.WAITING, line(PushRegState.LIMITED, service = KeeperStatus.WAITING_FOR_NETWORK))
        // The vault's `unavailable` keeps "Not available yet" under the choice.
        val unavailable = NotificationSettingsUiState(NotificationMode.PUSH, push = PushAvailability.AVAILABLE, registration = PushStatus(PushRegState.UNAVAILABLE))
        assertEquals(PushAvailability.NOT_AVAILABLE_YET, unavailable.pushNote)
        assertEquals(PushAvailability.AVAILABLE, unavailable.copy(registration = PushStatus(PushRegState.ACTIVE)).pushNote)
        // The registration says nothing in the other modes.
        assertEquals(NotifyStatusLine.CONNECTED, NotificationSettingsUiState(service = KeeperStatus.CONNECTED, registration = PushStatus(PushRegState.LIMITED)).status)
        assertNull(NotificationSettingsUiState(NotificationMode.OFF, registration = PushStatus(PushRegState.ACTIVE)).status)
    }

    @Test
    fun theStatusLineSaysTheMostImportantFirst() {
        val s = NotificationSettingsUiState(service = KeeperStatus.CONNECTED)
        assertEquals(NotifyStatusLine.CONNECTED, s.status)
        assertEquals(NotifyStatusLine.BATTERY, s.copy(phone = PhoneNotifyState(background = false)).status)
        assertEquals(NotifyStatusLine.LOCKED, s.copy(service = KeeperStatus.LOCKED, phone = PhoneNotifyState(background = false)).status)
        assertEquals(NotifyStatusLine.BLOCKED, s.copy(service = KeeperStatus.LOCKED, phone = PhoneNotifyState(allowed = false)).status)
        assertEquals(NotifyStatusLine.WAITING, s.copy(service = KeeperStatus.WAITING_FOR_NETWORK).status)
        assertEquals(NotifyStatusLine.CONNECTING, s.copy(service = null).status)
    }
}
