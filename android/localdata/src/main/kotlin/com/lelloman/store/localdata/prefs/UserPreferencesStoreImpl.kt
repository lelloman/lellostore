package com.lelloman.store.localdata.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lelloman.store.domain.preferences.ThemeMode
import com.lelloman.store.domain.preferences.UpdateCheckInterval
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.domain.preferences.AutoUpdateOverride
import com.lelloman.store.domain.preferences.ReleaseChannel
import com.lelloman.store.domain.preferences.ReleaseChannelOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class UserPreferencesStoreImpl(
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

    override val autoUpdateDefault: StateFlow<Boolean> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.AUTO_UPDATE_DEFAULT] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    override val releaseChannelDefault: StateFlow<ReleaseChannel> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.RELEASE_CHANNEL_DEFAULT]?.let { value ->
                ReleaseChannel.entries.find { it.name == value }
            } ?: ReleaseChannel.Stable
        }
        .stateIn(scope, SharingStarted.Eagerly, ReleaseChannel.Stable)

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

    override suspend fun setAutoUpdateDefault(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_UPDATE_DEFAULT] = enabled
        }
    }

    override suspend fun setReleaseChannelDefault(channel: ReleaseChannel) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.RELEASE_CHANNEL_DEFAULT] = channel.name
        }
    }

    override fun autoUpdateOverride(packageName: String) = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.autoUpdateOverride(packageName)]?.let { value ->
            AutoUpdateOverride.entries.find { it.name == value }
        } ?: AutoUpdateOverride.Inherit
    }

    override fun releaseChannelOverride(packageName: String) = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.releaseChannelOverride(packageName)]?.let { value ->
            ReleaseChannelOverride.entries.find { it.name == value }
        } ?: ReleaseChannelOverride.Inherit
    }

    override suspend fun setAutoUpdateOverride(
        packageName: String,
        override: AutoUpdateOverride,
    ) {
        dataStore.edit { preferences ->
            val key = PreferencesKeys.autoUpdateOverride(packageName)
            if (override == AutoUpdateOverride.Inherit) preferences.remove(key)
            else preferences[key] = override.name
        }
    }

    override suspend fun setReleaseChannelOverride(
        packageName: String,
        override: ReleaseChannelOverride,
    ) {
        dataStore.edit { preferences ->
            val key = PreferencesKeys.releaseChannelOverride(packageName)
            if (override == ReleaseChannelOverride.Inherit) preferences.remove(key)
            else preferences[key] = override.name
        }
    }
}
