package com.lelloman.store.ui.navigation

import kotlinx.serialization.Serializable

sealed interface Screen {
    @Serializable
    data object Splash : Screen

    @Serializable
    data object Login : Screen

    @Serializable
    data object Main : Screen

    @Serializable
    data class AppDetail(val packageName: String) : Screen
}

sealed interface MainTab {
    @Serializable
    data object Catalog : MainTab

    @Serializable
    data object Updates : MainTab

    @Serializable
    data object Settings : MainTab
}
