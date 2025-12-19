package com.lelloman.store.ui.screen.login

import android.content.Intent
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.ui.model.AuthResult
import com.lelloman.store.ui.model.AuthState
import com.lelloman.store.ui.model.SetServerUrlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeInteractor: FakeLoginInteractor
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeInteractor = FakeLoginInteractor()
        viewModel = LoginViewModel(fakeInteractor)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has server URL from interactor`() {
        fakeInteractor._initialServerUrl = "https://initial.example.com"
        viewModel = LoginViewModel(fakeInteractor)

        assertThat(viewModel.state.value.serverUrl).isEqualTo("https://initial.example.com")
    }

    @Test
    fun `onServerUrlChanged updates state`() {
        viewModel.onServerUrlChanged("https://new.example.com")

        assertThat(viewModel.state.value.serverUrl).isEqualTo("https://new.example.com")
        assertThat(viewModel.state.value.serverUrlError).isNull()
    }

    @Test
    fun `onServerUrlChanged clears previous error`() {
        // Set an error first
        viewModel.onServerUrlChanged("invalid")
        fakeInteractor.setServerUrlResult = SetServerUrlResult.InvalidUrl

        runTest {
            viewModel.onLoginClick()
            advanceUntilIdle()
        }

        assertThat(viewModel.state.value.serverUrlError).isNotNull()

        // Now change URL, error should clear
        viewModel.onServerUrlChanged("https://valid.example.com")

        assertThat(viewModel.state.value.serverUrlError).isNull()
    }

    @Test
    fun `onLoginClick with invalid URL shows error`() = runTest {
        fakeInteractor.setServerUrlResult = SetServerUrlResult.InvalidUrl
        viewModel.onServerUrlChanged("invalid-url")

        viewModel.onLoginClick()
        advanceUntilIdle()

        assertThat(viewModel.state.value.serverUrlError).isEqualTo("Invalid URL")
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `onLoginClick with valid URL emits LaunchAuth event`() = runTest {
        fakeInteractor.setServerUrlResult = SetServerUrlResult.Success
        viewModel.onServerUrlChanged("https://valid.example.com")

        viewModel.events.test {
            viewModel.onLoginClick()
            advanceUntilIdle()

            val event = awaitItem()
            assertThat(event).isInstanceOf(LoginScreenEvent.LaunchAuth::class.java)
        }
    }

    @Test
    fun `onLoginClick sets loading state and keeps it until auth completes`() = runTest {
        fakeInteractor.setServerUrlResult = SetServerUrlResult.Success
        viewModel.onServerUrlChanged("https://valid.example.com")

        viewModel.onLoginClick()
        advanceUntilIdle()

        // Loading stays true until auth flow completes
        assertThat(viewModel.state.value.isLoading).isTrue()

        // Auth cancelled clears loading
        viewModel.onAuthResult(AuthResult.Cancelled)
        advanceUntilIdle()

        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `auth state change to Authenticated emits NavigateToMain when auth flow in progress`() = runTest {
        fakeInteractor.setServerUrlResult = SetServerUrlResult.Success
        viewModel.onServerUrlChanged("https://valid.example.com")

        viewModel.events.test {
            // Start auth flow
            viewModel.onLoginClick()
            advanceUntilIdle()

            // Consume the LaunchAuth event
            val launchEvent = awaitItem()
            assertThat(launchEvent).isInstanceOf(LoginScreenEvent.LaunchAuth::class.java)

            // Simulate successful authentication by changing auth state
            fakeInteractor.mutableAuthState.value = AuthState.Authenticated("test@example.com")
            advanceUntilIdle()

            // Should emit NavigateToMain
            val navigateEvent = awaitItem()
            assertThat(navigateEvent).isEqualTo(LoginScreenEvent.NavigateToMain)
        }
    }

    @Test
    fun `onAuthResult Cancelled does not emit event`() = runTest {
        viewModel.events.test {
            viewModel.onAuthResult(AuthResult.Cancelled)
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `onAuthResult Error updates state with error message`() = runTest {
        viewModel.onAuthResult(AuthResult.Error("Login failed"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Login failed")
        assertThat(viewModel.state.value.isLoading).isFalse()
    }
}

class FakeLoginInteractor : LoginViewModel.Interactor {
    var _initialServerUrl = "https://default.example.com"
    var setServerUrlResult: SetServerUrlResult = SetServerUrlResult.Success
    var authIntent: Intent = Intent()
    val mutableAuthState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
    val mutableServerUrl = MutableStateFlow("https://default.example.com")

    override val authState: StateFlow<AuthState> = mutableAuthState
    override val serverUrl: StateFlow<String> = mutableServerUrl

    override fun getInitialServerUrl() = _initialServerUrl

    override suspend fun setServerUrl(url: String) = setServerUrlResult

    override fun createAuthIntent() = authIntent
}
