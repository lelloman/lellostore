package com.lelloman.store.domain.download

import kotlinx.coroutines.flow.StateFlow

interface DownloadManager {
    val activeDownloads: StateFlow<Map<String, DownloadProgress>>
    suspend fun downloadAndInstall(
        packageName: String,
        versionCode: Int,
        installationMode: InstallationMode = InstallationMode.FOREGROUND,
        purpose: com.lelloman.store.domain.model.AcquisitionPurpose = com.lelloman.store.domain.model.AcquisitionPurpose.INSTALL,
    ): DownloadResult
    fun cancelDownload(packageName: String)
    fun canInstallPackages(): Boolean
    fun openInstallPermissionSettings()
}

enum class InstallationMode {
    FOREGROUND,
    BACKGROUND,
}

data class DownloadProgress(
    val packageName: String,
    val progress: Float,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val state: DownloadState,
    /** Who started the operation: a user tap or the scheduled worker. */
    val mode: InstallationMode = InstallationMode.FOREGROUND,
    /** Human-readable failure reason, set only for [DownloadState.FAILED]. */
    val reason: String? = null,
)

enum class DownloadState {
    PENDING,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PERMISSION_REQUIRED,
    /** Android's interactive installer was opened; the user has not confirmed yet. */
    AWAITING_CONFIRMATION,
}

val DownloadState.isInProgress: Boolean
    get() = this != DownloadState.COMPLETED &&
        this != DownloadState.FAILED &&
        this != DownloadState.CANCELLED &&
        this != DownloadState.PERMISSION_REQUIRED &&
        this != DownloadState.AWAITING_CONFIRMATION

sealed interface DownloadResult {
    data object Success : DownloadResult
    data object Cancelled : DownloadResult
    data object PermissionRequired : DownloadResult
    /** Android's interactive installer was opened; the outcome depends on the user. */
    data object AwaitingConfirmation : DownloadResult
    /** The verified APK is retained for a later foreground installation. */
    data object UserActionRequired : DownloadResult
    data class Failed(
        val reason: String,
        val kind: DownloadFailureKind = DownloadFailureKind.GENERIC,
    ) : DownloadResult
}

enum class DownloadFailureKind {
    GENERIC,
    INCOMPATIBLE_SIGNATURE,
    /** Another operation for the package is running; its own state reports the outcome. */
    ALREADY_IN_PROGRESS,
}
