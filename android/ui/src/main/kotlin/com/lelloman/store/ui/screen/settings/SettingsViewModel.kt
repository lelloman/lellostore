package com.lelloman.store.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SettingsScreenState())
    val state: StateFlow<SettingsScreenState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<SettingsScreenEvent>()
    val events: SharedFlow<SettingsScreenEvent> = mutableEvents.asSharedFlow()

    init {
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                interactor.themeMode(),
                interactor.updateCheckInterval(),
                interactor.wifiOnlyDownloads(),
                interactor.userEmail(),
            ) { theme, interval, wifiOnly, email ->
                SettingsScreenState(
                    themeMode = theme,
                    updateCheckInterval = interval,
                    wifiOnlyDownloads = wifiOnly,
                    userEmail = email,
                    appVersion = interactor.getAppVersion(),
                )
            }.collect { newState ->
                mutableState.value = newState
            }
        }
    }

    fun onThemeModeChanged(mode: ThemeModeOption) {
        viewModelScope.launch {
            interactor.setThemeMode(mode)
        }
    }

    fun onUpdateCheckIntervalChanged(interval: UpdateCheckIntervalOption) {
        viewModelScope.launch {
            interactor.setUpdateCheckInterval(interval)
        }
    }

    fun onWifiOnlyDownloadsChanged(enabled: Boolean) {
        viewModelScope.launch {
            interactor.setWifiOnlyDownloads(enabled)
        }
    }

    fun onLogoutClick() {
        viewModelScope.launch {
            interactor.logout()
            mutableEvents.emit(SettingsScreenEvent.NavigateToLogin)
        }
    }

    interface Interactor {
        fun themeMode(): StateFlow<ThemeModeOption>
        fun updateCheckInterval(): StateFlow<UpdateCheckIntervalOption>
        fun wifiOnlyDownloads(): StateFlow<Boolean>
        fun userEmail(): StateFlow<String?>
        fun getAppVersion(): String
        suspend fun setThemeMode(mode: ThemeModeOption)
        suspend fun setUpdateCheckInterval(interval: UpdateCheckIntervalOption)
        suspend fun setWifiOnlyDownloads(enabled: Boolean)
        suspend fun logout()
    }
}

data class SettingsScreenState(
    val themeMode: ThemeModeOption = ThemeModeOption.System,
    val updateCheckInterval: UpdateCheckIntervalOption = UpdateCheckIntervalOption.Hours24,
    val wifiOnlyDownloads: Boolean = true,
    val userEmail: String? = null,
    val appVersion: String = "",
)

sealed interface SettingsScreenEvent {
    data object NavigateToLogin : SettingsScreenEvent
}

enum class ThemeModeOption(val displayName: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
}

enum class UpdateCheckIntervalOption(val displayName: String) {
    Hours6("Every 6 hours"),
    Hours12("Every 12 hours"),
    Hours24("Every 24 hours"),
    Manual("Manual only"),
}
