package com.lelloman.store.domain.preferences

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow

interface UserPreferencesStore {
    val themeMode: StateFlow<ThemeMode>
    val updateCheckInterval: StateFlow<UpdateCheckInterval>
    val wifiOnlyDownloads: StateFlow<Boolean>
    val autoUpdateDefault: StateFlow<Boolean>
    val releaseChannelDefault: StateFlow<ReleaseChannel>
    val installationChannels: StateFlow<List<InstallationChannelPreference>>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUpdateCheckInterval(interval: UpdateCheckInterval)
    suspend fun setWifiOnlyDownloads(enabled: Boolean)
    suspend fun setAutoUpdateDefault(enabled: Boolean)
    suspend fun setReleaseChannelDefault(channel: ReleaseChannel)
    suspend fun setInstallationChannels(channels: List<InstallationChannelPreference>)
    fun autoUpdateOverride(packageName: String): Flow<AutoUpdateOverride>
    fun releaseChannelOverride(packageName: String): Flow<ReleaseChannelOverride>
    suspend fun setAutoUpdateOverride(packageName: String, override: AutoUpdateOverride)
    suspend fun setReleaseChannelOverride(packageName: String, override: ReleaseChannelOverride)
}

data class InstallationChannelPreference(
    val id: String,
    val enabled: Boolean,
)

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

enum class ReleaseChannel {
    Stable,
    Beta,
}

enum class AutoUpdateOverride {
    Inherit,
    Enabled,
    Disabled,
}

enum class ReleaseChannelOverride {
    Inherit,
    Stable,
    Beta,
}

enum class AppAccessLevel {
    Stable,
    Beta,
}

data class EffectiveAppUpdatePolicy(
    val autoUpdateEnabled: Boolean,
    val preferredChannel: ReleaseChannel,
    val effectiveChannel: ReleaseChannel,
    val isAuthorized: Boolean,
)

object AppUpdatePolicyResolver {
    fun resolve(
        autoUpdateDefault: Boolean,
        releaseChannelDefault: ReleaseChannel,
        autoUpdateOverride: AutoUpdateOverride,
        releaseChannelOverride: ReleaseChannelOverride,
        accessLevel: AppAccessLevel?,
        hasBetaRelease: Boolean,
    ): EffectiveAppUpdatePolicy {
        val autoUpdateEnabled = when (autoUpdateOverride) {
            AutoUpdateOverride.Inherit -> autoUpdateDefault
            AutoUpdateOverride.Enabled -> true
            AutoUpdateOverride.Disabled -> false
        }
        val preferredChannel = when (releaseChannelOverride) {
            ReleaseChannelOverride.Inherit -> releaseChannelDefault
            ReleaseChannelOverride.Stable -> ReleaseChannel.Stable
            ReleaseChannelOverride.Beta -> ReleaseChannel.Beta
        }
        val effectiveChannel = if (
            preferredChannel == ReleaseChannel.Beta &&
            accessLevel == AppAccessLevel.Beta &&
            hasBetaRelease
        ) {
            ReleaseChannel.Beta
        } else {
            ReleaseChannel.Stable
        }
        return EffectiveAppUpdatePolicy(
            autoUpdateEnabled = autoUpdateEnabled,
            preferredChannel = preferredChannel,
            effectiveChannel = effectiveChannel,
            isAuthorized = accessLevel != null,
        )
    }
}
