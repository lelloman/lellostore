package com.lelloman.store.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.download.DownloadFailureKind
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.download.DownloadProgress
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.domain.model.AvailableUpdate
import com.lelloman.store.domain.preferences.ReleaseChannel
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.notification.NotificationHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
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
    private val foregroundController: WorkerForegroundController = mockk(relaxed = true)
    private val downloadGate: AutomaticDownloadGate = mockk {
        every { deferUntilUnmetered() } returns false
    }

    init {
        every { downloadManager.activeDownloads } returns MutableStateFlow(emptyMap())
    }

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
        coVerify(exactly = 1) {
            foregroundController.setForeground(any(), any())
        }
        verify {
            notifications.cancelOperationNotification(
                NotificationHelper.BACKGROUND_UPDATE_NOTIFICATION_ID
            )
        }
    }

    @Test
    fun `disabled automatic update is left for actionable notification`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = false)))

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { downloadManager.downloadAndInstall(any(), any(), any()) }
        verify { notifications.showUpdatesAvailableNotification(listOf(ReleaseChannel.Stable)) }
    }

    @Test
    fun `missing non-interactive channel posts notification`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = true)))
        coEvery { downloadManager.downloadAndInstall(any(), any(), any()) } returns
            DownloadResult.UserActionRequired

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        verify { notifications.showUpdatesAvailableNotification(listOf(ReleaseChannel.Stable)) }
    }

    @Test
    fun `failed automatic installation retries background work and notifies`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = true)))
        coEvery { downloadManager.downloadAndInstall(any(), any(), any()) } returns
            DownloadResult.Failed("network")

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        verify { notifications.showUpdatesAvailableNotification(listOf(ReleaseChannel.Stable)) }
        verify(exactly = 0) { notifications.cancelUpdatesNotification() }
    }

    @Test
    fun `incompatible signature failure notifies without requesting retry`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = true)))
        coEvery { downloadManager.downloadAndInstall(any(), any(), any()) } returns
            DownloadResult.Failed("signature", DownloadFailureKind.INCOMPATIBLE_SIGNATURE)

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        verify { notifications.showUpdatesAvailableNotification(listOf(ReleaseChannel.Stable)) }
    }

    @Test
    fun `awaiting confirmation is treated as requiring user action`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = true)))
        coEvery { downloadManager.downloadAndInstall(any(), any(), any()) } returns
            DownloadResult.AwaitingConfirmation

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        verify { notifications.showUpdatesAvailableNotification(listOf(ReleaseChannel.Stable)) }
    }

    @Test
    fun `updates notification is cancelled when nothing remains`() = runTest {
        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        verify { notifications.cancelUpdatesNotification() }
        verify(exactly = 0) { notifications.showUpdatesAvailableNotification(any()) }
    }

    @Test
    fun `updates notification is cancelled after all automatic updates succeed`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = true)))
        coEvery { downloadManager.downloadAndInstall(any(), any(), any()) } returns DownloadResult.Success

        worker().doWork()

        verify { notifications.cancelUpdatesNotification() }
    }

    @Test
    fun `progress observer ignores foreground operations`() = runTest {
        val background = progress("com.example.app", InstallationMode.BACKGROUND)
        val foreground = progress("com.example.other", InstallationMode.FOREGROUND)
        every { downloadManager.activeDownloads } returns MutableStateFlow(
            mapOf(background.packageName to background, foreground.packageName to foreground),
        )
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = true)))
        coEvery { downloadManager.downloadAndInstall(any(), any(), any()) } coAnswers {
            delay(10)
            DownloadResult.Success
        }

        worker().doWork()

        verify {
            notifications.showOperationNotification(
                NotificationHelper.BACKGROUND_UPDATE_NOTIFICATION_ID,
                listOf(background),
            )
        }
        verify(exactly = 0) {
            notifications.showOperationNotification(any(), match { foreground in it })
        }
    }

    @Test
    fun `automatic update waits for unmetered network without notifying`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = true)))
        every { downloadGate.deferUntilUnmetered() } returns true

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { downloadManager.downloadAndInstall(any(), any(), any()) }
        coVerify(exactly = 0) { foregroundController.setForeground(any(), any()) }
        verify(exactly = 0) { notifications.showUpdatesAvailableNotification(any()) }
        verify(exactly = 0) { notifications.cancelUpdatesNotification() }
    }

    @Test
    fun `download gate is not consulted without automatic updates`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(listOf(update(autoUpdate = false)))

        worker().doWork()

        verify(exactly = 0) { downloadGate.deferUntilUnmetered() }
    }

    private fun worker() = UpdateCheckWorker(
        appContext = mockk<Context>(relaxed = true),
        workerParams = mockk<WorkerParameters>(relaxed = true),
        updateChecker = updateChecker,
        downloadManager = downloadManager,
        notificationHelper = notifications,
        workerForegroundController = foregroundController,
        automaticDownloadGate = downloadGate,
    )

    private fun progress(packageName: String, mode: InstallationMode) = DownloadProgress(
        packageName = packageName,
        progress = 0.5f,
        bytesDownloaded = 1,
        totalBytes = 2,
        state = DownloadState.DOWNLOADING,
        mode = mode,
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
