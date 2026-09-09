package com.lelloman.store.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lelloman.store.di.ApplicationScope
import com.lelloman.store.domain.preferences.UpdateCheckInterval
import com.lelloman.store.domain.preferences.UserPreferencesStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesStore: UserPreferencesStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val workManager = WorkManager.getInstance(context)

    fun initialize() {
        // Observe interval changes and reschedule work accordingly
        scope.launch {
            userPreferencesStore.updateCheckInterval
                .collect { interval ->
                    scheduleUpdateCheck(interval)
                }
        }
    }

    fun enqueueImmediateUpdateCheck() {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(networkConstraints())
            .build()

        workManager.enqueueUniqueWork(
            UpdateCheckWorker.IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun scheduleUpdateCheck(interval: UpdateCheckInterval) {
        if (interval == UpdateCheckInterval.Manual) {
            // Cancel any existing periodic work when set to Manual
            workManager.cancelUniqueWork(UpdateCheckWorker.WORK_NAME)
            return
        }

        val intervalHours = when (interval) {
            UpdateCheckInterval.Hours6 -> 6L
            UpdateCheckInterval.Hours12 -> 12L
            UpdateCheckInterval.Hours24 -> 24L
            UpdateCheckInterval.Manual -> return // Already handled above
        }

        val updateCheckRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            repeatInterval = intervalHours,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
        )
            .setConstraints(networkConstraints())
            .build()

        // Use REPLACE to update the interval if it changed
        workManager.enqueueUniquePeriodicWork(
            UpdateCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            updateCheckRequest,
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
