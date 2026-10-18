package com.lelloman.store.domain.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AuthStore {
    val authState: StateFlow<AuthState>
    suspend fun getAccessToken(): String?

    /** Like [getAccessToken], but refreshes early unless the token stays valid for [minValiditySeconds]. */
    suspend fun getAccessToken(minValiditySeconds: Long): String? = getAccessToken()
    suspend fun logout()

    /** True after the server rejected a provisioned device token in this process. */
    val deviceTokenRejected: StateFlow<Boolean> get() = NeverRejected
}

private val NeverRejected: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

sealed interface AuthState {
    data object Loading : AuthState
    data object NotAuthenticated : AuthState
    /** For a provisioned device token, [userEmail] carries the token's display name. */
    data class Authenticated(val userEmail: String, val deviceToken: Boolean = false) : AuthState
}

sealed interface AuthResult {
    data object Success : AuthResult
    data object Cancelled : AuthResult
    data class Error(val message: String) : AuthResult
}
