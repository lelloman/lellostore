package com.lelloman.store.ui.screen.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lelloman.store.ui.navigation.MainTab
import com.lelloman.store.ui.screen.catalog.CatalogScreen
import com.lelloman.store.ui.screen.settings.SettingsScreen
import com.lelloman.store.ui.screen.updates.UpdatesScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onAppClick: (String) -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabNavController = rememberNavController()

    Scaffold(
        topBar = {
            LellostoreTopBar(onProfileClick = onProfileClick)
        },
        bottomBar = {
            LellostoreBottomNav(tabNavController)
        },
        modifier = modifier,
    ) { padding ->
        NavHost(
            navController = tabNavController,
            startDestination = MainTab.Catalog,
            modifier = Modifier.padding(padding),
        ) {
            composable<MainTab.Catalog> {
                CatalogScreen(onAppClick = onAppClick)
            }
            composable<MainTab.Updates> {
                UpdatesScreen(onAppClick = onAppClick)
            }
            composable<MainTab.Settings> {
                SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LellostoreTopBar(
    onProfileClick: () -> Unit,
) {
    TopAppBar(
        title = { Text("Lellostore") },
        actions = {
            IconButton(onClick = onProfileClick) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "Profile",
                )
            }
        },
    )
}

@Composable
private fun LellostoreBottomNav(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        BottomNavItem.entries.forEach { item ->
            NavigationBarItem(
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                selected = currentDestination?.hasRoute(item.route::class) == true,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }
}

private enum class BottomNavItem(
    val route: MainTab,
    val icon: ImageVector,
    val label: String,
) {
    Catalog(MainTab.Catalog, Icons.Default.Home, "Catalog"),
    Updates(MainTab.Updates, Icons.Default.Refresh, "Updates"),
    Settings(MainTab.Settings, Icons.Default.Settings, "Settings"),
}
