package com.lelloman.store.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RecoveryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != RecoveryDeadlineScheduler.ACTION_CHECK_DEADLINE
        ) return
        val store = RecoveryAttemptStore(context)
        val evaluated = RecoveryPolicy.evaluate(store.read(), System.currentTimeMillis())
        evaluated?.let(store::write)
        if (evaluated?.status == RecoveryStatus.AWAITING_HEALTH) {
            RecoveryDeadlineScheduler.schedule(context, evaluated.deadlineAtMillis)
        } else {
            RecoveryDeadlineScheduler.cancel(context)
        }
    }
}
