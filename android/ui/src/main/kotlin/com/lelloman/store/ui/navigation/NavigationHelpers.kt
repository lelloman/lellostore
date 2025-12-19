package com.lelloman.store.ui.navigation

import androidx.navigation.NavController

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
