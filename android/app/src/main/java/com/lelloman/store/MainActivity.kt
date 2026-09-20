package com.lelloman.store

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.lelloman.store.recovery.RecoveryCompanionClient
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.auth.SessionExpiredHandler
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.localdata.auth.AuthStoreImpl
import com.lelloman.store.ui.AppUi
import com.lelloman.store.ui.model.AuthResult
import com.lelloman.store.ui.model.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.lelloman.store.domain.auth.AuthResult as DomainAuthResult
import com.lelloman.store.domain.auth.AuthState as DomainAuthState
import com.lelloman.store.domain.preferences.ThemeMode as DomainThemeMode

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    @Inject
    lateinit var userPreferencesStore: UserPreferencesStore

    @Inject
    lateinit var authStore: AuthStore

    @Inject
    lateinit var authStoreImpl: AuthStoreImpl

    @Inject
    lateinit var sessionExpiredHandler: SessionExpiredHandler

    // Flow to signal UI that session expired and should navigate to login
    private val _sessionExpiredNavigation = MutableStateFlow(false)
    private val sessionExpiredNavigation = _sessionExpiredNavigation.asStateFlow()
    private val openPesce = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPesce.value = intent.getBooleanExtra("open_pesce", false)
        enableEdgeToEdge()
        observeSessionExpiredEvents()
        setContent {
            val domainThemeMode by userPreferencesStore.themeMode.collectAsState()
            val domainAuthState by authStore.authState.collectAsState()
            val shouldNavigateToLogin by sessionExpiredNavigation.collectAsState()
            val shouldOpenPesce by openPesce.collectAsState()

            val themeMode = domainThemeMode.toUiModel()
            val useDarkSystemBars = when (domainThemeMode) {
                DomainThemeMode.System -> isSystemInDarkTheme()
                DomainThemeMode.Light -> false
                DomainThemeMode.Dark -> true
            }
            val isLoggedIn = domainAuthState is DomainAuthState.Authenticated
            val userEmail = (domainAuthState as? DomainAuthState.Authenticated)?.userEmail ?: ""

            SideEffect {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (useDarkSystemBars) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                    navigationBarStyle = if (useDarkSystemBars) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                )
            }

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
                forceNavigateToLogin = shouldNavigateToLogin,
                onForceNavigateToLoginHandled = { _sessionExpiredNavigation.value = false },
                openPesce = shouldOpenPesce,
                onOpenPesceHandled = { openPesce.value = false; intent.removeExtra("open_pesce") },
            )
            LaunchedEffect(isLoggedIn) {
                if (isLoggedIn) requestNotificationPermissionIfNeeded()
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                val recovery = RecoveryCompanionClient(this@MainActivity)
                recovery.restoreIdentityIfNeeded()
                recovery.acknowledgePendingHealth()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPesce.value = intent.getBooleanExtra("open_pesce", false)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val preferences = getSharedPreferences(
            NOTIFICATION_PERMISSION_PREFERENCES,
            MODE_PRIVATE,
        )
        if (preferences.getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)) return

        preferences.edit {
            putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true)
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun observeSessionExpiredEvents() {
        lifecycleScope.launch {
            sessionExpiredHandler.sessionExpiredEvents.collect {
                // Logout and signal navigation
                authStore.logout()
                _sessionExpiredNavigation.value = true
            }
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

    private companion object {
        const val NOTIFICATION_PERMISSION_PREFERENCES = "notification-permission"
        const val NOTIFICATION_PERMISSION_REQUESTED = "requested"
    }
}
