package com.lelloman.store.notification

/** All times are elapsedRealtime milliseconds; wall-clock changes cannot train this model. */
class AdaptiveHeartbeat(initial: Long = INITIAL) {
    var interval: Long = initial.coerceIn(MINIMUM, MAXIMUM)
        private set
    private var successes = 0
    fun acknowledged(idleFor: Long, alarmLateBy: Long): Boolean {
        if (idleFor < interval * 3 / 4 || alarmLateBy > 10_000) return false
        if (++successes < 3) return false
        successes = 0
        interval = (interval * 102 / 100).coerceAtMost(MAXIMUM)
        return true
    }
    fun failed(networkUnchanged: Boolean, alarmOnTime: Boolean, serverReachable: Boolean) {
        successes = 0
        if (networkUnchanged && alarmOnTime && serverReachable) interval = (interval * 80 / 100).coerceAtLeast(MINIMUM)
    }
    companion object {
        const val INITIAL = 300_000L
        const val MINIMUM = 60_000L
        const val MAXIMUM = 900_000L
        const val ACK_TIMEOUT = 90_000L
    }
}
