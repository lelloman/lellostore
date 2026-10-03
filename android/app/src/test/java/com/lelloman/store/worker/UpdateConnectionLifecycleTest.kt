package com.lelloman.store.worker

import androidx.lifecycle.LifecycleOwner
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.preferences.UserPreferencesStore
import io.mockk.coEvery
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateConnectionLifecycleTest {
    @Test
    fun `service preserves connection across background and disabling restores polling`() = runTest {
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("dev@example.test"))
        val keep = MutableStateFlow(false)
        val running = MutableStateFlow(false)
        val url = MutableStateFlow("https://store.example")
        val authStore = mockk<AuthStore> { every { authState } returns auth }
        val config = mockk<ConfigStore> { every { serverUrl } returns url }
        val preferences = mockk<UserPreferencesStore> { every { keepUpdateConnection } returns keep; coEvery { readKeepUpdateConnection() } answers { keep.value } }
        val service = mockk<UpdateConnectionServiceController>(relaxed = true) {
            every { this@mockk.running } returns running
        }
        val connection = mockk<ForegroundCatalogEventConnection>(relaxed = true)
        val broker = mockk<com.lelloman.store.notification.NotificationBrokerRuntime>(relaxed = true)
        val warm = mockk<WarmUpdateScheduler>(relaxed = true)
        val observer = ForegroundUpdateLifecycleObserver(authStore, config, preferences, service,
            mockk(relaxed = true), warm, connection, broker, mockk(relaxed = true), backgroundScope)
        val owner = mockk<LifecycleOwner>()
        observer.observeConnection()
        observer.onStart(owner)
        runCurrent()
        verify { connection.start(url.value) }
        verify(exactly = 0) { service.start() }

        keep.value = true
        runCurrent()
        verify { service.start() }
        running.value = true
        runCurrent()
        clearMocks(connection, answers = false)
        observer.onStop(owner)
        runCurrent()
        verify { connection.stop(); broker.start(url.value) }

        clearMocks(service, answers = false)
        observer.onStart(owner)
        runCurrent()
        verify(exactly = 1) { service.restoreNotification() }
        observer.onStop(owner)
        runCurrent()

        url.value = "https://other.example"
        runCurrent()
        verify { broker.start("https://other.example") }
        keep.value = false
        runCurrent()
        verify { service.stop(); connection.stop(); warm.start() }

        running.value = false
        keep.value = true
        clearMocks(service, answers = false)
        runCurrent()
        // A persisted opt-in must not start a foreground service from the background.
        verify(exactly = 0) { service.start() }
        observer.onStart(owner)
        runCurrent()
        verify { service.start() }
        running.value = true
        runCurrent()
        clearMocks(connection, service, answers = false)
        auth.value = AuthState.NotAuthenticated
        runCurrent()
        verify { service.stop(); connection.stop() }
    }

    @Test
    fun `sticky service waits for restored authentication before making lifecycle decisions`() = runTest {
        val auth = MutableStateFlow<AuthState>(AuthState.Loading)
        val keep = MutableStateFlow(true)
        val running = MutableStateFlow(true)
        val authStore = mockk<AuthStore> { every { authState } returns auth }
        val config = mockk<ConfigStore> { every { serverUrl } returns MutableStateFlow("https://store.example") }
        val preferences = mockk<UserPreferencesStore> {
            every { keepUpdateConnection } returns keep
            coEvery { readKeepUpdateConnection() } returns true
        }
        val service = mockk<UpdateConnectionServiceController>(relaxed = true) { every { this@mockk.running } returns running }
        val broker = mockk<com.lelloman.store.notification.NotificationBrokerRuntime>(relaxed = true)
        val observer = ForegroundUpdateLifecycleObserver(authStore, config, preferences, service,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), broker, mockk(relaxed = true), backgroundScope)
        observer.observeConnection()
        runCurrent()
        verify(exactly = 0) { service.stop() }
        auth.value = AuthState.Authenticated("restored@example.test")
        runCurrent()
        verify { broker.start("https://store.example") }
        verify(exactly = 0) { service.stop() }
    }
}
