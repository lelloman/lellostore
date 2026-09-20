package com.lelloman.store.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.lelloman.store.ui.screen.pesce.PesceScreen
import androidx.navigation.NavDestination.Companion.hasRoute
import com.lelloman.store.ui.screen.main.MainScreen
import com.lelloman.store.ui.screen.main.ProfileBottomSheet
import com.lelloman.store.ui.screen.splash.SplashScreen
import com.lelloman.store.ui.theme.LellostoreTheme
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUi(
    themeMode: ThemeMode = ThemeMode.System,
    isLoggedIn: Boolean = false,
    userEmail: String = "",
    onAuthResponse: (AuthorizationResponse?, AuthorizationException?, onResult: (AuthResult) -> Unit) -> Unit = { _, _, _ -> },
    onLogout: () -> Unit = {},
    forceNavigateToLogin: Boolean = false,
    onForceNavigateToLoginHandled: () -> Unit = {},
    openPesce: Boolean = false,
    onOpenPesceHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    // Resolve cold notification entry before the splash's delayed auth redirect can run.
    val initialDestination = remember { if (openPesce) Screen.Pesce else Screen.Splash }
    var showProfileSheet by rememberSaveable { mutableStateOf(false) }
    var pesceSelected by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openPesce) {
        if (openPesce) {
            navController.navigate(Screen.Pesce) { popUpTo(0); launchSingleTop = true }
            onOpenPesceHandled()
        }
    }

    // Handle forced navigation to login (e.g., session expired)
    LaunchedEffect(forceNavigateToLogin) {
        if (forceNavigateToLogin) {
            showProfileSheet = false
            if (pesceSelected || navController.currentDestination?.hasRoute<Screen.Pesce>() == true ||
                navController.currentDestination?.hasRoute<Screen.PesceLogin>() == true) {
                if (navController.currentDestination?.hasRoute<Screen.Pesce>() != true) {
                    navController.navigate(Screen.Pesce) { popUpTo(0); launchSingleTop = true }
                }
            } else navController.logout()
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
                startDestination = initialDestination,
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
                        onPesceClick = { navController.navigate(Screen.Pesce) },
                    )
                }

                composable<Screen.PesceLogin> {
                    LoginScreen(
                        onNavigateToMain = { navController.popBackStack() },
                        onAuthResponse = onAuthResponse,
                        onPesceClick = { navController.popBackStack() },
                    )
                }

                composable<Screen.Pesce> {
                    Scaffold(topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.pesce_title)) },
                            navigationIcon = {
                                IconButton(onClick = {
                                    if (isLoggedIn) navController.navigate(Screen.Main) { popUpTo(0) }
                                    else if (!navController.popBackStack()) navController.navigate(Screen.Login)
                                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back)) }
                            })
                    }) { padding ->
                        PesceScreen(onSignIn = { navController.navigate(Screen.PesceLogin) }, modifier = Modifier.padding(padding))
                    }
                }

                composable<Screen.Main> {
                    MainScreen(
                        onAppClick = { packageName -> navController.toAppDetail(packageName) },
                        onProfileClick = { showProfileSheet = true },
                        onNavigateToLogin = {
                            onLogout()
                            navController.logout()
                        },
                        onPesceSelected = { pesceSelected = it },
                        onPesceSignIn = { navController.navigate(Screen.PesceLogin) },
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
