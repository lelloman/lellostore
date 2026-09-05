package com.lelloman.store.recovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecoveryPolicyTest {
    private val attempt = RecoveryAttempt(
        id = "attempt-1",
        packageName = "com.lelloman.store",
        currentVersion = 1,
        targetVersion = 2,
        expectedSignerSha256 = "abc",
        startedAtMillis = 100,
        deadlineAtMillis = 200,
        status = RecoveryStatus.AWAITING_HEALTH,
    )

    @Test
    fun `matching health acknowledgement completes attempt`() {
        assertThat(RecoveryPolicy.acknowledge(attempt, "attempt-1", 2, 150)?.status)
            .isEqualTo(RecoveryStatus.HEALTHY)
    }

    @Test
    fun `health acknowledgement after repair records recovery`() {
        val repaired = attempt.copy(destructiveAttempts = 1)
        assertThat(RecoveryPolicy.acknowledge(repaired, "attempt-1", 1, 150)?.status)
            .isEqualTo(RecoveryStatus.RECOVERED)
    }

    @Test
    fun `new attempts cannot overwrite unresolved or recovered attempts`() {
        for (status in listOf(RecoveryStatus.AWAITING_HEALTH, RecoveryStatus.NEEDS_ATTENTION,
            RecoveryStatus.REPAIRING, RecoveryStatus.RECOVERED, RecoveryStatus.MANUAL_RECOVERY)) {
            assertThat(RecoveryPolicy.record(attempt.copy(status = status), attempt.copy(id = "new"))).isNull()
        }
        assertThat(RecoveryPolicy.record(attempt.copy(status = RecoveryStatus.HEALTHY), attempt.copy(id = "new"))).isNotNull()
    }

    @Test
    fun `interrupted repair and unhealthy restored version require manual recovery`() {
        assertThat(RecoveryPolicy.evaluate(attempt.copy(status = RecoveryStatus.REPAIRING), 150)?.status)
            .isEqualTo(RecoveryStatus.MANUAL_RECOVERY)
        assertThat(RecoveryPolicy.evaluate(attempt.copy(destructiveAttempts = 1), 200)?.status)
            .isEqualTo(RecoveryStatus.MANUAL_RECOVERY)
    }

    @Test
    fun `stale or wrong-version acknowledgement is rejected`() {
        assertThat(RecoveryPolicy.acknowledge(attempt, "other", 2, 150)).isNull()
        assertThat(RecoveryPolicy.acknowledge(attempt, "attempt-1", 1, 150)).isNull()
    }

    @Test
    fun `missed deadline needs attention but never starts destructive repair`() {
        val result = RecoveryPolicy.evaluate(attempt, 201)
        assertThat(result?.status).isEqualTo(RecoveryStatus.NEEDS_ATTENTION)
        assertThat(result?.destructiveAttempts).isEqualTo(0)
    }

    @Test
    fun `destructive repair is explicit and bounded to one attempt`() {
        val needsAttention = attempt.copy(status = RecoveryStatus.NEEDS_ATTENTION)
        val repairing = RecoveryPolicy.beginExplicitRepair(needsAttention)
        assertThat(repairing?.status).isEqualTo(RecoveryStatus.REPAIRING)
        assertThat(repairing?.destructiveAttempts).isEqualTo(1)
        assertThat(RecoveryPolicy.beginExplicitRepair(needsAttention.copy(destructiveAttempts = 1))).isNull()
    }

    @Test
    fun `successful repair waits for health without being marked finished`() {
        val repairing = attempt.copy(status = RecoveryStatus.REPAIRING, destructiveAttempts = 1)
        val result = RecoveryPolicy.finishRepair(repairing, true, "installed", 300)
        assertThat(result.status).isEqualTo(RecoveryStatus.AWAITING_HEALTH)
        assertThat(result.finishedAtMillis).isNull()
        assertThat(result.deadlineAtMillis).isEqualTo(300 + RecoveryPolicy.RECOVERY_HEALTH_GRACE_MILLIS)
    }

    @Test
    fun `rejected update closes attempt when old version remains installed`() {
        val result = RecoveryPolicy.cancelUnreplaced(attempt, "attempt-1", 1, "install rejected", 160)
        assertThat(result?.status).isEqualTo(RecoveryStatus.HEALTHY)
        assertThat(result?.lastReason).contains("install rejected")
    }
}
