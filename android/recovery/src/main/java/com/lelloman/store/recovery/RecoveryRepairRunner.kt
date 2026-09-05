package com.lelloman.store.recovery

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** An activity rotation must not abandon a repair after the package was uninstalled. */
internal object RecoveryRepairRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, attempt: RecoveryAttempt) = scope.launch {
        val application = context.applicationContext
        val engine = RecoveryEngine(application)
        val result = engine.repair(attempt)
        val finished = RecoveryPolicy.finishRepair(
            attempt,
            success = result.isSuccess,
            reason = result.fold({ it }, { it.message ?: "Unknown recovery error" }),
            now = System.currentTimeMillis(),
        )
        RecoveryAttemptStore(application).write(finished)
        if (finished.status == RecoveryStatus.AWAITING_HEALTH) {
            RecoveryDeadlineScheduler.schedule(application, finished.deadlineAtMillis)
            runCatching { engine.launchStore() }
        } else {
            RecoveryDeadlineScheduler.cancel(application)
        }
    }
}
