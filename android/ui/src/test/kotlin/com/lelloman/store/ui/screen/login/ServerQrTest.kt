package com.lelloman.store.ui.screen.login

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.ui.model.AuthState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ServerQrTest {
    private lateinit var viewModel: LoginViewModel

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        val interactor = mockk<LoginViewModel.Interactor> {
            every { getInitialServerUrl() } returns ""
            every { serverUrl } returns MutableStateFlow("")
            every { authState } returns MutableStateFlow(AuthState.NotAuthenticated)
        }
        viewModel = LoginViewModel(interactor)
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun `website QR selects a canonical address but still requires discovery and confirmation`() {
        viewModel.onQrScanned("""{"type":"store-setup","version":1,"server_url":"https://Store.Example:443/"}""")
        assertThat(viewModel.state.value.serverUrl).isEqualTo("https://store.example")
        assertThat(viewModel.state.value.serverName).isNull()
        assertThat(viewModel.state.value.isLoading).isFalse()
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test fun `invalid QR cannot replace a working selection`() {
        viewModel.onServerUrlChanged("https://original.example")
        listOf(
            "not json",
            """{"type":"store-setup","version":2,"server_url":"https://new.example"}""",
            """{"type":"store-setup","version":1,"server_url":"http://new.example"}""",
            """{"type":"store-setup","version":1,"server_url":"https://user:secret@new.example"}""",
        ).forEach { payload ->
            viewModel.onQrScanned(payload)
            assertThat(viewModel.state.value.serverUrl).isEqualTo("https://original.example")
            assertThat(viewModel.state.value.error).isNotNull()
        }
    }
}
