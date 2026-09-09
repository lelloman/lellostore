package com.lelloman.store.download

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.download.isInProgress
import com.lelloman.store.notification.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadForegroundService : Service() {

    @Inject
    lateinit var downloadManager: DownloadManager

    @Inject
    lateinit var notificationHelper: NotificationHelper

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observationJob: Job? = null
    private var observedAnOperation = false

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            NotificationHelper.DOWNLOAD_SERVICE_NOTIFICATION_ID,
            notificationHelper.buildOperationNotification(emptyList()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        observationJob = serviceScope.launch {
            downloadManager.activeDownloads.collectLatest { downloads ->
                val active = downloads.values.filter { it.state.isInProgress }
                if (active.isNotEmpty()) {
                    observedAnOperation = true
                    notificationHelper.showOperationNotification(
                        NotificationHelper.DOWNLOAD_SERVICE_NOTIFICATION_ID,
                        active,
                    )
                } else if (observedAnOperation) {
                    ServiceCompat.stopForeground(
                        this@DownloadForegroundService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        downloadManager.activeDownloads.value.keys.forEach(downloadManager::cancelDownload)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        observationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
