package com.lelloman.store.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.worker.UpdateConnectionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.auth.AuthState
import javax.inject.Inject

@AndroidEntryPoint
class BrokerBootReceiver : BroadcastReceiver() {
    @Inject lateinit var auth: AuthStore
    @Inject lateinit var preferences: UserPreferencesStore
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val optedIn = withTimeout(5_000) {
                    val keep = preferences.readKeepUpdateConnection()
                    preferences.keepUpdateConnection.first { it == keep }
                    keep && auth.authState.first { it !is AuthState.Loading } is AuthState.Authenticated
                }
                if (optedIn && context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) {
                    runCatching { ContextCompat.startForegroundService(context, Intent(context, UpdateConnectionService::class.java)) }
                }
            } finally { pending.finish() }
        }
    }
}
