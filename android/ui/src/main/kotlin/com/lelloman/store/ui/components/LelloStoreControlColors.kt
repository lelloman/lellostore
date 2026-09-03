package com.lelloman.store.ui.components

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import com.lelloman.store.ui.theme.Green20
import com.lelloman.store.ui.theme.LelloGreen

@Composable
fun lelloStoreButtonColors() = ButtonDefaults.buttonColors(
    containerColor = LelloGreen,
    contentColor = Green20,
)

@Composable
fun lelloStoreSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Green20,
    checkedTrackColor = LelloGreen,
    checkedBorderColor = LelloGreen,
)

@Composable
fun lelloStoreFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = LelloGreen,
    selectedLabelColor = Green20,
    selectedLeadingIconColor = Green20,
    selectedTrailingIconColor = Green20,
)

@Composable
fun lelloStoreNavigationBarItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Green20,
    indicatorColor = LelloGreen,
)
