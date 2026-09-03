package com.lelloman.store.ui.screen.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.ui.model.ThemeMode
import com.lelloman.store.ui.navigation.MainTab
import com.lelloman.store.ui.theme.LellostoreTheme
import org.junit.Rule
import org.junit.Test

class MainNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun updatesItemNavigatesToUpdatesAndBecomesCurrentDestination() {
        lateinit var navController: NavHostController

        composeRule.setContent {
            LellostoreTheme(themeMode = ThemeMode.Light) {
                navController = rememberNavController()
                Column {
                    NavHost(
                        navController = navController,
                        startDestination = MainTab.Catalog,
                        modifier = Modifier.weight(1f),
                    ) {
                        composable<MainTab.Catalog> { Text("Catalog content") }
                        composable<MainTab.Updates> { Text("Updates content") }
                        composable<MainTab.Settings> { Text("Settings content") }
                    }
                    LellostoreBottomNav(navController)
                }
            }
        }

        composeRule.onNodeWithText("Updates").performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertThat(navController.currentDestination?.hasRoute(MainTab.Updates::class)).isTrue()
        }
    }
}
