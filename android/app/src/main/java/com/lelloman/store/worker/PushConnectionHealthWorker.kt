package com.lelloman.store.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.notification.NotificationBrokerRuntime
import com.lelloman.store.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

class PushConnectionHealthCheck @Inject constructor(
    private val preferences: UserPreferencesStore,
    private val auth: AuthStore,
    private val broker: NotificationBrokerRuntime,
    private val service: UpdateConnectionServiceController,
    private val notifications: NotificationHelper,
) {
    /** Returns false when a transient failure needs another attempt. */
    suspend fun check(): Boolean {
        if (!preferences.readKeepUpdateConnection()) {
            notifications.cancelPushConnectionReminder()
            return true
        }
        val state = withTimeoutOrNull(10_000) {
            auth.authState.first { it !is AuthState.Loading }
        } ?: return false
        if (state !is AuthState.Authenticated) {
            notifications.cancelPushConnectionReminder()
            return true
        }
        if (!broker.hasBatteryExemption()) {
            notifications.showPushConnectionReminder(batteryExemptionRequired = true)
            return true
        }
        if (!service.running.value) {
            try {
                service.start()
            } catch (_: RuntimeException) {
                notifications.showPushConnectionReminder(batteryExemptionRequired = false)
                return false
            }
            val started = withTimeoutOrNull(10_000) { service.running.first { it } }
            if (started != true) {
                notifications.showPushConnectionReminder(batteryExemptionRequired = false)
                return false
            }
        }
        notifications.cancelPushConnectionReminder()
        return true
    }
}

@HiltWorker
class PushConnectionHealthWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val healthCheck: PushConnectionHealthCheck,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result =
        if (healthCheck.check()) Result.success() else Result.retry()

    companion object {
        const val WORK_NAME = "push_connection_health"
    }
}
