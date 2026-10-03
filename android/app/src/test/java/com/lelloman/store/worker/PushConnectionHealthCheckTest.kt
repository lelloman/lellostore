package com.lelloman.store.worker

import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.notification.NotificationBrokerRuntime
import com.lelloman.store.notification.NotificationHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushConnectionHealthCheckTest {
    private val preferences = mockk<UserPreferencesStore> {
        coEvery { readKeepUpdateConnection() } returns true
    }
    private val authState = MutableStateFlow<AuthState>(AuthState.Authenticated("test@example.test"))
    private val auth = mockk<AuthStore> { every { this@mockk.authState } returns this@PushConnectionHealthCheckTest.authState }
    private val broker = mockk<NotificationBrokerRuntime> { every { hasBatteryExemption() } returns true }
    private val running = MutableStateFlow(true)
    private val service = mockk<UpdateConnectionServiceController>(relaxed = true) {
        every { this@mockk.running } returns this@PushConnectionHealthCheckTest.running
    }
    private val notifications = mockk<NotificationHelper>(relaxed = true)
    private val check = PushConnectionHealthCheck(preferences, auth, broker, service, notifications)

    @Test fun `disabled push respects user choice`() = runTest {
        coEvery { preferences.readKeepUpdateConnection() } returns false
        assertTrue(check.check())
        verify { notifications.cancelPushConnectionReminder() }
        verify(exactly = 0) { service.start(); notifications.showPushConnectionReminder(any()) }
    }

    @Test fun `signed out does not start or remind`() = runTest {
        authState.value = AuthState.NotAuthenticated
        assertTrue(check.check())
        verify(exactly = 0) { service.start(); notifications.showPushConnectionReminder(any()) }
    }

    @Test fun `missing exemption reminds even when service is running`() = runTest {
        every { broker.hasBatteryExemption() } returns false
        assertTrue(check.check())
        verify { notifications.showPushConnectionReminder(true) }
        verify(exactly = 0) { service.start() }
    }

    @Test fun `healthy service clears reminder without restarting`() = runTest {
        assertTrue(check.check())
        verify { notifications.cancelPushConnectionReminder() }
        verify(exactly = 0) { service.start() }
    }

    @Test fun `exempt stopped service is restarted`() = runTest {
        running.value = false
        every { service.start() } answers { running.value = true }
        assertTrue(check.check())
        verify(exactly = 1) { service.start() }
        verify { notifications.cancelPushConnectionReminder() }
    }

    @Test fun `rejected start reminds and retries`() = runTest {
        running.value = false
        every { service.start() } throws IllegalStateException("Start rejected")
        assertFalse(check.check())
        verify { notifications.showPushConnectionReminder(false) }
    }

    @Test fun `startup timeout reminds and retries`() = runTest {
        running.value = false
        assertFalse(check.check())
        verify { notifications.showPushConnectionReminder(false) }
    }

    @Test fun `loading authentication retries without reminder`() = runTest {
        authState.value = AuthState.Loading
        assertFalse(check.check())
        verify(exactly = 0) { service.start(); notifications.showPushConnectionReminder(any()) }
    }
}
