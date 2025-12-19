package com.lelloman.store

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.ui.AppUi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferencesStore: UserPreferencesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by userPreferencesStore.themeMode.collectAsState()

            AppUi(
                themeMode = themeMode,
                isLoggedIn = false, // TODO: Connect to AuthStore
                userEmail = "", // TODO: Connect to AuthStore
                onLogin = { /* TODO: Implement OIDC login */ },
                onLogout = { /* TODO: Implement logout */ },
            )
        }
    }
}
