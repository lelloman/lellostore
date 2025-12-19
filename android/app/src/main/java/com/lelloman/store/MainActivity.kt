package com.lelloman.store

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.localdata.auth.AuthStoreImpl
import com.lelloman.store.ui.AppUi
import com.lelloman.store.ui.model.AuthResult
import com.lelloman.store.ui.model.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.lelloman.store.domain.auth.AuthResult as DomainAuthResult
import com.lelloman.store.domain.auth.AuthState as DomainAuthState
import com.lelloman.store.domain.preferences.ThemeMode as DomainThemeMode

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferencesStore: UserPreferencesStore

    @Inject
    lateinit var authStore: AuthStore

    @Inject
    lateinit var authStoreImpl: AuthStoreImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val domainThemeMode by userPreferencesStore.themeMode.collectAsState()
            val domainAuthState by authStore.authState.collectAsState()

            val themeMode = domainThemeMode.toUiModel()
            val isLoggedIn = domainAuthState is DomainAuthState.Authenticated
            val userEmail = (domainAuthState as? DomainAuthState.Authenticated)?.userEmail ?: ""

            AppUi(
                themeMode = themeMode,
                isLoggedIn = isLoggedIn,
                userEmail = userEmail,
                onAuthResponse = { response, exception, onResult ->
                    authStoreImpl.handleAuthResponse(response, exception) { domainResult ->
                        onResult(domainResult.toUiModel())
                    }
                },
                onLogout = {
                    lifecycleScope.launch {
                        authStore.logout()
                    }
                },
            )
        }
    }

    private fun DomainThemeMode.toUiModel(): ThemeMode = when (this) {
        DomainThemeMode.System -> ThemeMode.System
        DomainThemeMode.Light -> ThemeMode.Light
        DomainThemeMode.Dark -> ThemeMode.Dark
    }

    private fun DomainAuthResult.toUiModel(): AuthResult = when (this) {
        is DomainAuthResult.Success -> AuthResult.Success
        is DomainAuthResult.Cancelled -> AuthResult.Cancelled
        is DomainAuthResult.Error -> AuthResult.Error(message)
    }
}
