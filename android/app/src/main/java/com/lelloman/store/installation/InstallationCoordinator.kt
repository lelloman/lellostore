package com.lelloman.store.installation

import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.domain.preferences.InstallationChannelPreference
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.logger.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstallationCoordinator @Inject constructor(
    channels: Set<@JvmSuppressWildcards InstallationChannel>,
    private val logger: Logger,
    private val userPreferencesStore: UserPreferencesStore,
) {
    private val defaultChannels = channels.sortedWith(
        compareBy<InstallationChannel> { it.metadata.requiresUserInteraction }
            .thenBy { it.metadata.priority }
            .thenBy { it.metadata.id }
    )

    val channelMetadata: List<InstallationChannelMetadata> = defaultChannels.map { it.metadata }

    suspend fun install(request: InstallationRequest): InstallationResult {
        val orderedChannels = configuredInstallationChannels(
            channels = defaultChannels,
            preferences = userPreferencesStore.installationChannels.value,
        ).filter { it.enabled }.map { it.channel }
        val eligibleChannels = when (request.mode) {
            InstallationMode.FOREGROUND -> orderedChannels
            InstallationMode.BACKGROUND -> orderedChannels.filter {
                it.metadata.supportsBackgroundInstallation
            }
        }
        if (eligibleChannels.isEmpty()) {
            val reason = when (request.mode) {
                InstallationMode.FOREGROUND -> "No installation channels are registered"
                InstallationMode.BACKGROUND -> "No background installation channels are available"
            }
            return if (request.mode == InstallationMode.BACKGROUND) {
                InstallationResult.UserActionRequired(listOf(reason))
            } else {
                InstallationResult.Failed(listOf(reason))
            }
        }

        val failures = mutableListOf<String>()
        var permissionRequired: InstallationResult.PermissionRequired? = null

        for (channel in eligibleChannels) {
            val metadata = channel.metadata
            logger.i(TAG, "Trying installation channel ${metadata.id}")

            when (val result = channel.install(request)) {
                ChannelInstallationResult.Installed -> {
                    return InstallationResult.Installed(metadata)
                }
                ChannelInstallationResult.UserActionStarted -> {
                    return InstallationResult.UserActionStarted(metadata)
                }
                is ChannelInstallationResult.Unavailable -> {
                    failures += "${metadata.id}: ${result.reason}"
                    logger.i(TAG, "Installation channel ${metadata.id} unavailable: ${result.reason}")
                }
                is ChannelInstallationResult.PermissionRequired -> {
                    failures += "${metadata.id}: ${result.reason}"
                    permissionRequired = InstallationResult.PermissionRequired(metadata, result.reason)
                    logger.i(TAG, "Installation channel ${metadata.id} requires permission")
                }
                is ChannelInstallationResult.Failed -> {
                    failures += "${metadata.id}: ${result.reason}"
                    logger.w(TAG, "Installation channel ${metadata.id} failed: ${result.reason}")
                    if (!result.canTryNextChannel) {
                        return InstallationResult.Failed(failures)
                    }
                }
            }
        }

        return if (request.mode == InstallationMode.BACKGROUND) {
            InstallationResult.UserActionRequired(failures)
        } else {
            permissionRequired ?: InstallationResult.Failed(failures)
        }
    }

    private companion object {
        const val TAG = "InstallationCoordinator"
    }
}

internal data class ConfiguredInstallationChannel(
    val channel: InstallationChannel,
    val enabled: Boolean,
)

internal fun configuredInstallationChannels(
    channels: List<InstallationChannel>,
    preferences: List<InstallationChannelPreference>,
): List<ConfiguredInstallationChannel> {
    val channelsById = channels.associateBy { it.metadata.id }
    val configured = preferences.mapNotNull { preference ->
        channelsById[preference.id]?.let { channel ->
            ConfiguredInstallationChannel(channel, preference.enabled)
        }
    }
    val configuredIds = configured.mapTo(mutableSetOf()) { it.channel.metadata.id }
    val newChannels = channels
        .filterNot { it.metadata.id in configuredIds }
        .map { ConfiguredInstallationChannel(it, enabled = true) }
    return configured + newChannels
}

sealed interface InstallationResult {
    data class Installed(val channel: InstallationChannelMetadata) : InstallationResult
    data class UserActionStarted(val channel: InstallationChannelMetadata) : InstallationResult
    data class PermissionRequired(
        val channel: InstallationChannelMetadata,
        val reason: String,
    ) : InstallationResult
    data class UserActionRequired(val reasons: List<String>) : InstallationResult
    data class Failed(val reasons: List<String>) : InstallationResult
}
