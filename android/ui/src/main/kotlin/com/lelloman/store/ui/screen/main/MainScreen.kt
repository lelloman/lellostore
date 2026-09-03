package com.lelloman.store.ui.screen.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lelloman.store.ui.R
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lelloman.store.ui.navigation.MainTab
import com.lelloman.store.ui.components.LelloStoreBrandMark
import com.lelloman.store.ui.components.lelloStoreNavigationBarItemColors
import com.lelloman.store.ui.screen.catalog.CatalogScreen
import com.lelloman.store.ui.screen.settings.SettingsScreen
import com.lelloman.store.ui.screen.updates.UpdatesScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onAppClick: (String) -> Unit,
    onProfileClick: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentItem = BottomNavItem.entries.firstOrNull {
        currentDestination?.hasRoute(it.route::class) == true
    } ?: BottomNavItem.Catalog

    Scaffold(
        topBar = {
            LellostoreTopBar(
                currentSection = stringResource(currentItem.labelRes),
                onProfileClick = onProfileClick,
            )
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
                SettingsScreen(onNavigateToLogin = onNavigateToLogin)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LellostoreTopBar(
    currentSection: String,
    onProfileClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                LelloStoreBrandMark(contentDescription = null, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.splash_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = currentSection,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        },
        actions = {
            Surface(
                modifier = Modifier.padding(end = 8.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                IconButton(onClick = onProfileClick) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = stringResource(R.string.content_description_profile),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun LellostoreBottomNav(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        BottomNavItem.entries.forEach { item ->
            val label = stringResource(item.labelRes)
            NavigationBarItem(
                icon = { Icon(item.icon, contentDescription = label) },
                label = { Text(label) },
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
                colors = lelloStoreNavigationBarItemColors(),
            )
        }
    }
}

private enum class BottomNavItem(
    val route: MainTab,
    val icon: ImageVector,
    val labelRes: Int,
) {
    Catalog(MainTab.Catalog, Icons.Default.Home, R.string.nav_catalog),
    Updates(MainTab.Updates, Icons.Default.Refresh, R.string.nav_updates),
    Settings(MainTab.Settings, Icons.Default.Settings, R.string.nav_settings),
}
