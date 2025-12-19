package com.lelloman.store.localdata.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lelloman.store.domain.preferences.ThemeMode
import com.lelloman.store.domain.preferences.UpdateCheckInterval
import com.lelloman.store.domain.preferences.UserPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal class UserPreferencesStoreImpl(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : UserPreferencesStore {

    override val themeMode: StateFlow<ThemeMode> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.THEME_MODE]?.let { value ->
                ThemeMode.entries.find { it.name == value }
            } ?: ThemeMode.System
        }
        .stateIn(scope, SharingStarted.Eagerly, ThemeMode.System)

    override val updateCheckInterval: StateFlow<UpdateCheckInterval> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.UPDATE_CHECK_INTERVAL]?.let { value ->
                UpdateCheckInterval.entries.find { it.name == value }
            } ?: UpdateCheckInterval.Hours24
        }
        .stateIn(scope, SharingStarted.Eagerly, UpdateCheckInterval.Hours24)

    override val wifiOnlyDownloads: StateFlow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.WIFI_ONLY_DOWNLOADS] ?: true
        }
        .stateIn(scope, SharingStarted.Eagerly, true)

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = mode.name
        }
    }

    override suspend fun setUpdateCheckInterval(interval: UpdateCheckInterval) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.UPDATE_CHECK_INTERVAL] = interval.name
        }
    }

    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.WIFI_ONLY_DOWNLOADS] = enabled
        }
    }
}
