package com.lelloman.store.recovery.protocol

object RecoveryContract {
    const val STORE_PACKAGE = "com.lelloman.store"
    const val RECOVERY_PACKAGE = "com.lelloman.store.recovery"
    const val SERVICE_CLASS = "$RECOVERY_PACKAGE.RecoveryService"
    const val SIGNATURE_PERMISSION = "$RECOVERY_PACKAGE.permission.RECOVERY_CONTROL"
    const val PROTOCOL_VERSION = 2
    const val MIN_COMPANION_VERSION = 2
}
