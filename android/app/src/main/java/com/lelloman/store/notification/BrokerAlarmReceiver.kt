package com.lelloman.store.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BrokerAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var runtime: NotificationBrokerRuntime
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LelloStore:notification-heartbeat")
        lock.acquire(10_000)
        CoroutineScope(Dispatchers.IO).launch {
            try { kotlinx.coroutines.withTimeout(9_000) { runtime.pulse() } }
            catch (_: Exception) { runtime.interruptedPulse() }
            finally { if (lock.isHeld) lock.release(); pending.finish() }
        }
    }
}
