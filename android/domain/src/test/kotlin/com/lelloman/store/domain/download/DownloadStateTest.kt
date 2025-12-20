package com.lelloman.store.domain.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadStateTest {

    @Test
    fun `DownloadState has all expected states`() {
        val states = DownloadState.entries

        assertThat(states).containsExactly(
            DownloadState.PENDING,
            DownloadState.DOWNLOADING,
            DownloadState.VERIFYING,
            DownloadState.INSTALLING,
            DownloadState.COMPLETED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
            DownloadState.PERMISSION_REQUIRED
        )
    }

    @Test
    fun `DownloadProgress holds progress information`() {
        val progress = DownloadProgress(
            packageName = "com.example.app",
            progress = 0.5f,
            bytesDownloaded = 512L,
            totalBytes = 1024L,
            state = DownloadState.DOWNLOADING
        )

        assertThat(progress.progress).isEqualTo(0.5f)
        assertThat(progress.bytesDownloaded).isEqualTo(512L)
        assertThat(progress.totalBytes).isEqualTo(1024L)
        assertThat(progress.state).isEqualTo(DownloadState.DOWNLOADING)
    }

    @Test
    fun `DownloadResult Success is a singleton`() {
        val result1: DownloadResult = DownloadResult.Success
        val result2: DownloadResult = DownloadResult.Success

        assertThat(result1).isSameInstanceAs(result2)
    }

    @Test
    fun `DownloadResult Failed contains reason`() {
        val result = DownloadResult.Failed("Network error")

        assertThat(result.reason).isEqualTo("Network error")
    }

    @Test
    fun `DownloadResult Cancelled is a singleton`() {
        val result: DownloadResult = DownloadResult.Cancelled

        assertThat(result).isEqualTo(DownloadResult.Cancelled)
    }

    @Test
    fun `DownloadResult PermissionRequired is a singleton`() {
        val result: DownloadResult = DownloadResult.PermissionRequired

        assertThat(result).isEqualTo(DownloadResult.PermissionRequired)
    }
}
