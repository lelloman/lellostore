package com.lelloman.store.ui.screen.login

data class LoginScreenState(
    val serverUrl: String = "",
    val serverUrlError: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val serverName: String? = null,
    val deviceTokenRejected: Boolean = false,
    /** The confirmed store lets a device enroll by device code, without signing in. */
    val deviceEnrollmentAvailable: Boolean = false,
    /** Enrollment is the main action on a TV, where a browser sign-in is impractical. */
    val isTelevision: Boolean = false,
)
