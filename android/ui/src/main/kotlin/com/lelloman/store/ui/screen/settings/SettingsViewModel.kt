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
                interactor.serverUrl(),
            ) { theme, interval, wifiOnly, email, serverUrl ->
                SettingsScreenState(
                    themeMode = theme,
                    updateCheckInterval = interval,
                    wifiOnlyDownloads = wifiOnly,
                    userEmail = email,
                    serverUrl = serverUrl,
                    serverUrlInput = serverUrl,
                    appVersion = interactor.getAppVersion(),
                )
            }.collect { newState ->
                val currentState = mutableState.value
                mutableState.value = currentState.copy(
                    themeMode = newState.themeMode,
                    updateCheckInterval = newState.updateCheckInterval,
                    wifiOnlyDownloads = newState.wifiOnlyDownloads,
                    userEmail = newState.userEmail,
                    serverUrl = newState.serverUrl,
                    serverUrlInput = if (currentState.serverUrlInput.isEmpty()) newState.serverUrl else currentState.serverUrlInput,
                    appVersion = newState.appVersion,
                )
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

    fun onServerUrlInputChanged(input: String) {
        mutableState.value = mutableState.value.copy(
            serverUrlInput = input,
            serverUrlError = null,
        )
    }

    fun onServerUrlSave() {
        viewModelScope.launch {
            val result = interactor.setServerUrl(mutableState.value.serverUrlInput)
            when (result) {
                is SetServerUrlResult.Success -> {
                    mutableState.value = mutableState.value.copy(serverUrlError = null)
                }
                is SetServerUrlResult.InvalidUrl -> {
                    mutableState.value = mutableState.value.copy(serverUrlError = "Invalid URL")
                }
            }
        }
    }

    interface Interactor {
        fun themeMode(): StateFlow<ThemeModeOption>
        fun updateCheckInterval(): StateFlow<UpdateCheckIntervalOption>
        fun wifiOnlyDownloads(): StateFlow<Boolean>
        fun userEmail(): StateFlow<String?>
        fun serverUrl(): StateFlow<String>
        fun getAppVersion(): String
        suspend fun setThemeMode(mode: ThemeModeOption)
        suspend fun setUpdateCheckInterval(interval: UpdateCheckIntervalOption)
        suspend fun setWifiOnlyDownloads(enabled: Boolean)
        suspend fun setServerUrl(url: String): SetServerUrlResult
        suspend fun logout()
    }

    sealed interface SetServerUrlResult {
        data object Success : SetServerUrlResult
        data object InvalidUrl : SetServerUrlResult
    }
}

data class SettingsScreenState(
    val themeMode: ThemeModeOption = ThemeModeOption.System,
    val updateCheckInterval: UpdateCheckIntervalOption = UpdateCheckIntervalOption.Hours24,
    val wifiOnlyDownloads: Boolean = true,
    val userEmail: String? = null,
    val serverUrl: String = "",
    val serverUrlInput: String = "",
    val serverUrlError: String? = null,
    val appVersion: String = "",
)

sealed interface SettingsScreenEvent {
    data object NavigateToLogin : SettingsScreenEvent
}

enum class ThemeModeOption {
    System,
    Light,
    Dark,
}

enum class UpdateCheckIntervalOption {
    Hours6,
    Hours12,
    Hours24,
    Manual,
}
