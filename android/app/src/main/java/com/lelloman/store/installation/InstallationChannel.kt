package com.lelloman.store.installation

import com.lelloman.store.domain.download.InstallationMode
import java.io.File

/** Static capabilities used to choose an installation channel before attempting it. */
data class InstallationChannelMetadata(
    val id: String,
    val displayName: String,
    val requiresUserInteraction: Boolean,
    val priority: Int,
) {
    val supportsBackgroundInstallation: Boolean
        get() = !requiresUserInteraction
}

data class InstallationRequest(
    val apk: File,
    val packageName: String,
    val mode: InstallationMode = InstallationMode.FOREGROUND,
)

interface InstallationChannel {
    val metadata: InstallationChannelMetadata

    suspend fun install(request: InstallationRequest): ChannelInstallationResult
}

sealed interface ChannelInstallationResult {
    /** The package manager completed the installation. */
    data object Installed : ChannelInstallationResult

    /** The channel launched a flow that now needs the user to confirm the installation. */
    data object UserActionStarted : ChannelInstallationResult

    /** The channel cannot currently be used. Trying another channel is always safe. */
    data class Unavailable(val reason: String) : ChannelInstallationResult

    /** A system permission must be granted before this channel can be used. */
    data class PermissionRequired(val reason: String) : ChannelInstallationResult

    /**
     * The channel attempted the install and failed. Fallback is opt-in because retrying an
     * ambiguous failure through another channel could submit the same installation twice.
     */
    data class Failed(
        val reason: String,
        val canTryNextChannel: Boolean = false,
    ) : ChannelInstallationResult
}
