package com.lelloman.store.enrollment

import com.lelloman.store.domain.preferences.UpdateCheckInterval
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.logger.Logger
import javax.inject.Inject

/**
 * What a freshly enrolled device turns on, so that a new box keeps its apps current without a
 * trip through Settings: automatic updates (with periodic checks, if they were off) and the
 * persistent update connection. Permissions these need are asked for by the completion screen.
 */
class EnrolledDeviceSetup @Inject constructor(
    private val preferences: UserPreferencesStore,
    private val logger: Logger,
) {
    data class Applied(val updateCheckInterval: UpdateCheckInterval)

    suspend fun apply(): Applied {
        preferences.setAutoUpdateDefault(true)
        var interval = preferences.updateCheckInterval.value
        if (interval == UpdateCheckInterval.Manual) {
            interval = UpdateCheckInterval.Hours24
            preferences.setUpdateCheckInterval(interval)
        }
        preferences.setKeepUpdateConnection(true)
        logger.audit("enrollment.updates_enabled", mapOf("interval" to interval.name))
        return Applied(interval)
    }
}
