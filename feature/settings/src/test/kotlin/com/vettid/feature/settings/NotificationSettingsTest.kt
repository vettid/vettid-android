// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.settings

import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.NotificationPreviews
import com.vettid.core.notify.KeeperStatus
import com.vettid.core.notify.NoPushProvider
import com.vettid.core.notify.ServiceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
