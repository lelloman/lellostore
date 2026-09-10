package com.lelloman.store.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WarmPollingScheduleTest {
    @Test
    fun `increasing delays fit ten checks into one hour`() {
        val deadline = WarmUpdateScheduler.WARM_PERIOD_MILLIS
        var now = 0L
        val elapsedMinutes = mutableListOf<Long>()

        for (delay in 1..20) {
            val next = WarmPollingSchedule.nextTrigger(now, deadline, delay) ?: break
            now = next
            elapsedMinutes += now / WarmUpdateScheduler.MINUTE_MILLIS
        }

        assertThat(elapsedMinutes).containsExactly(1L, 3L, 6L, 10L, 15L, 21L, 28L, 36L, 45L, 55L).inOrder()
    }

    @Test
    fun `trigger beyond deadline is rejected`() {
        assertThat(WarmPollingSchedule.nextTrigger(55_000L, 60_000L, 1)).isNull()
    }
}
