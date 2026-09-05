package com.lelloman.store.recovery

import android.content.Context
import androidx.core.content.edit

class RecoveryAttemptStore(context: Context) {
    private val preferences = context.getSharedPreferences("recovery-state-v1", Context.MODE_PRIVATE)

    @Synchronized
    fun read(): RecoveryAttempt? {
        val id = preferences.getString("id", null) ?: return null
        return RecoveryAttempt(
            id = id,
            packageName = preferences.getString("package", "") ?: "",
            currentVersion = preferences.getInt("currentVersion", -1),
            targetVersion = preferences.getInt("targetVersion", -1),
            expectedSignerSha256 = preferences.getString("signer", "") ?: "",
            startedAtMillis = preferences.getLong("startedAt", 0),
            deadlineAtMillis = preferences.getLong("deadlineAt", 0),
            status = runCatching {
                RecoveryStatus.valueOf(preferences.getString("status", RecoveryStatus.IDLE.name)!!)
            }.getOrDefault(RecoveryStatus.MANUAL_RECOVERY),
            destructiveAttempts = preferences.getInt("destructiveAttempts", 0),
            lastReason = preferences.getString("lastReason", null),
            finishedAtMillis = preferences.getLong("finishedAt", -1).takeIf { it >= 0 },
        )
    }

    @Synchronized
    fun write(attempt: RecoveryAttempt) {
        preferences.edit {
            putString("id", attempt.id)
            putString("package", attempt.packageName)
            putInt("currentVersion", attempt.currentVersion)
            putInt("targetVersion", attempt.targetVersion)
            putString("signer", attempt.expectedSignerSha256)
            putLong("startedAt", attempt.startedAtMillis)
            putLong("deadlineAt", attempt.deadlineAtMillis)
            putString("status", attempt.status.name)
            putInt("destructiveAttempts", attempt.destructiveAttempts)
            putString("lastReason", attempt.lastReason)
            putLong("finishedAt", attempt.finishedAtMillis ?: -1)
        }
    }
}
