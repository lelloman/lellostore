package com.lelloman.store.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.domain.download.isInProgress
import com.lelloman.store.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val updateChecker: UpdateChecker,
    private val downloadManager: DownloadManager,
    private val notificationHelper: NotificationHelper,
    private val workerForegroundController: WorkerForegroundController,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = coroutineScope {
        try {
            val result = updateChecker.checkForUpdates()
            result.fold(
                onSuccess = { updates ->
                    var retryNeeded = false
                    val remainingUpdates = updates.filterNot { it.autoUpdateEnabled }.toMutableList()
                    val automaticUpdates = updates.filter { it.autoUpdateEnabled }
                    val progressObserver = if (automaticUpdates.isNotEmpty()) {
                        workerForegroundController.setForeground(
                            this@UpdateCheckWorker,
                            notificationHelper.operationForegroundInfo(
                                NotificationHelper.BACKGROUND_UPDATE_NOTIFICATION_ID,
                                emptyList(),
                            )
                        )
                        launch {
                            downloadManager.activeDownloads.collectLatest { downloads ->
                                val active = downloads.values.filter { it.state.isInProgress }
                                if (active.isNotEmpty()) {
                                    notificationHelper.showOperationNotification(
                                        NotificationHelper.BACKGROUND_UPDATE_NOTIFICATION_ID,
                                        active,
                                    )
                                }
                            }
                        }
                    } else null
                    try {
                        automaticUpdates.forEach { update ->
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
                    } finally {
                        progressObserver?.cancelAndJoin()
                        notificationHelper.cancelOperationNotification(
                            NotificationHelper.BACKGROUND_UPDATE_NOTIFICATION_ID
                        )
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
        } catch (e: CancellationException) {
            throw e
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
        const val IMMEDIATE_WORK_NAME = "immediate_update_check_work"
        private const val MAX_RETRIES = 3
    }
}
