package com.lelloman.store.notification

import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.logger.Logger
import com.lelloman.store.worker.UpdateConnectionServiceController
import com.lelloman.store.worker.WorkManagerInitializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Restore foreground protection promptly; the lifecycle observer waits for authentication. */
class PushConnectionRestorer @Inject constructor(
    private val preferences: UserPreferencesStore,
    private val broker: NotificationBrokerRuntime,
    private val service: UpdateConnectionServiceController,
    private val work: WorkManagerInitializer,
    private val logger: Logger,
) {
    suspend fun restore(trigger: String) {
        val fields = mapOf("trigger" to trigger)
        logger.audit("push.restore_requested", fields)
        // Persist a retry before attempting startup, including when preferences are slow to load.
        work.enqueuePushConnectionRecovery()
        try {
            val enabled = withTimeoutOrNull(5_000) { preferences.readKeepUpdateConnection() }
            if (enabled != true) {
                logger.audit("push.restore_deferred", fields + mapOf("reason" to if (enabled == null) "preferences_timeout" else "disabled"))
                return
            }
            if (!broker.hasBatteryExemption()) {
                logger.audit("push.restore_deferred", fields + mapOf("reason" to "battery_exemption_required"))
                return
            }
            service.start()
            logger.audit("push.restore_start_requested", fields)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.audit("push.restore_failed", fields + mapOf("error_type" to error.javaClass.simpleName))
        }
    }
}
