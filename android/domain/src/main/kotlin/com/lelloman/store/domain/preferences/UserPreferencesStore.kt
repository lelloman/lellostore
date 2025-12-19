package com.lelloman.store.domain.preferences

import kotlinx.coroutines.flow.StateFlow

interface UserPreferencesStore {
    val themeMode: StateFlow<ThemeMode>
    val updateCheckInterval: StateFlow<UpdateCheckInterval>
    val wifiOnlyDownloads: StateFlow<Boolean>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUpdateCheckInterval(interval: UpdateCheckInterval)
    suspend fun setWifiOnlyDownloads(enabled: Boolean)
}

enum class ThemeMode {
    System,
    Light,
    Dark
}

enum class UpdateCheckInterval {
    Hours6,
    Hours12,
    Hours24,
    Manual
}
