package com.lelloman.store.recovery

enum class RecoveryStatus {
    IDLE,
    AWAITING_HEALTH,
    HEALTHY,
    NEEDS_ATTENTION,
    REPAIRING,
    RECOVERED,
    MANUAL_RECOVERY,
}

data class RecoveryAttempt(
    val id: String,
    val packageName: String,
    val currentVersion: Int,
    val targetVersion: Int,
    val expectedSignerSha256: String,
    val startedAtMillis: Long,
    val deadlineAtMillis: Long,
    val status: RecoveryStatus,
    val destructiveAttempts: Int = 0,
    val lastReason: String? = null,
    val finishedAtMillis: Long? = null,
)

object RecoveryPolicy {
    const val MAX_DESTRUCTIVE_ATTEMPTS = 1
    const val RECOVERY_HEALTH_GRACE_MILLIS = 2 * 60_000L

    fun record(current: RecoveryAttempt?, incoming: RecoveryAttempt): RecoveryAttempt? {
        if (incoming.id.isBlank() || incoming.targetVersion <= incoming.currentVersion) return null
        if (incoming.deadlineAtMillis <= incoming.startedAtMillis) return null
        if (current?.status == RecoveryStatus.REPAIRING) return null
        return incoming.copy(status = RecoveryStatus.AWAITING_HEALTH)
    }

    fun acknowledge(current: RecoveryAttempt?, attemptId: String, installedVersion: Int, now: Long): RecoveryAttempt? {
        if (current == null || current.id != attemptId || installedVersion < current.targetVersion) return null
        if (current.status != RecoveryStatus.AWAITING_HEALTH && current.status != RecoveryStatus.NEEDS_ATTENTION) return null
        return current.copy(
            status = if (current.destructiveAttempts > 0) RecoveryStatus.RECOVERED else RecoveryStatus.HEALTHY,
            finishedAtMillis = now,
            lastReason = null,
        )
    }

    fun evaluate(current: RecoveryAttempt?, now: Long): RecoveryAttempt? =
        if (current?.status == RecoveryStatus.AWAITING_HEALTH && now > current.deadlineAtMillis) {
            current.copy(
                status = RecoveryStatus.NEEDS_ATTENTION,
                lastReason = "LelloStore did not acknowledge health before the update deadline",
            )
        } else {
            current
        }

    fun cancelUnreplaced(
        current: RecoveryAttempt?,
        attemptId: String,
        installedVersion: Int,
        reason: String,
        now: Long,
    ): RecoveryAttempt? {
        if (current == null || current.id != attemptId || current.status != RecoveryStatus.AWAITING_HEALTH) return null
        if (installedVersion != current.currentVersion) return null
        return current.copy(
            status = RecoveryStatus.HEALTHY,
            lastReason = "Update did not replace LelloStore: $reason",
            finishedAtMillis = now,
        )
    }

    fun beginExplicitRepair(current: RecoveryAttempt?): RecoveryAttempt? {
        if (current == null || current.status != RecoveryStatus.NEEDS_ATTENTION) return null
        if (current.destructiveAttempts >= MAX_DESTRUCTIVE_ATTEMPTS) return null
        return current.copy(
            status = RecoveryStatus.REPAIRING,
            destructiveAttempts = current.destructiveAttempts + 1,
            lastReason = "Explicit destructive repair approved by the user",
        )
    }

    fun finishRepair(current: RecoveryAttempt, success: Boolean, reason: String, now: Long): RecoveryAttempt =
        current.copy(
            status = if (success) RecoveryStatus.AWAITING_HEALTH else RecoveryStatus.MANUAL_RECOVERY,
            lastReason = reason,
            finishedAtMillis = now.takeUnless { success },
            deadlineAtMillis = if (success) now + RECOVERY_HEALTH_GRACE_MILLIS else current.deadlineAtMillis,
        )
}
