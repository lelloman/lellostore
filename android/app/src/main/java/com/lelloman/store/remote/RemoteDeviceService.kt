package com.lelloman.store.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lelloman.store.MainActivity
import com.lelloman.store.domain.remote.RemoteOperationPhase
import com.lelloman.store.ui.R
import com.lelloman.store.ui.screen.pesce.remoteProgressText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

@AndroidEntryPoint
class RemoteDeviceService : Service() {
    @Inject lateinit var manager: RemoteDeviceManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionGeneration = -1
    private var latestStartId = 0
    private var observing = false
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.pesce_title), NotificationManager.IMPORTANCE_LOW))
    }
    private fun updateNotification() {
        val state = manager.state.value
        val stop = PendingIntent.getService(this, 1, Intent(this, RemoteDeviceService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("open_pesce", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pesce)
            .setContentTitle(getString(R.string.pesce_title))
            .setContentText(remoteProgressText(this, state))
            .setSubText(listOfNotNull(state.receiver?.model, state.activeApp).joinToString(" · "))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (state.busy) setProgress(100, ((state.transferProgress ?: 0f) * 100).toInt(), state.transferProgress == null)
            }
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, getString(R.string.pesce_stop), stop).build()
        val types = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (state.operation == RemoteOperationPhase.DOWNLOADING) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0) else 0
        ServiceCompat.startForeground(this, NOTIFICATION, notification, types)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every foreground start must be promoted, even if USB failed or disconnected
        // before Android delivered it, or this service instance was already running.
        updateNotification()
        latestStartId = startId
        if (intent?.action == STOP) manager.disconnect()
        else {
            sessionGeneration = intent?.getIntExtra(SESSION_GENERATION, -1) ?: -1
            manager.serviceStartDelivered(sessionGeneration)
        }
        if (!observing) {
            observing = true
            scope.launch {
                manager.state.collectLatest {
                    if (!stopIfIdle()) updateNotification()
                }
            }
        } else stopIfIdle()
        return START_NOT_STICKY
    }
    private fun stopIfIdle() = manager.stopServiceIfIdle { stopSelfResult(latestStartId) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { manager.cancel(); stopIfIdle() }
    override fun onDestroy() {
        scope.cancel()
        manager.serviceStopped(sessionGeneration)
        super.onDestroy()
    }
    companion object {
        internal const val SESSION_GENERATION = "session_generation"
        private const val CHANNEL = "remote-device"
        private const val NOTIFICATION = 7301
        private const val STOP = "stop-remote-device"
    }
}
