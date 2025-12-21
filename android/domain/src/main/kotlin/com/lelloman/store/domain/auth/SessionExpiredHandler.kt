package com.lelloman.store.domain.auth

import kotlinx.coroutines.flow.SharedFlow

/**
 * Handles session expiration events triggered by 401 (Unauthorized) responses from the server.
 * The UI layer observes [sessionExpiredEvents] to navigate to the login screen.
 */
interface SessionExpiredHandler {
    /**
     * Flow of session expired events. Each emission indicates the user should be logged out
     * and navigated to the login screen.
     */
    val sessionExpiredEvents: SharedFlow<Unit>

    /**
     * Called when a 401 response is received, indicating the session has expired.
     * This will emit an event to [sessionExpiredEvents] if the user is currently logged in.
     */
    fun onSessionExpired()
}
