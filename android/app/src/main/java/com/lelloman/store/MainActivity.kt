package com.lelloman.store

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lelloman.store.notification.NotificationHelper
import com.lelloman.store.ui.screen.settings.hasPushBatteryExemption
import com.lelloman.store.ui.screen.settings.openPushBatterySettings
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.lelloman.store.domain.auth.AuthResult as DomainAuthResult
import com.lelloman.store.domain.auth.AuthState as DomainAuthState
import com.lelloman.store.domain.preferences.ThemeMode as DomainThemeMode

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) notifications.showStartupBatteryWarning()
    }

    @Inject lateinit var notifications: NotificationHelper

    @Inject lateinit var selfUpdateTracker: com.lelloman.store.installation.SelfUpdateTracker

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
    private val openUpdates = MutableStateFlow(false)
    private var openedBatterySettings = false
    private val offerPhoneConnection = androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPesce.value = intent.getBooleanExtra("open_pesce", false)
        openUpdates.value = intent.getBooleanExtra(NotificationHelper.EXTRA_NAVIGATE_TO_UPDATES, false)
        enableEdgeToEdge()
        observeSessionExpiredEvents()
        handleBatterySettingsIntent()
        setContent {
            val domainThemeMode by userPreferencesStore.themeMode.collectAsState()
            val domainAuthState by authStore.authState.collectAsState()
            val shouldNavigateToLogin by sessionExpiredNavigation.collectAsState()
            val shouldOpenPesce by openPesce.collectAsState()
            val shouldOpenUpdates by openUpdates.collectAsState()

            val themeMode = domainThemeMode.toUiModel()
            val useDarkSystemBars = when (domainThemeMode) {
                DomainThemeMode.System -> isSystemInDarkTheme()
                DomainThemeMode.Light -> false
                DomainThemeMode.Dark -> true
            }
            val isLoggedIn = domainAuthState is DomainAuthState.Authenticated
            val userEmail = (domainAuthState as? DomainAuthState.Authenticated)?.userEmail ?: ""
            val isDeviceToken = (domainAuthState as? DomainAuthState.Authenticated)?.deviceToken == true

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

            if (offerPhoneConnection.value) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { offerPhoneConnection.value = false },
                    title = { androidx.compose.material3.Text(getString(com.lelloman.store.ui.R.string.broker_offer_title)) },
                    text = { androidx.compose.material3.Text(getString(com.lelloman.store.ui.R.string.broker_offer_body)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            offerPhoneConnection.value = false
                            startActivity(Intent(this@MainActivity, com.lelloman.store.auth.BrokerAccountsActivity::class.java))
                        }) { androidx.compose.material3.Text(getString(com.lelloman.store.ui.R.string.broker_connect)) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { offerPhoneConnection.value = false }) {
                            androidx.compose.material3.Text(getString(com.lelloman.store.ui.R.string.broker_later))
                        }
                    },
                )
            }
            val batterySnackbar = remember { SnackbarHostState() }
            Box(Modifier.fillMaxSize()) {
                AppUi(
                    themeMode = themeMode,
                    isLoggedIn = isLoggedIn,
                    isAuthLoading = domainAuthState is DomainAuthState.Loading,
                    userEmail = userEmail,
                    isDeviceToken = isDeviceToken,
                    onAuthResponse = { response, exception, onResult ->
                        authStoreImpl.handleAuthResponse(response, exception) { domainResult ->
                            if (domainResult is DomainAuthResult.Success) {
                                val issuer = response?.request?.configuration?.discoveryDoc?.issuer?.toString()
                                if (issuer != null && com.lelloman.store.auth.EmbeddedSignInBroker.acceptsIssuer(issuer)) {
                                    lifecycleScope.launch {
                                        val connected = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            com.lelloman.store.auth.EmbeddedSignInBroker.hasAccount(this@MainActivity, issuer)
                                        }
                                        offerPhoneConnection.value = !connected
                                    }
                                }
                            }
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
                    openUpdates = shouldOpenUpdates,
                    onOpenUpdatesHandled = {
                        openUpdates.value = false
                        intent.removeExtra(NotificationHelper.EXTRA_NAVIGATE_TO_UPDATES)
                    },
                )
                SnackbarHost(batterySnackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
            LaunchedEffect(Unit) {
                if (savedInstanceState == null && !openedBatterySettings && !hasPushBatteryExemption() && !notifications.showStartupBatteryWarning()) {
                    val result = batterySnackbar.showSnackbar(
                        getString(com.lelloman.store.ui.R.string.push_battery_warning_text),
                        getString(com.lelloman.store.ui.R.string.push_battery_open_settings),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) openPushBatterySettings()
                }
            }
            LaunchedEffect(Unit) {
                // One-shot: the old process was killed by the update before it could say so.
                selfUpdateTracker.announcement.filterNotNull().collect { versionName ->
                    selfUpdateTracker.announced()
                    batterySnackbar.showSnackbar(
                        getString(R.string.self_update_completed, versionName),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                }
            }
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
        handleBatterySettingsIntent()
        openPesce.value = intent.getBooleanExtra("open_pesce", false)
        openUpdates.value = intent.getBooleanExtra(NotificationHelper.EXTRA_NAVIGATE_TO_UPDATES, false)
    }

    private fun handleBatterySettingsIntent() {
        if (intent.getBooleanExtra("open_battery_settings", false)) {
            openedBatterySettings = true
            intent.removeExtra("open_battery_settings")
            openPushBatterySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPushBatteryExemption()) notifications.cancelStartupBatteryWarning()
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
