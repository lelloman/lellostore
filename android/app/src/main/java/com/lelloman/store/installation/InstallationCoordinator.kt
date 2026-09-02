package com.lelloman.store.installation

import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.logger.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstallationCoordinator @Inject constructor(
    channels: Set<@JvmSuppressWildcards InstallationChannel>,
    private val logger: Logger,
) {
    private val orderedChannels = channels.sortedWith(
        compareBy<InstallationChannel> { it.metadata.requiresUserInteraction }
            .thenBy { it.metadata.priority }
            .thenBy { it.metadata.id }
    )

    val channelMetadata: List<InstallationChannelMetadata> = orderedChannels.map { it.metadata }

    suspend fun install(request: InstallationRequest): InstallationResult {
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
            return InstallationResult.Failed(listOf(reason))
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

        return permissionRequired ?: InstallationResult.Failed(failures)
    }

    private companion object {
        const val TAG = "InstallationCoordinator"
    }
}

sealed interface InstallationResult {
    data class Installed(val channel: InstallationChannelMetadata) : InstallationResult
    data class UserActionStarted(val channel: InstallationChannelMetadata) : InstallationResult
    data class PermissionRequired(
        val channel: InstallationChannelMetadata,
        val reason: String,
    ) : InstallationResult
    data class Failed(val reasons: List<String>) : InstallationResult
}
