package com.lelloman.store.interactor

import android.content.Intent
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.ui.model.AuthState
import com.lelloman.store.ui.model.SetServerUrlResult
import com.lelloman.store.ui.screen.login.AuthIntentProvider
import com.lelloman.store.ui.screen.login.LoginViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Inject
import com.lelloman.store.domain.auth.AuthState as DomainAuthState

class LoginInteractorImpl @Inject constructor(
    private val configStore: ConfigStore,
    private val domainAuthStore: AuthStore,
    private val authIntentProvider: AuthIntentProvider,
) : LoginViewModel.Interactor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val authState: StateFlow<AuthState> = domainAuthStore.authState
        .map { domainState ->
            when (domainState) {
                is DomainAuthState.Loading -> AuthState.Loading
                is DomainAuthState.NotAuthenticated -> AuthState.NotAuthenticated
                is DomainAuthState.Authenticated -> AuthState.Authenticated(domainState.userEmail)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

    override val serverUrl: StateFlow<String>
        get() = configStore.serverUrl

    override fun getInitialServerUrl(): String {
        return configStore.serverUrl.value
    }

    override suspend fun setServerUrl(url: String): SetServerUrlResult {
        return when (configStore.setServerUrl(url)) {
            ConfigStore.SetServerUrlResult.Success -> SetServerUrlResult.Success
            ConfigStore.SetServerUrlResult.InvalidUrl -> SetServerUrlResult.InvalidUrl
        }
    }

    override fun createAuthIntent(): Intent {
        return authIntentProvider.createAuthIntent()
    }
}
