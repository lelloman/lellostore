package com.lelloman.store.domain.download

import kotlinx.coroutines.flow.StateFlow

interface DownloadManager {
    val activeDownloads: StateFlow<Map<String, DownloadProgress>>
    suspend fun downloadAndInstall(packageName: String, versionCode: Int): DownloadResult
    fun cancelDownload(packageName: String)
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
    CANCELLED
}

sealed interface DownloadResult {
    data object Success : DownloadResult
    data object Cancelled : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}
