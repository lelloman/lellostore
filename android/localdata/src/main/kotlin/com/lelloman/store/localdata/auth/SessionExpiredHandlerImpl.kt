package com.lelloman.store.localdata.auth

import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.auth.SessionExpiredHandler
import com.lelloman.store.logger.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SessionExpiredHandlerImpl(
    private val authStore: AuthStore,
    private val logger: Logger,
) : SessionExpiredHandler {

    private val mutableSessionExpiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val sessionExpiredEvents: SharedFlow<Unit> = mutableSessionExpiredEvents.asSharedFlow()

    override fun onSessionExpired() {
        // Only emit if currently authenticated to avoid duplicate events
        if (authStore.authState.value is AuthState.Authenticated) {
            logger.w(TAG, "Session expired, emitting logout event")
            mutableSessionExpiredEvents.tryEmit(Unit)
        }
    }

    companion object {
        private const val TAG = "SessionExpiredHandler"
    }
}
