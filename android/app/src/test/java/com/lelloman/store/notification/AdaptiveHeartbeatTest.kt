package com.lelloman.store.notification

import org.junit.Assert.*
import org.junit.Test

class AdaptiveHeartbeatTest {
    @Test fun idleSuccessesGrowConservatively() {
        val model = AdaptiveHeartbeat()
        repeat(2) { assertFalse(model.acknowledged(300_000, 0)) }
        assertTrue(model.acknowledged(300_000, 0))
        assertEquals(306_000L, model.interval)
    }
    @Test fun trafficAndDelayedAlarmsDoNotTrain() {
        val model = AdaptiveHeartbeat()
        repeat(100) { model.acknowledged(1_000, 0); model.acknowledged(900_000, 10_001) }
        assertEquals(300_000L, model.interval)
    }
    @Test fun onlyClassifiedIdleFailuresReduceInterval() {
        val model = AdaptiveHeartbeat()
        model.failed(false, true, true)
        model.failed(true, false, true)
        model.failed(true, true, false)
        assertEquals(300_000L, model.interval)
        model.failed(true, true, true)
        assertEquals(240_000L, model.interval)
        repeat(100) { model.failed(true, true, true) }
        assertEquals(60_000L, model.interval)
    }
    @Test fun growthHasBatterySafetyCeiling() {
        val model = AdaptiveHeartbeat(900_000)
        repeat(300) { model.acknowledged(900_000, 0) }
        assertEquals(900_000L, model.interval)
    }
}
