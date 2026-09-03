package com.lelloman.store.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val updateChecker: UpdateChecker,
    private val downloadManager: DownloadManager,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val result = updateChecker.checkForUpdates()
            result.fold(
                onSuccess = { updates ->
                    var retryNeeded = false
                    val remainingUpdates = updates.filterNot { it.autoUpdateEnabled }.toMutableList()
                    updates.filter { it.autoUpdateEnabled }.forEach { update ->
                        when (downloadManager.downloadAndInstall(
                            packageName = update.app.packageName,
                            versionCode = update.app.latestVersion.versionCode,
                            installationMode = InstallationMode.BACKGROUND,
                        )) {
                            DownloadResult.Success -> Unit
                            DownloadResult.UserActionRequired,
                            DownloadResult.PermissionRequired -> remainingUpdates += update
                            DownloadResult.Cancelled,
                            is DownloadResult.Failed -> retryNeeded = true
                        }
                    }
                    if (remainingUpdates.isNotEmpty()) {
                        notificationHelper.showUpdatesAvailableNotification(
                            remainingUpdates.map { it.effectiveReleaseChannel },
                        )
                    }
                    when {
                        !retryNeeded -> Result.success()
                        runAttemptCount < MAX_RETRIES -> Result.retry()
                        else -> Result.failure()
                    }
                },
                onFailure = {
                    if (runAttemptCount < MAX_RETRIES) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                },
            )
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "update_check_work"
        private const val MAX_RETRIES = 3
    }
}
