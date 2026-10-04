package com.lelloman.store.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.ForegroundInfo
import com.lelloman.store.MainActivity
import com.lelloman.store.R
import com.lelloman.store.domain.download.DownloadProgress
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.preferences.ReleaseChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val notificationManager = NotificationManagerCompat.from(context)
    private val appNames = LruCache<String, String>(64)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        UPDATES_CHANNEL_ID,
                        context.getString(R.string.notification_channel_updates),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.notification_channel_updates_description)
                    },
                    NotificationChannel(
                        PUSH_REMINDERS_CHANNEL_ID,
                        context.getString(R.string.notification_push_reminder_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                    NotificationChannel(
                        OPERATIONS_CHANNEL_ID,
                        context.getString(R.string.notification_channel_operations),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.notification_channel_operations_description)
                    },
                )
            )
        }
    }

    @SuppressLint("MissingPermission") // Permission is checked via hasNotificationPermission()
    fun showUpdatesAvailableNotification(channels: List<ReleaseChannel>) {
        if (!hasNotificationPermission()) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO_UPDATES, true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = formatUpdateTitle(channels)

        val notification = NotificationCompat.Builder(context, UPDATES_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("Tap to view and install updates")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(UPDATES_NOTIFICATION_ID, notification)
    }

    fun cancelUpdatesNotification() {
        notificationManager.cancel(UPDATES_NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission", "BatteryLife") // Permission checked; shared push needs Doze access.
    fun showPushConnectionReminder(batteryExemptionRequired: Boolean) {
        if (!hasNotificationPermission()) return
        val intent = if (batteryExemptionRequired) {
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${context.packageName}".toUri())
        } else {
            Intent(context, MainActivity::class.java)
        }
        val pendingIntent = PendingIntent.getActivity(context, PUSH_REMINDER_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = context.getString(if (batteryExemptionRequired)
            R.string.notification_push_battery_text else R.string.notification_push_stopped_text)
        val notification = NotificationCompat.Builder(context, PUSH_REMINDERS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.notification_push_reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TimeUnit.HOURS.toMillis(1))
            .build()
        notificationManager.notify(PUSH_REMINDER_NOTIFICATION_ID, notification)
    }

    /** Separate ID so background health checks cannot overwrite the startup warning. */
    @SuppressLint("MissingPermission")
    fun showStartupBatteryWarning(): Boolean {
        if (context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) {
            cancelStartupBatteryWarning()
            return true
        }
        if (!hasNotificationPermission() || !notificationManager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26 && notificationManager.getNotificationChannel(PUSH_REMINDERS_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = Intent(context, MainActivity::class.java).putExtra("open_battery_settings", true)
        val pending = PendingIntent.getActivity(context, STARTUP_BATTERY_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = context.getString(com.lelloman.store.ui.R.string.push_battery_warning_text)
        notificationManager.notify(STARTUP_BATTERY_NOTIFICATION_ID,
            NotificationCompat.Builder(context, PUSH_REMINDERS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(com.lelloman.store.ui.R.string.push_battery_warning_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .addAction(0, context.getString(com.lelloman.store.ui.R.string.push_battery_open_settings), pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(60_000)
                .build())
        // Android 24/25 do not implement notification timeoutAfter.
        startupWarningHandler.removeCallbacks(startupWarningTimeout)
        startupWarningHandler.postDelayed(startupWarningTimeout, 60_000)
        return true
    }

    private val startupWarningHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val startupWarningTimeout = Runnable { cancelStartupBatteryWarning() }

    fun cancelStartupBatteryWarning() {
        startupWarningHandler.removeCallbacks(startupWarningTimeout)
        notificationManager.cancel(STARTUP_BATTERY_NOTIFICATION_ID)
    }

    fun cancelPushConnectionReminder() {
        notificationManager.cancel(PUSH_REMINDER_NOTIFICATION_ID)
    }

    fun buildOperationNotification(progresses: Collection<DownloadProgress>): Notification {
        val progress = progresses.singleOrNull()
        val title = when {
            progresses.isEmpty() -> context.getString(R.string.operation_preparing_update)
            progress != null -> operationStateLabel(progress)
            else -> context.resources.getQuantityString(
                R.plurals.operation_updating_apps,
                progresses.size,
                progresses.size,
            )
        }
        val text = when {
            progresses.isEmpty() -> context.getString(R.string.operation_starting)
            progress != null -> appName(progress.packageName)
            else -> progresses.joinToString(limit = 2) { appName(it.packageName) }
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            OPERATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, OPERATIONS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(contentIntent)

        if (progress?.state == DownloadState.DOWNLOADING && progress.totalBytes > 0) {
            builder.setProgress(PROGRESS_MAX, (progress.progress * PROGRESS_MAX).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (progresses.size > 1) {
            builder.setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    progresses.forEach { item ->
                        style.addLine("${appName(item.packageName)} — ${operationStateLabel(item)}")
                    }
                }
            )
        }
        return builder.build()
    }

    private fun appName(packageName: String): String {
        appNames.get(packageName)?.let { return it }
        val name = try {
            val packageManager = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        // Avoid repeatedly loading application resources for every progress notification.
        if (name != null) appNames.put(packageName, name)
        return name ?: packageName
    }

    @SuppressLint("MissingPermission") // Foreground-operation notifications are required by Android.
    fun showOperationNotification(id: Int, progresses: Collection<DownloadProgress>) {
        notificationManager.notify(id, buildOperationNotification(progresses))
    }

    fun cancelOperationNotification(id: Int) {
        notificationManager.cancel(id)
    }

    fun operationForegroundInfo(
        id: Int,
        progresses: Collection<DownloadProgress>,
    ): ForegroundInfo = ForegroundInfo(
        id,
        buildOperationNotification(progresses),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        },
    )

    private fun operationStateLabel(progress: DownloadProgress): String = when (progress.state) {
        DownloadState.PENDING -> context.getString(R.string.operation_preparing)
        DownloadState.DOWNLOADING -> context.getString(
            R.string.operation_downloading,
            (progress.progress * PROGRESS_MAX).toInt(),
        )
        DownloadState.VERIFYING -> context.getString(R.string.operation_verifying)
        DownloadState.INSTALLING -> context.getString(R.string.operation_installing)
        DownloadState.COMPLETED -> context.getString(R.string.operation_completed)
        DownloadState.FAILED -> context.getString(R.string.operation_failed)
        DownloadState.CANCELLED -> context.getString(R.string.operation_cancelled)
        DownloadState.PERMISSION_REQUIRED -> context.getString(R.string.operation_permission_required)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        const val UPDATES_CHANNEL_ID = "updates_channel"
        const val OPERATIONS_CHANNEL_ID = "operations_channel"
        const val PUSH_REMINDERS_CHANNEL_ID = "push_connection_reminders"
        internal const val STARTUP_BATTERY_NOTIFICATION_ID = 1006
        private const val PUSH_REMINDER_NOTIFICATION_ID = 1005
        const val UPDATES_NOTIFICATION_ID = 1001
        const val BACKGROUND_UPDATE_NOTIFICATION_ID = 1002
        const val DOWNLOAD_SERVICE_NOTIFICATION_ID = 1003
        const val EXTRA_NAVIGATE_TO_UPDATES = "navigate_to_updates"
        private const val OPERATION_REQUEST_CODE = 1002
        private const val PROGRESS_MAX = 100

        internal fun formatUpdateTitle(channels: List<ReleaseChannel>): String {
            val count = channels.size
            val channelLabel = when (channels.toSet()) {
                setOf(ReleaseChannel.Stable) -> "stable"
                setOf(ReleaseChannel.Beta) -> "beta"
                else -> "stable and beta"
            }
            val updateLabel = if (count == 1) "update" else "updates"
            return "$count $channelLabel $updateLabel available"
        }
    }
}
