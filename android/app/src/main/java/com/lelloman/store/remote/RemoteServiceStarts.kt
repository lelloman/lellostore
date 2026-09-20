package com.lelloman.store.remote

/** Keeps shutdown from overtaking an undelivered startForegroundService request. */
internal class RemoteServiceStarts {
    private var pending = 0

    @Synchronized fun request(start: () -> Unit) {
        pending++
        try {
            start()
        } catch (error: Exception) {
            pending--
            throw error
        }
    }

    // Called only after the service has called startForeground for this request.
    @Synchronized fun delivered() {
        if (pending > 0) pending--
    }

    @Synchronized fun stopIfIdle(isActive: () -> Boolean, stop: () -> Unit): Boolean {
        if (pending != 0 || isActive()) return false
        stop()
        return true
    }
}
