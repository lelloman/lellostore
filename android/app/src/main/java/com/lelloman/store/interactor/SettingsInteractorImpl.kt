package com.lelloman.store.interactor

import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.preferences.ThemeMode
import com.lelloman.store.domain.preferences.UpdateCheckInterval
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.ui.screen.settings.SettingsViewModel
import com.lelloman.store.ui.screen.settings.ThemeModeOption
import com.lelloman.store.ui.screen.settings.UpdateCheckIntervalOption
import com.lelloman.store.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

class SettingsInteractorImpl @Inject constructor(
    private val userPreferencesStore: UserPreferencesStore,
    private val authStore: AuthStore,
    private val configStore: ConfigStore,
    @ApplicationScope private val scope: CoroutineScope,
) : SettingsViewModel.Interactor {

    override fun themeMode(): StateFlow<ThemeModeOption> {
        return userPreferencesStore.themeMode
            .map { it.toOption() }
            .stateIn(scope, SharingStarted.Eagerly, ThemeModeOption.System)
    }

    override fun updateCheckInterval(): StateFlow<UpdateCheckIntervalOption> {
        return userPreferencesStore.updateCheckInterval
            .map { it.toOption() }
            .stateIn(scope, SharingStarted.Eagerly, UpdateCheckIntervalOption.Hours24)
    }

    override fun wifiOnlyDownloads(): StateFlow<Boolean> {
        return userPreferencesStore.wifiOnlyDownloads
    }

    override fun userEmail(): StateFlow<String?> {
        return authStore.authState
            .map { state ->
                when (state) {
                    is AuthState.Authenticated -> state.userEmail
                    else -> null
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    override fun serverUrl(): StateFlow<String> {
        return configStore.serverUrl
    }

    override fun getAppVersion(): String {
        // Version info from build.gradle.kts
        return "1.0 (1)"
    }

    override suspend fun setThemeMode(mode: ThemeModeOption) {
        userPreferencesStore.setThemeMode(mode.toDomain())
    }

    override suspend fun setUpdateCheckInterval(interval: UpdateCheckIntervalOption) {
        userPreferencesStore.setUpdateCheckInterval(interval.toDomain())
    }

    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        userPreferencesStore.setWifiOnlyDownloads(enabled)
    }

    override suspend fun setServerUrl(url: String): SettingsViewModel.SetServerUrlResult {
        return when (configStore.setServerUrl(url)) {
            is ConfigStore.SetServerUrlResult.Success -> SettingsViewModel.SetServerUrlResult.Success
            is ConfigStore.SetServerUrlResult.InvalidUrl -> SettingsViewModel.SetServerUrlResult.InvalidUrl
        }
    }

    override suspend fun logout() {
        authStore.logout()
    }

    private fun ThemeMode.toOption(): ThemeModeOption = when (this) {
        ThemeMode.System -> ThemeModeOption.System
        ThemeMode.Light -> ThemeModeOption.Light
        ThemeMode.Dark -> ThemeModeOption.Dark
    }

    private fun ThemeModeOption.toDomain(): ThemeMode = when (this) {
        ThemeModeOption.System -> ThemeMode.System
        ThemeModeOption.Light -> ThemeMode.Light
        ThemeModeOption.Dark -> ThemeMode.Dark
    }

    private fun UpdateCheckInterval.toOption(): UpdateCheckIntervalOption = when (this) {
        UpdateCheckInterval.Hours6 -> UpdateCheckIntervalOption.Hours6
        UpdateCheckInterval.Hours12 -> UpdateCheckIntervalOption.Hours12
        UpdateCheckInterval.Hours24 -> UpdateCheckIntervalOption.Hours24
        UpdateCheckInterval.Manual -> UpdateCheckIntervalOption.Manual
    }

    private fun UpdateCheckIntervalOption.toDomain(): UpdateCheckInterval = when (this) {
        UpdateCheckIntervalOption.Hours6 -> UpdateCheckInterval.Hours6
        UpdateCheckIntervalOption.Hours12 -> UpdateCheckInterval.Hours12
        UpdateCheckIntervalOption.Hours24 -> UpdateCheckInterval.Hours24
        UpdateCheckIntervalOption.Manual -> UpdateCheckInterval.Manual
    }
}
