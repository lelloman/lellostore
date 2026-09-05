package com.lelloman.store.recovery

import android.app.Application

class RecoveryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A persisted REPAIRING state on a new process means the previous repair was interrupted.
        RecoveryAttemptStore(this).update { RecoveryPolicy.evaluate(it, System.currentTimeMillis()) }
    }
}
