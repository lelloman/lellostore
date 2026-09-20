package com.lelloman.store.ui.screen.pesce

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.domain.remote.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PesceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessionState = MutableStateFlow(RemoteDeviceState())
    private val session = mockk<RemoteDeviceSession>(relaxed = true) { every { state } returns sessionState }
    private val operations = mockk<RemoteDeviceOperations>(relaxed = true)
    private val authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
    private val auth = mockk<AuthStore> { every { authState } returns this@PesceViewModelTest.authState }
    private val version = AppVersion(7, "1.7", 3, "hash", 24, Instant.fromEpochMilliseconds(0))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `copy and connection actions work without store login`() {
        val vm = PesceViewModel(session, operations, auth)
        vm.act(PesceAction.Connect("usb-device"))
        vm.act(PesceAction.Copy)
        verify { session.refreshDevices(); session.connect("usb-device"); operations.copyStore() }
    }

    @Test fun `batch contains only selected catalog versions`() = runTest {
        val first = RemoteAppChoice("com.first.app", "First", version)
        val second = RemoteAppChoice("com.second.app", "Second", version.copy(versionCode = 8))
        coEvery { operations.loadCatalog() } returns Result.success(listOf(first, second))
        val vm = PesceViewModel(session, operations, auth)
        vm.act(PesceAction.PickApps)
        advanceUntilIdle()
        vm.act(PesceAction.Select(second.packageName))
        vm.act(PesceAction.Install)
        verify { operations.installApps(listOf(second)) }
        assertThat(vm.picker.value.open).isFalse()
    }

    @Test fun `catalog login failure does not disconnect USB session and can be retried`() = runTest {
        coEvery { operations.loadCatalog() } returns Result.failure(IllegalStateException("Sign in"))
        val vm = PesceViewModel(session, operations, auth)
        vm.act(PesceAction.PickApps)
        advanceUntilIdle()
        assertThat(vm.picker.value.error).isEqualTo("Sign in")
        coEvery { operations.loadCatalog() } returns Result.success(listOf(RemoteAppChoice("com.example.app", "App", version)))
        authState.value = AuthState.Authenticated("person@example.test")
        vm.refreshCatalog()
        advanceUntilIdle()
        assertThat(vm.picker.value.apps).hasSize(1)
        assertThat(vm.picker.value.error).isNull()
        verify(exactly = 0) { session.disconnect() }
    }
}
