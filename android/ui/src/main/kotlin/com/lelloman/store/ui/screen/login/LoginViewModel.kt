package com.lelloman.store.ui.screen.login

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lelloman.store.ui.model.AuthResult
import com.lelloman.store.ui.model.AuthState
import com.lelloman.store.ui.model.SetServerUrlResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel() {

    private val mutableState = MutableStateFlow(LoginScreenState())
    val state: StateFlow<LoginScreenState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<LoginScreenEvent>()
    val events: SharedFlow<LoginScreenEvent> = mutableEvents.asSharedFlow()

    private var authFlowInProgress = false
    private var userHasEditedServerUrl = false

    init {
        mutableState.value = mutableState.value.copy(
            serverUrl = interactor.getInitialServerUrl()
        )
        observeAuthState()
        observeServerUrl()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            interactor.authState
                .map { state -> state is AuthState.Authenticated }
                .distinctUntilChanged()
                .collect { isAuthenticated ->
                    if (isAuthenticated && authFlowInProgress) {
                        authFlowInProgress = false
                        mutableState.value = mutableState.value.copy(isLoading = false)
                        mutableEvents.emit(LoginScreenEvent.NavigateToMain)
                    }
                }
        }
    }

    private fun observeServerUrl() {
        viewModelScope.launch {
            interactor.serverUrl.collect { url ->
                // Only update if user hasn't manually edited the URL
                if (!userHasEditedServerUrl) {
                    mutableState.value = mutableState.value.copy(serverUrl = url)
                }
            }
        }
    }

    fun onServerUrlChanged(url: String) {
        userHasEditedServerUrl = true
        mutableState.value = mutableState.value.copy(
            serverUrl = url,
            serverUrlError = null,
            error = null,
        )
    }

    fun onLoginClick() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)

            when (interactor.setServerUrl(mutableState.value.serverUrl)) {
                SetServerUrlResult.InvalidUrl -> {
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        serverUrlError = "Invalid URL",
                    )
                    return@launch
                }
                SetServerUrlResult.Success -> {}
            }

            authFlowInProgress = true
            val authIntent = interactor.createAuthIntent()
            mutableEvents.emit(LoginScreenEvent.LaunchAuth(authIntent))
        }
    }

    fun onAuthResult(result: AuthResult) {
        viewModelScope.launch {
            when (result) {
                is AuthResult.Success -> {
                    // Success is handled by observeAuthState()
                }
                is AuthResult.Cancelled -> {
                    authFlowInProgress = false
                    mutableState.value = mutableState.value.copy(isLoading = false)
                }
                is AuthResult.Error -> {
                    authFlowInProgress = false
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
            }
        }
    }

    interface Interactor {
        val authState: StateFlow<AuthState>
        val serverUrl: StateFlow<String>
        fun getInitialServerUrl(): String
        suspend fun setServerUrl(url: String): SetServerUrlResult
        fun createAuthIntent(): Intent
    }
}
