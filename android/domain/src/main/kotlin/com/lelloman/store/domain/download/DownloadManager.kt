package com.lelloman.store.domain.download

import kotlinx.coroutines.flow.StateFlow

interface DownloadManager {
    val activeDownloads: StateFlow<Map<String, DownloadProgress>>
    suspend fun downloadAndInstall(
        packageName: String,
        versionCode: Int,
        installationMode: InstallationMode = InstallationMode.FOREGROUND,
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
)

enum class DownloadState {
    PENDING,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PERMISSION_REQUIRED
}

sealed interface DownloadResult {
    data object Success : DownloadResult
    data object Cancelled : DownloadResult
    data object PermissionRequired : DownloadResult
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
}
