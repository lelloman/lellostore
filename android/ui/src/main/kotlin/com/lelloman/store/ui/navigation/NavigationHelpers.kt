package com.lelloman.store.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute

fun NavController.fromSplashToLogin() {
    navigate(Screen.Login) {
        popUpTo(Screen.Splash) { inclusive = true }
    }
}

fun NavController.fromSplashToMain() {
    navigate(Screen.Main) {
        popUpTo(Screen.Splash) { inclusive = true }
    }
}

fun NavController.fromLoginToMain() {
    navigate(Screen.Main) {
        popUpTo(Screen.Login) { inclusive = true }
    }
}

fun NavController.toAppDetail(packageName: String) {
    navigate(Screen.AppDetail(packageName))
}

fun NavController.logout() {
    navigate(Screen.Login) {
        popUpTo(0) { inclusive = true }
    }
}

/**
 * Brings [Screen.Main] to the top so a tab can be selected, e.g. from a notification. While the
 * splash is still resolving auth (or the user is signed out) the stack is left alone; Main picks
 * the requested tab up once it is shown.
 */
fun NavController.toMainForTab(isLoggedIn: Boolean) {
    val destination = currentDestination ?: return
    if (destination.hasRoute<Screen.Main>()) return
    if (popBackStack<Screen.Main>(inclusive = false)) return
    if (isLoggedIn && !destination.hasRoute<Screen.Splash>()) {
        navigate(Screen.Main) {
            popUpTo(0)
            launchSingleTop = true
        }
    }
}

/** Selects [tab] in the bottom-navigation graph with the same options as a bottom-bar tap. */
fun NavController.selectMainTab(tab: MainTab) {
    navigate(tab) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
