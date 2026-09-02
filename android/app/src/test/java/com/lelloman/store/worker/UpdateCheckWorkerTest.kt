package com.lelloman.store.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.domain.model.AvailableUpdate
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.notification.NotificationHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class UpdateCheckWorkerTest {
    private val updateChecker: UpdateChecker = mockk {
        coEvery { checkForUpdates() } returns Result.success(emptyList())
        every { availableUpdates } returns MutableStateFlow(emptyList())
    }
    private val downloadManager: DownloadManager = mockk(relaxed = true)
    private val notifications: NotificationHelper = mockk(relaxed = true)

    @Test
    fun `enabled update uses background installation without notification on success`() = runTest {
        val update = update(autoUpdate = true)
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update))
        coEvery { downloadManager.downloadAndInstall(any(), any(), any()) } returns DownloadResult.Success

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify {
            downloadManager.downloadAndInstall(
                "com.example.app",
                2,
                InstallationMode.BACKGROUND,
            )
        }
        verify(exactly = 0) { notifications.showUpdatesAvailableNotification(any()) }
    }

    @Test
    fun `disabled automatic update is left for actionable notification`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = false)))

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { downloadManager.downloadAndInstall(any(), any(), any()) }
        verify { notifications.showUpdatesAvailableNotification(1) }
    }

    @Test
    fun `missing non-interactive channel posts notification`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = true)))
        coEvery { downloadManager.downloadAndInstall(any(), any(), any()) } returns
            DownloadResult.UserActionRequired

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        verify { notifications.showUpdatesAvailableNotification(1) }
    }

    @Test
    fun `failed automatic installation retries background work`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = true)))
        coEvery { downloadManager.downloadAndInstall(any(), any(), any()) } returns
            DownloadResult.Failed("network")

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        verify(exactly = 0) { notifications.showUpdatesAvailableNotification(any()) }
    }

    private fun worker() = UpdateCheckWorker(
        appContext = mockk<Context>(relaxed = true),
        workerParams = mockk<WorkerParameters>(relaxed = true),
        updateChecker = updateChecker,
        downloadManager = downloadManager,
        notificationHelper = notifications,
    )

    private fun update(autoUpdate: Boolean) = AvailableUpdate(
        app = App(
            packageName = "com.example.app",
            name = "Example",
            description = null,
            iconUrl = "",
            latestVersion = AppVersion(
                versionCode = 2,
                versionName = "2",
                size = 1,
                sha256 = "hash",
                minSdk = 21,
                uploadedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        ),
        installedVersionCode = 1,
        installedVersionName = "1",
        autoUpdateEnabled = autoUpdate,
    )
}
