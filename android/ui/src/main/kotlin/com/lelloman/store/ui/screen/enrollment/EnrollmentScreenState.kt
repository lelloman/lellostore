package com.lelloman.store.ui.screen.enrollment

import com.lelloman.store.ui.screen.settings.UpdateCheckIntervalOption

sealed interface EnrollmentScreenState {
    /** Choosing the name the administrator sees; the enrollment starts from here. */
    data class Naming(val name: String, val invalidName: Boolean = false) : EnrollmentScreenState

    /**
     * An enrollment is running. [code] is null while a code is being requested (also after an
     * expiry); [offline] says the last request failed and is being retried.
     */
    data class Waiting(
        val name: String,
        val storeHost: String,
        val code: EnrollmentCode? = null,
        val remainingSeconds: Long = 0,
        val expiredCodes: Int = 0,
        val offline: Boolean = false,
    ) : EnrollmentScreenState

    /** The enrollment ended without a credential; [reason] says why and whether retrying helps. */
    data class Stopped(val name: String, val reason: StopReason) : EnrollmentScreenState

    /** Approved: the credential is being stored and the updates turned on. */
    data object Completing : EnrollmentScreenState

    data class Completed(val completion: EnrollmentCompletion) : EnrollmentScreenState
}

/** What the TV shows: [userCode] to type, [verificationUriComplete] in the QR code. */
data class EnrollmentCode(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
)

enum class StopReason {
    /** Three codes in a row expired without an approval. */
    EXPIRED,

    /** An administrator denied the request. */
    DENIED,

    /** A recent denial blocks enrollments from this network for a while. */
    BLOCKED,

    /** Too many enrollments from this network; try again later. */
    RATE_LIMITED,

    /** The store refused the request or no longer knows the code (e.g. already used). */
    REFUSED,

    /** This store does not support enrollment. */
    UNAVAILABLE,

    /** The approved credential could not be read or stored. */
    NOT_STORED,
}

/** The device is enrolled as [deviceName]; what was turned on for it. */
data class EnrollmentCompletion(
    val deviceName: String,
    val automaticUpdates: Boolean,
    val updateCheckInterval: UpdateCheckIntervalOption,
    val updateConnection: Boolean,
)

sealed interface EnrollmentScreenEvent {
    data object NavigateToMain : EnrollmentScreenEvent
}
