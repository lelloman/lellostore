package com.lelloman.store.localdata.auth

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SessionExpiredHandlerImplTest {

    private lateinit var fakeAuthStore: FakeAuthStore
    private lateinit var fakeLogger: FakeLogger
    private lateinit var handler: SessionExpiredHandlerImpl

    @Before
    fun setup() {
        fakeAuthStore = FakeAuthStore()
        fakeLogger = FakeLogger()
        handler = SessionExpiredHandlerImpl(fakeAuthStore, fakeLogger)
    }

    @Test
    fun `onSessionExpired emits event when authenticated`() = runTest {
        fakeAuthStore.setAuthState(AuthState.Authenticated("test@example.com"))

        handler.sessionExpiredEvents.test {
            handler.onSessionExpired()
            assertThat(awaitItem()).isEqualTo(Unit)
        }
    }

    @Test
    fun `onSessionExpired does not emit when not authenticated`() = runTest {
        fakeAuthStore.setAuthState(AuthState.NotAuthenticated)

        handler.sessionExpiredEvents.test {
            handler.onSessionExpired()
            expectNoEvents()
        }
    }

    @Test
    fun `onSessionExpired does not emit when loading`() = runTest {
        fakeAuthStore.setAuthState(AuthState.Loading)

        handler.sessionExpiredEvents.test {
            handler.onSessionExpired()
            expectNoEvents()
        }
    }

    @Test
    fun `onSessionExpired logs warning when authenticated`() = runTest {
        fakeAuthStore.setAuthState(AuthState.Authenticated("test@example.com"))

        handler.onSessionExpired()

        assertThat(fakeLogger.warnings).hasSize(1)
        assertThat(fakeLogger.warnings.first()).contains("Session expired")
    }

    private class FakeAuthStore : AuthStore {
        private val mutableAuthState = MutableStateFlow<AuthState>(AuthState.Loading)
        override val authState: StateFlow<AuthState> = mutableAuthState

        fun setAuthState(state: AuthState) {
            mutableAuthState.value = state
        }

        override suspend fun getAccessToken(): String? = null
        override suspend fun logout() {}
    }

    private class FakeLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {
            warnings.add(message)
        }
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }
}
