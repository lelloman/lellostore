package com.lelloman.store.worker

import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import javax.inject.Inject

class WorkerForegroundController @Inject constructor() {
    suspend fun setForeground(worker: CoroutineWorker, foregroundInfo: ForegroundInfo) {
        worker.setForeground(foregroundInfo)
    }
}
