package com.lelloman.store.domain.auth

import kotlinx.coroutines.flow.StateFlow

interface AuthStore {
    val authState: StateFlow<AuthState>
    suspend fun getAccessToken(): String?

    /** Like [getAccessToken], but refreshes early unless the token stays valid for [minValiditySeconds]. */
    suspend fun getAccessToken(minValiditySeconds: Long): String? = getAccessToken()
    suspend fun logout()
}

sealed interface AuthState {
    data object Loading : AuthState
    data object NotAuthenticated : AuthState
    data class Authenticated(val userEmail: String) : AuthState
}

sealed interface AuthResult {
    data object Success : AuthResult
    data object Cancelled : AuthResult
    data class Error(val message: String) : AuthResult
}
