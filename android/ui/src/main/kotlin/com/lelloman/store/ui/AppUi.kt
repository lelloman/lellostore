package com.lelloman.store.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lelloman.store.ui.model.AuthResult
import com.lelloman.store.ui.model.ThemeMode
import com.lelloman.store.ui.navigation.Screen
import com.lelloman.store.ui.navigation.fromLoginToMain
import com.lelloman.store.ui.navigation.fromSplashToLogin
import com.lelloman.store.ui.navigation.fromSplashToMain
import com.lelloman.store.ui.navigation.logout
import com.lelloman.store.ui.navigation.toAppDetail
import com.lelloman.store.ui.screen.detail.AppDetailScreen
import com.lelloman.store.ui.screen.login.LoginScreen
import com.lelloman.store.ui.screen.main.MainScreen
import com.lelloman.store.ui.screen.main.ProfileBottomSheet
import com.lelloman.store.ui.screen.splash.SplashScreen
import com.lelloman.store.ui.theme.LellostoreTheme
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

@Composable
fun AppUi(
    themeMode: ThemeMode = ThemeMode.System,
    isLoggedIn: Boolean = false,
    userEmail: String = "",
    onAuthResponse: (AuthorizationResponse?, AuthorizationException?, onResult: (AuthResult) -> Unit) -> Unit = { _, _, _ -> },
    onLogout: () -> Unit = {},
    forceNavigateToLogin: Boolean = false,
    onForceNavigateToLoginHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    var showProfileSheet by rememberSaveable { mutableStateOf(false) }

    // Handle forced navigation to login (e.g., session expired)
    LaunchedEffect(forceNavigateToLogin) {
        if (forceNavigateToLogin) {
            showProfileSheet = false
            navController.logout()
            onForceNavigateToLoginHandled()
        }
    }

    LellostoreTheme(themeMode = themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Splash,
            ) {
                composable<Screen.Splash> {
                    SplashScreen(
                        onNavigateToLogin = { navController.fromSplashToLogin() },
                        onNavigateToMain = { navController.fromSplashToMain() },
                        isLoggedIn = isLoggedIn,
                    )
                }

                composable<Screen.Login> {
                    LoginScreen(
                        onNavigateToMain = { navController.fromLoginToMain() },
                        onAuthResponse = onAuthResponse,
                    )
                }

                composable<Screen.Main> {
                    MainScreen(
                        onAppClick = { packageName -> navController.toAppDetail(packageName) },
                        onProfileClick = { showProfileSheet = true },
                        onNavigateToLogin = {
                            onLogout()
                            navController.logout()
                        },
                    )
                }

                composable<Screen.AppDetail> { backStackEntry ->
                    val route = backStackEntry.toRoute<Screen.AppDetail>()
                    AppDetailScreen(
                        packageName = route.packageName,
                        onBackClick = { navController.popBackStack() },
                    )
                }
            }
        }

        if (showProfileSheet) {
            ProfileBottomSheet(
                userEmail = userEmail,
                onLogout = {
                    showProfileSheet = false
                    onLogout()
                    navController.logout()
                },
                onDismiss = { showProfileSheet = false },
            )
        }
    }
}
