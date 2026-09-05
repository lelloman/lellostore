package com.lelloman.store.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal object RecoveryDeadlineScheduler {
    const val ACTION_CHECK_DEADLINE = "com.lelloman.store.recovery.action.CHECK_DEADLINE"

    fun schedule(context: Context, deadlineAtMillis: Long) {
        alarmManager(context).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            deadlineAtMillis,
            pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context))
    }

    private fun alarmManager(context: Context): AlarmManager =
        requireNotNull(context.getSystemService(AlarmManager::class.java))

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, RecoveryBootReceiver::class.java).setAction(ACTION_CHECK_DEADLINE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
