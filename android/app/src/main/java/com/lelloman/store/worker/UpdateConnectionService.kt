package com.lelloman.store.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lelloman.store.MainActivity
import com.lelloman.store.R
import com.lelloman.store.di.ApplicationScope
import com.lelloman.store.domain.preferences.UserPreferencesStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Foreground protection for the single connection owned by the lifecycle observer. */
@Singleton
class UpdateConnectionServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutableRunning = MutableStateFlow(false)
    val running = mutableRunning.asStateFlow()
    private var requested = false

    fun start() {
        if (requested) return
        ContextCompat.startForegroundService(context, Intent(context, UpdateConnectionService::class.java))
        requested = true
    }

    fun stop() {
        requested = false
        context.stopService(Intent(context, UpdateConnectionService::class.java))
    }

    fun onStarted() { mutableRunning.value = true }
    fun onStopped() {
        requested = false
        mutableRunning.value = false
    }
}

@AndroidEntryPoint
class UpdateConnectionService : Service() {
    @Inject lateinit var controller: UpdateConnectionServiceController
    @Inject lateinit var preferences: UserPreferencesStore
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, getString(R.string.notification_connection_channel), NotificationManager.IMPORTANCE_LOW,
            ))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, UpdateConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notification_connection_title))
            .setContentText(getString(R.string.notification_connection_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notification_connection_stop), stop)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        controller.onStarted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { preferences.setKeepUpdateConnection(false) }
        }
        // Reopen the app to resume after the system stops the service.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller.onStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "update_connection"
        const val NOTIFICATION_ID = 1004
        const val ACTION_STOP = "com.lelloman.store.STOP_UPDATE_CONNECTION"
    }
}
