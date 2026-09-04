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
        observeUpdatePolicy()
    }

    private fun observeUpdatePolicy() {
        viewModelScope.launch {
            combine(
                interactor.autoUpdateDefault(),
                interactor.releaseChannelDefault(),
                interactor.installationChannels(),
            ) { autoUpdate, releaseChannel, installationChannels ->
                Triple(autoUpdate, releaseChannel, installationChannels)
            }
                .collect { (autoUpdate, releaseChannel, installationChannels) ->
                    mutableState.value = mutableState.value.copy(
                        autoUpdateDefault = autoUpdate,
                        releaseChannelDefault = releaseChannel,
                        installationChannels = installationChannels,
                    )
                }
        }
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

    fun onAutoUpdateDefaultChanged(enabled: Boolean) {
        viewModelScope.launch { interactor.setAutoUpdateDefault(enabled) }
    }

    fun onReleaseChannelDefaultChanged(channel: ReleaseChannelOption) {
        viewModelScope.launch { interactor.setReleaseChannelDefault(channel) }
    }

    fun onInstallationChannelEnabledChanged(id: String, enabled: Boolean) {
        val updated = mutableState.value.installationChannels.map { channel ->
            if (channel.id == id) channel.copy(enabled = enabled) else channel
        }
        if (updated.none { it.enabled }) return
        mutableState.value = mutableState.value.copy(installationChannels = updated)
        viewModelScope.launch { interactor.setInstallationChannels(updated) }
    }

    fun onMoveInstallationChannel(id: String, offset: Int) {
        val channels = mutableState.value.installationChannels.toMutableList()
        val from = channels.indexOfFirst { it.id == id }
        val to = from + offset
        if (from < 0 || to !in channels.indices) return
        val moved = channels.removeAt(from)
        channels.add(to, moved)
        mutableState.value = mutableState.value.copy(installationChannels = channels)
        viewModelScope.launch { interactor.setInstallationChannels(channels) }
    }

    fun onTestWirelessDebugging() {
        if (mutableState.value.wirelessDebugging.isBusy) return
        mutableState.value = mutableState.value.copy(
            wirelessDebugging = AdbConnectionState.Testing,
        )
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                wirelessDebugging = interactor.testWirelessDebugging().fold(
                    onSuccess = { AdbConnectionState.Ready(it) },
                    onFailure = {
                        AdbConnectionState.Unavailable(
                            it.message ?: "Unknown connection error"
                        )
                    },
                )
            )
        }
    }

    fun onTestLegacyAdb() {
        if (mutableState.value.legacyAdb.isBusy) return
        mutableState.value = mutableState.value.copy(
            legacyAdb = AdbConnectionState.Testing,
        )
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                legacyAdb = interactor.testLegacyAdb().fold(
                    onSuccess = { AdbConnectionState.Ready(it) },
                    onFailure = {
                        AdbConnectionState.Unavailable(
                            it.message ?: "Unknown connection error"
                        )
                    },
                )
            )
        }
    }

    fun onPairWirelessDebugging(pairingCode: String) {
        if (mutableState.value.wirelessDebugging.isBusy) return
        mutableState.value = mutableState.value.copy(
            wirelessDebugging = AdbConnectionState.Pairing,
        )
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                wirelessDebugging = interactor.pairWirelessDebugging(pairingCode).fold(
                    onSuccess = { AdbConnectionState.Ready(it) },
                    onFailure = {
                        AdbConnectionState.Unavailable(
                            it.message ?: "Unknown pairing error"
                        )
                    },
                )
            )
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
        fun autoUpdateDefault(): StateFlow<Boolean>
        fun releaseChannelDefault(): StateFlow<ReleaseChannelOption>
        fun installationChannels(): StateFlow<List<InstallationChannelOption>>
        fun userEmail(): StateFlow<String?>
        fun serverUrl(): StateFlow<String>
        fun getAppVersion(): String
        suspend fun setThemeMode(mode: ThemeModeOption)
        suspend fun setUpdateCheckInterval(interval: UpdateCheckIntervalOption)
        suspend fun setWifiOnlyDownloads(enabled: Boolean)
        suspend fun setAutoUpdateDefault(enabled: Boolean)
        suspend fun setReleaseChannelDefault(channel: ReleaseChannelOption)
        suspend fun setInstallationChannels(channels: List<InstallationChannelOption>)
        suspend fun testWirelessDebugging(): Result<String>
        suspend fun pairWirelessDebugging(pairingCode: String): Result<String>
        suspend fun testLegacyAdb(): Result<String>
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
    val autoUpdateDefault: Boolean = true,
    val releaseChannelDefault: ReleaseChannelOption = ReleaseChannelOption.Stable,
    val installationChannels: List<InstallationChannelOption> = emptyList(),
    val wirelessDebugging: AdbConnectionState = AdbConnectionState.NotTested,
    val legacyAdb: AdbConnectionState = AdbConnectionState.NotTested,
    val userEmail: String? = null,
    val serverUrl: String = "",
    val serverUrlInput: String = "",
    val serverUrlError: String? = null,
    val appVersion: String = "",
)

data class InstallationChannelOption(
    val id: String,
    val displayName: String,
    val requiresUserInteraction: Boolean,
    val enabled: Boolean,
)

sealed interface AdbConnectionState {
    data object NotTested : AdbConnectionState
    data object Testing : AdbConnectionState
    data object Pairing : AdbConnectionState
    data class Ready(val device: String) : AdbConnectionState
    data class Unavailable(val reason: String) : AdbConnectionState
}

private val AdbConnectionState.isBusy: Boolean
    get() = this == AdbConnectionState.Testing || this == AdbConnectionState.Pairing

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

enum class ReleaseChannelOption {
    Stable,
    Beta,
}
