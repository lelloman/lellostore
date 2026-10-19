package com.lelloman.store.notification

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.lelloman.store.di.ApplicationScope
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.download.DownloadProgress
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.download.InstallationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a notification when a user-started (FOREGROUND) operation finishes while the app is not
 * visible. The foreground service removes its ongoing notification when the operation ends, so
 * without this a failure would go unnoticed.
 */
@Singleton
class OperationOutcomeNotifier @Inject constructor(
    private val downloadManager: DownloadManager,
    private val notificationHelper: NotificationHelper,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var previous: Map<String, DownloadProgress>? = null
            downloadManager.activeDownloads.collect { current ->
                // The first emission is a baseline: anything already terminal finished earlier.
                val outcomes = previous?.let { detectOutcomes(it, current) }.orEmpty()
                previous = current
                if (outcomes.isNotEmpty() && !isProcessVisible()) {
                    outcomes.forEach(notificationHelper::showOperationOutcomeNotification)
                }
            }
        }
    }

    private suspend fun isProcessVisible(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    companion object {
        private val OUTCOME_STATES = setOf(DownloadState.COMPLETED, DownloadState.FAILED)

        /** FOREGROUND entries in [current] that just entered COMPLETED or FAILED. */
        internal fun detectOutcomes(
            previous: Map<String, DownloadProgress>,
            current: Map<String, DownloadProgress>,
        ): List<DownloadProgress> = current.values.filter { progress ->
            progress.mode == InstallationMode.FOREGROUND &&
                progress.state in OUTCOME_STATES &&
                previous[progress.packageName]?.state != progress.state
        }
    }
}
