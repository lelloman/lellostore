package com.lelloman.store.ui.screen.login

import android.content.Intent
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.config.ConfigStore
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class LoginInteractor @Inject constructor(
    private val configStore: ConfigStore,
    private val authStore: AuthStore,
    private val authIntentProvider: AuthIntentProvider,
) : LoginViewModel.Interactor {

    override val authState: StateFlow<AuthState>
        get() = authStore.authState

    override val serverUrl: StateFlow<String>
        get() = configStore.serverUrl

    override fun getInitialServerUrl(): String {
        return configStore.serverUrl.value
    }

    override suspend fun setServerUrl(url: String): ConfigStore.SetServerUrlResult {
        return configStore.setServerUrl(url)
    }

    override fun createAuthIntent(): Intent {
        return authIntentProvider.createAuthIntent()
    }
}

interface AuthIntentProvider {
    fun createAuthIntent(): Intent
}
