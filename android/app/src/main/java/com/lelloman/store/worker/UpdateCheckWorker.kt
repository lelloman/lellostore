package com.lelloman.store.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.domain.download.DownloadFailureKind
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
    private val automaticDownloadGate: AutomaticDownloadGate,
    private val session: com.lelloman.store.domain.config.StoreSession = com.lelloman.store.domain.config.StoreSession(),
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        session.use { performWork() }
    } catch (error: CancellationException) { throw error }
    catch (_: IllegalStateException) { Result.retry() }

    private suspend fun performWork(): Result = coroutineScope {
        try {
            val result = updateChecker.checkForUpdates()
            result.fold(
                onSuccess = { updates ->
                    var retryNeeded = false
                    val remainingUpdates = updates.filterNot { it.autoUpdateEnabled }.toMutableList()
                    // Deferred updates stay visible in the Updates screen; the queued
                    // unmetered check installs them, so no notification is posted.
                    val enabledUpdates = updates.filter { it.autoUpdateEnabled }
                    val deferred = enabledUpdates.isNotEmpty() && automaticDownloadGate.deferUntilUnmetered()
                    val automaticUpdates = if (deferred) emptyList() else enabledUpdates
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
                                // User-started operations are rendered by the download service.
                                val active = downloads.values.filter {
                                    it.mode == InstallationMode.BACKGROUND && it.state.isInProgress
                                }
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
                            when (val outcome = downloadManager.downloadAndInstall(
                                packageName = update.app.packageName,
                                versionCode = update.app.latestVersion.versionCode,
                                installationMode = InstallationMode.BACKGROUND,
                            )) {
                                DownloadResult.Success -> Unit
                                // Not expected in BACKGROUND mode; the user still has to act.
                                DownloadResult.AwaitingConfirmation,
                                DownloadResult.UserActionRequired,
                                DownloadResult.PermissionRequired -> remainingUpdates += update
                                DownloadResult.Cancelled -> retryNeeded = true
                                is DownloadResult.Failed -> {
                                    // Surface the update so a failure is never silent.
                                    remainingUpdates += update
                                    // A signature mismatch cannot succeed on retry.
                                    if (outcome.kind != DownloadFailureKind.INCOMPATIBLE_SIGNATURE) retryNeeded = true
                                }
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
                    } else if (!deferred) {
                        // Nothing is left to act on. Deferred updates are still pending, so a
                        // notification posted earlier is left alone.
                        notificationHelper.cancelUpdatesNotification()
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
        const val UNMETERED_WORK_NAME = "unmetered_update_check_work"
        private const val MAX_RETRIES = 3
    }
}
