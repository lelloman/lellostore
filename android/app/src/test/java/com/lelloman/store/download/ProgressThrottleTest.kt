package com.lelloman.store.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressThrottleTest {

    private var now = 0L
    private val throttle = ProgressThrottle(intervalNanos = 250_000_000L, clock = { now })

    @Test
    fun `publishes the first update`() {
        assertThat(throttle.shouldPublish(8_192, 10_000_000)).isTrue()
    }

    @Test
    fun `chunks within the same percent and interval are skipped`() {
        val total = 10_000_000L
        assertThat(throttle.shouldPublish(8_192, total)).isTrue()
        now += 10_000_000L
        assertThat(throttle.shouldPublish(16_384, total)).isFalse()
        now += 10_000_000L
        assertThat(throttle.shouldPublish(24_576, total)).isFalse()
    }

    @Test
    fun `publishes when the integer percent changes`() {
        val total = 1_000_000L
        assertThat(throttle.shouldPublish(1_000, total)).isTrue()
        assertThat(throttle.shouldPublish(9_000, total)).isFalse()
        assertThat(throttle.shouldPublish(10_000, total)).isTrue()
    }

    @Test
    fun `publishes after the interval even without a percent change`() {
        val total = 100_000_000L
        assertThat(throttle.shouldPublish(8_192, total)).isTrue()
        now += 249_000_000L
        assertThat(throttle.shouldPublish(16_384, total)).isFalse()
        now += 1_000_000L
        assertThat(throttle.shouldPublish(24_576, total)).isTrue()
    }

    @Test
    fun `always publishes the final byte count`() {
        val total = 1_000_000L
        assertThat(throttle.shouldPublish(995_000, total)).isTrue()
        assertThat(throttle.shouldPublish(999_000, total)).isFalse()
        assertThat(throttle.shouldPublish(1_000_000, total)).isTrue()
    }

    @Test
    fun `an eight KiB chunked download publishes about once per percent`() {
        val total = 8L * 1024 * 1024
        var published = 0
        var bytes = 0L
        while (bytes < total) {
            bytes += 8_192
            if (throttle.shouldPublish(bytes, total)) published++
        }
        // 1024 chunks collapse to one publication per percent (plus the first and the final).
        assertThat(published).isAtMost(102)
        assertThat(throttle.shouldPublish(total, total)).isTrue()
    }
}
