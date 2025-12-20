package com.lelloman.store.domain.download

import kotlinx.coroutines.flow.StateFlow

interface DownloadManager {
    val activeDownloads: StateFlow<Map<String, DownloadProgress>>
    suspend fun downloadAndInstall(packageName: String, versionCode: Int): DownloadResult
    fun cancelDownload(packageName: String)
    fun canInstallPackages(): Boolean
    fun openInstallPermissionSettings()
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
    data class Failed(val reason: String) : DownloadResult
}
