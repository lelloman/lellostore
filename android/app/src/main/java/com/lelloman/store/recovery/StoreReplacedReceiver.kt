package com.lelloman.store.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The independent ADB connection starts Store so the replacement UI can acknowledge health. */
class StoreReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RecoveryCompanionClient(context.applicationContext).startAfterReplacement()
            } finally {
                pending.finish()
            }
        }
    }
}
